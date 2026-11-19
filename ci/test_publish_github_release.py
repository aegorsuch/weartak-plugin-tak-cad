import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit

import publish_github_release as publisher


APK = "WearTAK-Plugin-TAK-CAD-5.8.4-p49.apk"
SUMS = "WearTAK-Plugin-TAK-CAD-5.8.4-p49-SHA256SUMS"
CONTENT = b"test APK"
DIGEST = hashlib.sha256(CONTENT).hexdigest()
FILES = {APK: CONTENT, SUMS: f"{DIGEST}  {APK}\n".encode()}
ENV = {
    "CI_SERVER_HOST": "git.tak.gov",
    "CI_PROJECT_PATH": publisher.PROJECT,
    "CI_DEFAULT_BRANCH": "main",
    "CI_COMMIT_BRANCH": "main",
    "CI_COMMIT_REF_PROTECTED": "true",
    "CI_PIPELINE_SOURCE": "push",
    "CI_PROJECT_ID": "123",
    "CI_PIPELINE_IID": "49",
    "CI_PIPELINE_ID": "234",
    "CI_PIPELINE_URL": f"https://git.tak.gov/{publisher.PROJECT}/-/pipelines/234",
    "CI_COMMIT_SHA": "a" * 40,
    "CI_COMMIT_SHORT_SHA": "a" * 8,
    "TAK_CAD_GITHUB_TOKEN": "source-token",
    "MARKET_CATALOG_DISPATCH_TOKEN": "market-token",
}


class RollingRelease:
    def __init__(self):
        manual_name = "WearTAK-Plugin-TAK-CAD-5.8.4-p48.apk"
        manual_digest = "b" * 64
        self.release = {
            "id": 7, "tag_name": publisher.TAG, "draft": False, "prerelease": False,
            "body": (
                f"- Current APK: `{manual_name}`\r\n"
                "- Build: GitLab pipeline **233** (project pipeline **48**)\r\n"
                f"- Pipeline: https://git.tak.gov/{publisher.PROJECT}/-/pipelines/233\r\n"
                f"- Source commit: `{'c' * 40}` on TAK.gov `main`\r\n"
                f"- SHA256: `{manual_digest}`\r\n"
                f"- Production signer SHA256: `{publisher.EXPECTED_CERTIFICATE_SHA256}`\r\n"
            ),
        }
        self.contents = {}
        self.items = [
            {"id": 1, "name": manual_name, "state": "uploaded", "digest": f"sha256:{manual_digest}"},
            {"id": 2, "name": "SHA256SUMS", "state": "uploaded"},
        ]
        self.events = []
        self.corrupt_asset = None
        self.corrupt_readback = False
        self.fail_cleanup = False

    def request(self, method, path="", data=None, *, content_type=None):
        if not path:
            return {"full_name": publisher.REPO, "archived": False, "disabled": False}
        if path.startswith("/git/ref/"):
            return {"ref": f"refs/tags/{publisher.TAG}"}
        if method == "GET":
            self.events.append("read release")
            return {**self.release, "draft": True} if self.corrupt_readback else self.release.copy()
        if method == "PATCH":
            self.events.append("publish notes")
            self.release.update(data)
            return self.release.copy()
        if method == "POST":
            fields = parse_qs(urlsplit(path).query)
            name, label = fields["name"][0], fields["label"][0]
            self.events.append(f"upload {name}")
            item = {"id": 100 + len(self.contents), "name": name, "label": label,
                    "state": "uploaded", "size": len(data)}
            self.contents[item["id"]] = data
            self.items.append(item)
            return item.copy()
        if method == "DELETE":
            asset_id = int(path.rsplit("/", 1)[1])
            self.events.append(f"delete {asset_id}")
            if self.fail_cleanup:
                raise publisher.PublishError("cleanup failed")
            self.items = [a for a in self.items if a["id"] != asset_id]
            return None
        raise AssertionError(method)

    def assets(self, release_id):
        return [asset.copy() for asset in self.items]

    def verify_download(self, asset_id, expected):
        name = next(a["name"] for a in self.items if a["id"] == asset_id)
        self.events.append(f"verify {name}")
        if name == self.corrupt_asset or self.contents[asset_id] != expected:
            raise publisher.PublishError("bad download")
        if name == APK and getattr(self, "fail_readback", False):
            self.corrupt_readback = True


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.remote = RollingRelease()
        self.state = publisher.configuration(ENV)
        self.notify = Mock(side_effect=lambda: self.remote.events.append("dispatch"))

    def publish(self, state=None, notify=None):
        publisher.publish(self.remote, state or self.state, FILES, DIGEST, notify or self.notify)

    def add_owned_asset(self, iid):
        name = f"WearTAK-Plugin-TAK-CAD-5.8.4-p{iid}.apk"
        self.remote.items.append({"id": 12, "name": name, "label": f"{publisher.MARKER}:{name}"})

    def test_verifies_and_cleans_up_only_owned_assets_before_dispatch(self):
        self.add_owned_asset(47)
        self.publish()
        self.assertEqual(self.remote.events, [
            "read release", f"upload {SUMS}", f"verify {SUMS}",
            f"upload {APK}", f"verify {APK}", "publish notes", "read release", "delete 12", "dispatch",
        ])
        self.assertEqual([a["id"] for a in self.remote.items], [1, 2, 100, 101])

    def test_failed_release_checks_never_dispatch_or_delete(self):
        for failure, error in (("bad download", "bad download"), ("readback", "stable rolling release"),
                               ("prerelease", "stable rolling release")):
            with self.subTest(failure=failure):
                self.remote = RollingRelease()
                self.add_owned_asset(47)
                self.remote.corrupt_asset = APK if failure == "bad download" else None
                self.remote.fail_readback = failure == "readback"
                self.remote.release["prerelease"] = failure == "prerelease"
                with self.assertRaisesRegex(publisher.PublishError, error):
                    self.publish()
                self.assertIn(12, [a["id"] for a in self.remote.items])
        self.notify.assert_not_called()

    def test_stale_manual_or_newer_in_progress_pipeline_does_not_dispatch(self):
        self.publish({**self.state, "pipeline_iid": 47})
        self.add_owned_asset(50)
        self.publish()
        self.assertEqual(self.remote.events, ["read release", "read release"])
        self.notify.assert_not_called()

    def test_missing_market_token_leaves_release_published_and_retry_reverifies(self):
        with self.assertRaisesRegex(publisher.PublishError, "remains published.*MARKET_CATALOG_DISPATCH_TOKEN"):
            self.publish(notify=lambda: publisher.dispatch_market(None))
        self.assertIn(publisher.MARKER, self.remote.release["body"])
        self.remote.events.clear()
        self.publish()
        self.assertEqual(self.remote.events, [
            "read release", f"verify {SUMS}", f"verify {APK}", "read release", "dispatch",
        ])

    def test_cleanup_failure_blocks_dispatch_and_same_pipeline_can_finish(self):
        self.add_owned_asset(47)
        self.remote.fail_cleanup = True
        with self.assertRaisesRegex(publisher.PublishError, "cleanup failed"):
            self.publish()
        self.notify.assert_not_called()
        self.remote.fail_cleanup = False
        self.publish()
        self.assertEqual(self.remote.events[-2:], ["delete 12", "dispatch"])

    def test_unrelated_same_name_asset_is_not_replaced(self):
        self.remote.items.append({"id": 11, "name": APK, "state": "uploaded"})
        with self.assertRaisesRegex(publisher.PublishError, "refusing replacement"):
            self.publish()
        self.assertEqual(self.remote.events, ["read release"])
        self.notify.assert_not_called()

    def test_forge_artifact_must_match_pipeline_and_checksum(self):
        with TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / APK).write_bytes(CONTENT)
            (directory / "SHA256SUMS").write_bytes(FILES[SUMS])
            self.assertEqual(publisher.artifacts(directory, self.state), (FILES, DIGEST))
            with self.assertRaisesRegex(publisher.PublishError, "filename"):
                publisher.artifacts(directory, {**self.state, "pipeline_iid": 48})
            (directory / APK).write_bytes(b"tampered")
            with self.assertRaisesRegex(publisher.PublishError, "checksum"):
                publisher.artifacts(directory, self.state)

    def test_refuses_unprotected_or_non_push_pipelines(self):
        for key, value in (("CI_COMMIT_REF_PROTECTED", "false"), ("CI_PIPELINE_SOURCE", "web")):
            with self.assertRaisesRegex(publisher.PublishError, key):
                publisher.configuration({**ENV, key: value})

    @patch.object(publisher, "dispatch_market")
    @patch.object(publisher, "GitHub")
    @patch.object(publisher, "artifacts", return_value=(FILES, DIGEST))
    def test_main_keeps_credentials_separate(self, artifacts, api, dispatch):
        api.return_value = self.remote
        with patch.dict(publisher.os.environ, ENV, clear=True):
            self.assertEqual(publisher.main(), 0)
        api.assert_called_once_with("source-token")
        dispatch.assert_called_once_with("market-token")
        api.reset_mock()
        with patch.dict(publisher.os.environ, {**ENV, "TAK_CAD_GITHUB_TOKEN": "market-token"}, clear=True):
            self.assertEqual(publisher.main(), 1)
        api.assert_not_called()


class TransportTest(unittest.TestCase):
    @patch.object(publisher.urllib.request, "build_opener")
    def test_market_requires_204_and_redacts_http_error(self, build_opener):
        opener = build_opener.return_value
        response = opener.open.return_value.__enter__.return_value
        response.status = 204
        publisher.dispatch_market("market-token")
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, publisher.MARKET_URL)
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(json.loads(request.data), {"ref": "main"})
        self.assertEqual(request.get_header("Authorization"), "Bearer market-token")
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 30)
        with self.assertRaisesRegex(publisher.PublishError, "refused a redirect"):
            build_opener.call_args.args[0].redirect_request(
                request, None, 302, "", {}, "https://example.test/other",
            )
        response.status = 200
        with self.assertRaisesRegex(publisher.PublishError, "HTTP 204"):
            publisher.dispatch_market("market-token")
        opener.open.side_effect = HTTPError("https://example.test/secret", 403, "market-token", {}, None)
        with self.assertRaisesRegex(publisher.PublishError, r"^Market dispatch returned HTTP 403\.$"):
            publisher.dispatch_market("market-token")

    @patch.object(publisher.urllib.request, "build_opener")
    def test_asset_download_checks_actual_bytes_and_strips_redirect_credentials(self, build_opener):
        api = publisher.GitHub("source-token")
        opener = build_opener.return_value
        response = opener.open.return_value.__enter__.return_value
        response.read.return_value = CONTENT
        api.verify_download(15, CONTENT)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.get_header("Authorization"), "Bearer source-token")
        redirect = build_opener.call_args.args[0].redirect_request(
            request, None, 302, "", {}, "https://example.test/storage",
        )
        self.assertIsNone(redirect.get_header("Authorization"))
        response.read.return_value = b"x" * len(CONTENT)
        with self.assertRaisesRegex(publisher.PublishError, "SHA256"):
            api.verify_download(15, CONTENT)

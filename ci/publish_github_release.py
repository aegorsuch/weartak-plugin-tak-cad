"""Publish the protected Forge pipeline's verified artifacts to the personal mirror."""

import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

from verify_release_signature import EXPECTED_CERTIFICATE_SHA256

PROJECT = "weartak-plugins/weartak-plugin-tak-cad"
REPO = "aegorsuch/weartak-plugin-tak-cad"
TAG = "TAK.gov-Forge-CI-CD-Pipeline-Release"
MARKER = "tak-cad-rolling-v1"
PREFIX = "WearTAK-Plugin-TAK-CAD-"
ASSET = re.compile(rf"({PREFIX}[0-9A-Za-z][0-9A-Za-z._-]*-p([1-9][0-9]*))"
                   r"(?:\.apk|-SHA256SUMS)")
MARKET_URL = (
    "https://api.github.com/repos/aegorsuch/weartak-wearos/"
    "actions/workflows/publish-market-catalog.yml/dispatches"
)


class PublishError(Exception):
    pass


class RefuseRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise PublishError("GitHub API request refused a redirect.")


class AssetRedirect(RefuseRedirect):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        url = urllib.parse.urlsplit(newurl)
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise PublishError("Asset download refused an unsafe redirect.")
        return urllib.request.Request(newurl, method="GET")


def headers(token):
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "TAK-CAD-Forge-release",
    }


def dispatch_market(token):
    if not token:
        raise PublishError("Missing protected, masked MARKET_CATALOG_DISPATCH_TOKEN.")
    request = urllib.request.Request(
        MARKET_URL, data=b'{"ref":"main"}', method="POST",
        headers={**headers(token), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.build_opener(RefuseRedirect()).open(request, timeout=30) as response:
            if response.status != 204:
                raise PublishError("Market dispatch did not return HTTP 204.")
    except urllib.error.HTTPError as error:
        raise PublishError(f"Market dispatch returned HTTP {error.code}.") from None
    except (OSError, ValueError, http.client.HTTPException):
        raise PublishError("Market dispatch transport failed.") from None


class GitHub:
    def __init__(self, token):
        self.headers = headers(token)
        self.api = urllib.request.build_opener(RefuseRedirect())
        self.download = urllib.request.build_opener(AssetRedirect())

    def request(self, method, path="", data=None, *, content_type=None):
        host = "uploads.github.com" if content_type else "api.github.com"
        request_headers = self.headers.copy()
        if data is not None:
            request_headers["Content-Type"] = content_type or "application/json"
            if not content_type:
                data = json.dumps(data).encode()
        request = urllib.request.Request(
            f"https://{host}/repos/{REPO}{path}", data=data,
            headers=request_headers, method=method,
        )
        try:
            with self.api.open(request, timeout=60) as response:
                return None if response.status == 204 else json.load(response)
        except urllib.error.HTTPError as error:
            raise PublishError(f"GitHub release API {method} returned HTTP {error.code}.") from None

    def assets(self, release_id):
        assets = []
        for page in range(1, 21):
            batch = self.request("GET", f"/releases/{release_id}/assets?per_page=100&page={page}")
            assets.extend(batch)
            if len(batch) < 100:
                return assets
        raise PublishError("GitHub release asset list exceeds verification limit.")

    def verify_download(self, asset_id, expected):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases/assets/{asset_id}",
            headers={**self.headers, "Accept": "application/octet-stream"},
        )
        try:
            with self.download.open(request, timeout=60) as response:
                content = response.read(len(expected) + 1)
        except urllib.error.HTTPError as error:
            raise PublishError(f"GitHub release asset download returned HTTP {error.code}.") from None
        if len(content) != len(expected) or hashlib.sha256(content).digest() != hashlib.sha256(expected).digest():
            raise PublishError("Published asset failed downloaded size/SHA256 verification.")


def configuration(env):
    expected = {
        "CI_SERVER_HOST": "git.tak.gov",
        "CI_PROJECT_PATH": PROJECT,
        "CI_COMMIT_BRANCH": "main",
        "CI_DEFAULT_BRANCH": "main",
        "CI_COMMIT_REF_PROTECTED": "true",
        "CI_PIPELINE_SOURCE": "push",
    }
    for key, value in expected.items():
        if env.get(key) != value:
            raise PublishError(f"Publishing requires {key}={value}.")
    for key in ("CI_PROJECT_ID", "CI_PIPELINE_ID", "CI_PIPELINE_IID"):
        if not re.fullmatch(r"[1-9][0-9]*", env.get(key, "")):
            raise PublishError(f"Invalid {key}.")
    sha = env.get("CI_COMMIT_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or env.get("CI_COMMIT_SHORT_SHA") != sha[:8]:
        raise PublishError("Invalid source commit SHA.")
    pipeline_url = f"https://git.tak.gov/{PROJECT}/-/pipelines/{env['CI_PIPELINE_ID']}"
    if env.get("CI_PIPELINE_URL") != pipeline_url:
        raise PublishError("Pipeline URL is not the canonical Forge URL.")
    return {
        "project_id": env["CI_PROJECT_ID"],
        "pipeline_id": env["CI_PIPELINE_ID"],
        "pipeline_iid": int(env["CI_PIPELINE_IID"]),
        "pipeline_url": pipeline_url,
        "source_sha": sha,
    }


def artifacts(directory, state):
    apks = list(directory.glob("*.apk"))
    sums = directory / "SHA256SUMS"
    if len(apks) != 1 or any(p.is_symlink() or not p.is_file() for p in [*apks, sums]):
        raise PublishError("Expected one verified Forge APK and its SHA256SUMS artifact.")
    apk = apks[0]
    match = ASSET.fullmatch(apk.name)
    if not match or int(match[2]) != state["pipeline_iid"]:
        raise PublishError("APK filename does not match the pipeline.")
    content = apk.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    checksum = f"{digest}  {apk.name}\n".encode()
    if not content or sums.read_bytes() != checksum:
        raise PublishError("APK does not match its Forge signature-verification checksum.")
    return {apk.name: content, f"{match[1]}-SHA256SUMS": checksum}, digest


def owned_iid(asset):
    name = asset["name"]
    match = ASSET.fullmatch(name)
    if match and asset.get("label") == f"{MARKER}:{name}":
        return int(match[2])
    return None


def check_stable(release):
    if (release.get("tag_name") != TAG or release.get("draft") is not False
            or release.get("prerelease") is not False or release.get("immutable") is True
            or type(release.get("id")) is not int or release["id"] <= 0):
        raise PublishError("Expected an existing mutable, published stable rolling release.")


def previous_pipeline(release, assets, project_id):
    body = release.get("body") or ""
    if MARKER in body:
        records = re.findall(rf"^<!-- {MARKER} (.+) -->$", body, re.MULTILINE)
        if len(records) != 1:
            raise PublishError("Invalid rolling release provenance marker.")
        state = json.loads(records[0])
        if (not isinstance(state, dict) or state.get("project_id") != project_id
                or type(state.get("pipeline_iid")) is not int or state["pipeline_iid"] <= 0):
            raise PublishError("Invalid rolling release project or pipeline provenance.")
        return state["pipeline_iid"], state

    # Adopt only the existing manual format, corroborated by the published asset.
    def extract(pattern):
        matches = re.findall(pattern, body, re.MULTILINE)
        if len(matches) != 1:
            raise PublishError("Unrecognized manual rolling release provenance; refusing adoption.")
        return matches[0]

    name = extract(r"^- Current APK: `([^`]+)`\s*$")
    match = ASSET.fullmatch(name)
    pipeline_id, iid_text = extract(r"^- Build: GitLab pipeline \*\*([1-9][0-9]*)\*\* "
                                    r"\(project pipeline \*\*([1-9][0-9]*)\*\*\)\s*$")
    iid = int(iid_text)
    url = extract(r"^- Pipeline: (\S+)\s*$")
    extract(r"^- Source commit: `([0-9a-f]{40})` on TAK.gov `main`\s*$")
    signer = extract(r"^- Production signer SHA256: `([0-9a-f]{64})`\s*$")
    digest = extract(r"^- SHA256: `([0-9a-f]{64})`\s*$")
    if (url != f"https://git.tak.gov/{PROJECT}/-/pipelines/{pipeline_id}"
            or signer != EXPECTED_CERTIFICATE_SHA256
            or not match or not name.endswith(".apk") or int(match[2]) != iid or not any(
        asset["name"] == name and asset.get("state") == "uploaded"
        and asset.get("digest") == f"sha256:{digest}" for asset in assets
    )):
        raise PublishError("Manual release notes do not match the published APK.")
    return iid, None


def verify_asset_metadata(asset, name, size):
    return (asset["name"] == name and owned_iid(asset) is not None
            and asset.get("state") == "uploaded" and asset.get("size") == size
            and type(asset.get("id")) is int and asset["id"] > 0)


def publish(api, state, files, digest, notify):
    repository = api.request("GET")
    if (repository.get("full_name") != REPO or repository.get("archived") is not False
            or repository.get("disabled") is not False):
        raise PublishError("Destination must be the approved active personal repository.")
    if api.request("GET", f"/git/ref/tags/{TAG}").get("ref") != f"refs/tags/{TAG}":
        raise PublishError("The fixed release anchor tag must already exist.")
    release = api.request("GET", f"/releases/tags/{TAG}")
    check_stable(release)
    release_id = release["id"]
    before = api.assets(release_id)
    previous_iid, previous = previous_pipeline(release, before, state["project_id"])
    iid = state["pipeline_iid"]
    if previous_iid > iid or any(
        (owned_iid(asset) or 0) > iid for asset in before
    ):
        print("Skipping stale pipeline: a newer pipeline already reached the rolling release.")
        return
    state = {**state, "sha256": digest}
    if previous_iid == iid and previous != state:
        raise PublishError("Same pipeline is manual or has different provenance; refusing replacement.")
    apk_name, checksum_name = files
    build = apk_name.removeprefix(PREFIX).removesuffix(".apk")
    title = f"TAK.gov Forge CI/CD Pipeline Release ({build})"
    body = (
        "Automatically published stable TAK-CAD main-channel build.\n\n"
        f"- Current APK: `{apk_name}`\n"
        f"- Matching checksum: `{checksum_name}`\n"
        f"- Build: GitLab pipeline **{state['pipeline_id']}** (project pipeline **{iid}**)\n"
        f"- Pipeline: {state['pipeline_url']}\n"
        f"- Source commit: `{state['source_sha']}` on TAK.gov `main`\n"
        f"- SHA256: `{digest}`\n"
        f"- Production signer SHA256: `{EXPECTED_CERTIFICATE_SHA256}`\n\n"
        "GitHub source archives use the fixed release anchor, not this build's commit.\n\n"
        f"<!-- {MARKER} {json.dumps(state, sort_keys=True)} -->"
    )
    matches = {name: [asset for asset in before if asset["name"] == name] for name in files}
    for name, existing in matches.items():
        if existing and (len(existing) != 1 or not verify_asset_metadata(existing[0], name, len(files[name]))):
            raise PublishError("Existing same-name asset is unrelated or incomplete; refusing replacement.")
    uploaded = {}
    for name in (checksum_name, apk_name):
        content = files[name]
        if matches[name]:
            asset = matches[name][0]
        else:
            query = urllib.parse.urlencode({"name": name, "label": f"{MARKER}:{name}"})
            asset = api.request(
                "POST", f"/releases/{release_id}/assets?{query}", content,
                content_type="application/vnd.android.package-archive" if name.endswith(".apk") else "text/plain",
            )
        if not verify_asset_metadata(asset, name, len(content)):
            raise PublishError("GitHub upload returned unexpected asset metadata.")
        api.verify_download(asset["id"], content)
        uploaded[name] = asset["id"]

    if release.get("name") != title or release.get("body") != body:
        api.request("PATCH", f"/releases/{release_id}", {"name": title, "body": body})
    confirmed = api.request("GET", f"/releases/tags/{TAG}")
    check_stable(confirmed)
    if confirmed["id"] != release_id or confirmed.get("name") != title or confirmed.get("body") != body:
        raise PublishError("Stable release readback does not match this pipeline.")
    current = api.assets(release_id)
    if any((owned_iid(asset) or 0) > iid for asset in current):
        raise PublishError("A newer pipeline's assets appeared during release verification.")
    for name, asset_id in uploaded.items():
        if not any(a["id"] == asset_id and verify_asset_metadata(a, name, len(files[name])) for a in current):
            raise PublishError("Stable release readback is missing a verified asset.")
    for asset in current:
        old_iid = owned_iid(asset)
        if old_iid is not None and old_iid < iid:
            api.request("DELETE", f"/releases/assets/{asset['id']}")

    print(f"Verified published stable APK: {apk_name} (SHA256 {digest}).")
    try:
        notify()
    except PublishError as error:
        raise PublishError(
            f"Stable release remains published, but Market notification failed: {error} "
            "Retry this same publish job to reverify and redispatch; hourly refresh remains available."
        ) from None
    print("Market catalog dispatch accepted.")


def main():
    try:
        state = configuration(os.environ)
        source_token = os.environ.get("TAK_CAD_GITHUB_TOKEN")
        market_token = os.environ.get("MARKET_CATALOG_DISPATCH_TOKEN")
        if not source_token:
            raise PublishError("Missing protected, masked TAK_CAD_GITHUB_TOKEN.")
        if source_token == market_token:
            raise PublishError("Release and Market credentials must be distinct.")
        files, digest = artifacts(Path("dist"), state)
        publish(GitHub(source_token), state, files, digest, lambda: dispatch_market(market_token))
    except PublishError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    except (OSError, KeyError, TypeError, ValueError, http.client.HTTPException):
        print("ERROR: Release operation failed (transport, artifact, or response error).", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

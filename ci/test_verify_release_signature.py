import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from verify_release_signature import (
    EXPECTED_CERTIFICATE_SHA256,
    release_asset_filename,
    verify_release_signature,
)


class ReleaseSignatureTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        root = Path(self.temporary_directory.name)
        self.apk = root / "app-release.apk"
        self.apksigner = root / "apksigner"
        self.aapt = root / "aapt"
        self.apk.touch()
        self.apksigner.touch()
        self.aapt.touch()

    @staticmethod
    def verified_signer():
        return subprocess.CompletedProcess(
            [], 0, f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERTIFICATE_SHA256}\n", "",
        )

    @patch("verify_release_signature.subprocess.run")
    def test_accepts_wear_tak_production_certificate(self, run):
        run.return_value = self.verified_signer()
        self.assertEqual(
            verify_release_signature(self.apk, self.apksigner),
            EXPECTED_CERTIFICATE_SHA256,
        )
        run.assert_called_once_with(
            [str(self.apksigner), "verify", "--print-certs", str(self.apk)],
            capture_output=True, text=True, check=False, timeout=60,
        )

    @patch("verify_release_signature.subprocess.run")
    def test_retains_only_verified_apk_and_checksum(self, run):
        contents = b"signed production apk"
        self.apk.write_bytes(contents)
        run.side_effect = [
            self.verified_signer(),
            subprocess.CompletedProcess(
                [], 0, "package: name='com.weartak.plugin.takcad' versionName='5.8.4-p25+abc123'\n", "",
            ),
        ]
        artifact_directory = Path(self.temporary_directory.name) / "dist"
        verify_release_signature(
            self.apk, self.apksigner, artifact_directory, pipeline_iid=25,
        )
        filename = "WearTAK-Plugin-TAK-CAD-5.8.4-p25.apk"
        self.assertEqual(
            sorted(path.name for path in artifact_directory.iterdir()),
            ["SHA256SUMS", filename],
        )
        artifact = artifact_directory / filename
        self.assertEqual(artifact.read_bytes(), contents)
        self.assertEqual(
            (artifact_directory / "SHA256SUMS").read_text(),
            f"{hashlib.sha256(contents).hexdigest()}  {filename}\n",
        )

    def test_asset_filename_uses_plugin_version_and_pipeline(self):
        self.assertEqual(
            release_asset_filename("TAK-CAD", "5.8.4-p25+abc123", 25),
            "WearTAK-Plugin-TAK-CAD-5.8.4-p25.apk",
        )

    def test_asset_filename_rejects_mismatched_pipeline(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            release_asset_filename("TAK-CAD", "5.8.4-p24+abc123", 25)

    def test_asset_filename_requires_positive_pipeline_iid(self):
        with self.assertRaisesRegex(ValueError, "positive integer"):
            release_asset_filename("TAK-CAD", "5.8.4", 0)

    def test_asset_filename_rejects_invalid_plugin_and_version(self):
        with self.assertRaisesRegex(ValueError, "Invalid plugin name"):
            release_asset_filename("../TAK-CAD", "5.8.4-p25", 25)
        with self.assertRaisesRegex(ValueError, "Invalid APK version"):
            release_asset_filename("TAK-CAD", "../5.8.4-p25", 25)

    @patch("verify_release_signature.subprocess.run")
    def test_rejects_different_certificate_without_retaining_apk(self, run):
        run.return_value = subprocess.CompletedProcess(
            [], 0, "Signer #1 certificate SHA-256 digest: " + "a" * 64, "",
        )
        artifact_directory = Path(self.temporary_directory.name) / "dist"
        with self.assertRaisesRegex(RuntimeError, "Unexpected APK signer"):
            verify_release_signature(self.apk, self.apksigner, artifact_directory)
        self.assertFalse(artifact_directory.exists())

    @patch("verify_release_signature.subprocess.run")
    def test_rejects_multiple_signers(self, run):
        run.return_value = subprocess.CompletedProcess(
            [], 0,
            f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERTIFICATE_SHA256}\n"
            f"Signer #2 certificate SHA-256 digest: {'a' * 64}\n",
            "",
        )
        with self.assertRaisesRegex(RuntimeError, "Unexpected APK signer"):
            verify_release_signature(self.apk, self.apksigner)

    @patch("verify_release_signature.subprocess.run")
    def test_rejects_invalid_apk_signature(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, "", "invalid APK")
        with self.assertRaisesRegex(RuntimeError, "APK signature verification failed"):
            verify_release_signature(self.apk, self.apksigner)

    @patch("verify_release_signature.subprocess.run")
    def test_rejects_output_without_certificate(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "Verified using v2 scheme", "")
        with self.assertRaisesRegex(RuntimeError, "none"):
            verify_release_signature(self.apk, self.apksigner)

    def test_requires_existing_apk_and_apksigner(self):
        with self.assertRaisesRegex(FileNotFoundError, "Release APK not found"):
            verify_release_signature(self.apk.with_name("missing.apk"), self.apksigner)
        with self.assertRaisesRegex(FileNotFoundError, "apksigner not found"):
            verify_release_signature(self.apk, self.apksigner.with_name("missing-apksigner"))

    def test_pipeline_limits_signing_to_protected_default_branch_pushes(self):
        pipeline = Path(".gitlab-ci.yml").read_text(encoding="utf-8")
        for condition in (
            '"${CI_COMMIT_BRANCH:-}" = "${CI_DEFAULT_BRANCH:-}"',
            '"${CI_COMMIT_REF_PROTECTED:-false}" = "true"',
            '"${CI_PIPELINE_SOURCE:-}" = "push"',
        ):
            self.assertEqual(pipeline.count(condition), 2)
        for variable in (
            "WEARTAK_RELEASE_KEYSTORE_B64",
            "RELEASE_STORE_PASSWORD",
            "RELEASE_KEY_ALIAS",
            "RELEASE_KEY_PASSWORD",
        ):
            self.assertIn(variable, pipeline)
        self.assertIn("ci/verify_release_signature.py", pipeline)
        self.assertIn("after_script:", pipeline)
        self.assertNotIn("release create", pipeline)
        verifier = pipeline.index("ci/verify_release_signature.py")
        artifact_source = pipeline.index("app/build/outputs/apk/release/app-release.apk", verifier)
        self.assertIn("dist", pipeline[artifact_source:pipeline.index("fi", artifact_source)])
        self.assertIn("expire_in: 30 days", pipeline)
        self.assertIn("dist/WearTAK-Plugin-TAK-CAD-*.apk", pipeline)
        self.assertIn("dist/SHA256SUMS", pipeline)
        artifact_section = pipeline[pipeline.index("  artifacts:", pipeline.index("forge-verify:")):]
        self.assertNotIn("app/build/outputs/apk/debug", artifact_section)
        self.assertNotIn("app/build/outputs/apk/release", artifact_section)

    def test_signing_properties_are_ignored(self):
        self.assertIn(
            "signing.properties",
            Path(".gitignore").read_text(encoding="utf-8").splitlines(),
        )


if __name__ == "__main__":
    unittest.main()

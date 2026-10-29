import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from verify_release_signature import (
    EXPECTED_CERTIFICATE_SHA256,
    verify_release_signature,
)


class ReleaseSignatureTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        root = Path(self.temporary_directory.name)
        self.apk = root / "app-release.apk"
        self.apksigner = root / "apksigner"
        self.apk.touch()
        self.apksigner.touch()

    @patch("verify_release_signature.subprocess.run")
    def test_accepts_wear_tak_production_certificate(self, run):
        run.return_value = subprocess.CompletedProcess(
            [], 0, f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERTIFICATE_SHA256}\n", "",
        )
        self.assertEqual(
            verify_release_signature(self.apk, self.apksigner),
            EXPECTED_CERTIFICATE_SHA256,
        )
        run.assert_called_once_with(
            [str(self.apksigner), "verify", "--print-certs", str(self.apk)],
            capture_output=True, text=True, check=False, timeout=60,
        )

    @patch("verify_release_signature.subprocess.run")
    def test_rejects_different_certificate(self, run):
        run.return_value = subprocess.CompletedProcess(
            [], 0, "Signer #1 certificate SHA-256 digest: " + "a" * 64, "",
        )
        with self.assertRaisesRegex(RuntimeError, "Unexpected APK signer"):
            verify_release_signature(self.apk, self.apksigner)

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

    def test_signing_properties_are_ignored(self):
        self.assertIn(
            "signing.properties",
            Path(".gitignore").read_text(encoding="utf-8").splitlines(),
        )


if __name__ == "__main__":
    unittest.main()

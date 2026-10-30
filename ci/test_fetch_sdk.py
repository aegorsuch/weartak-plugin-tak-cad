from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from fetch_sdk import DEFAULT_SDK_REVISION, SDK_REPOSITORY, checkout_sdk, git_environment, main, validate_revision


class SdkProvisioningTest(unittest.TestCase):
    def test_requires_full_immutable_revision(self):
        self.assertEqual(SDK_REPOSITORY, "https://github.com/aegorsuch/weartak-plugin-sdk.git")
        self.assertEqual(DEFAULT_SDK_REVISION, "e2ee52402cf5738e86e43c3f6f1ce1594bd6e5ec")
        validate_revision(DEFAULT_SDK_REVISION)
        validate_revision("a" * 40)
        for revision in ["", "main", "abc123", "a" * 39, "g" * 40, "-bad-option"]:
            with self.assertRaises(ValueError):
                validate_revision(revision)

    def test_auth_is_transient_and_redirects_disabled(self):
        environment = git_environment("synthetic-token")
        self.assertEqual(environment["GIT_TERMINAL_PROMPT"], "0")
        self.assertEqual(environment["GIT_CONFIG_VALUE_0"], "false")
        self.assertTrue(environment["GIT_CONFIG_VALUE_1"].startswith("Authorization: Basic "))
        self.assertNotIn("synthetic-token", environment["GIT_CONFIG_VALUE_1"])
        self.assertEqual(git_environment("")["GIT_CONFIG_COUNT"], "1")
        with self.assertRaises(ValueError):
            git_environment("unsafe\nheader")

    def test_checkout_pins_commit_without_persisting_credential(self):
        revision = "a" * 40
        commands = []

        def fake_run(command, **kwargs):
            commands.append(command)
            output = revision if command[-2:] == ["rev-parse", "HEAD"] else ""
            self.assertNotIn("synthetic-token", " ".join(command))
            return subprocess.CompletedProcess(command, 0, output, "")

        with tempfile.TemporaryDirectory() as root, patch("fetch_sdk.subprocess.run", side_effect=fake_run):
            directory = checkout_sdk(Path(root) / "sdk", revision, "synthetic-token")
            self.assertTrue(directory.is_dir())
        self.assertTrue(any(command[-3:] == ["add", "origin", SDK_REPOSITORY] for command in commands))
        self.assertTrue(any(command[-2:] == ["origin", revision] for command in commands))

    def test_remote_errors_are_explicit_and_sanitized(self):
        with tempfile.TemporaryDirectory() as root, patch("fetch_sdk.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, "", "sensitive remote body")
            with self.assertRaisesRegex(RuntimeError, "verify published commit") as error:
                checkout_sdk(Path(root) / "sdk", "a" * 40, "")
            self.assertNotIn("sensitive", str(error.exception))

    def test_revision_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as root, patch("fetch_sdk.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, "b" * 40, "")
            with self.assertRaisesRegex(RuntimeError, "revision mismatch"):
                checkout_sdk(Path(root) / "sdk", "a" * 40, "")

    def test_forge_checkout_does_not_require_github_environment(self):
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, {}, clear=True), \
                patch("fetch_sdk.checkout_sdk") as checkout:
            directory = Path(root) / "sdk"
            checkout.return_value = directory
            main(["--directory", str(directory), "--checkout-only"])
            checkout.assert_called_once_with(directory, DEFAULT_SDK_REVISION, "")

    def test_github_defaults_still_export_sdk_paths(self):
        with tempfile.TemporaryDirectory() as root:
            environment_file = Path(root) / "github-env"
            with patch.dict(os.environ, {
                "RUNNER_TEMP": root, "GITHUB_ENV": str(environment_file),
                "WEARTAK_SDK_REVISION": "b" * 40, "WEARTAK_SDK_TOKEN": "synthetic-token",
            }, clear=True), patch("fetch_sdk.checkout_sdk") as checkout:
                directory = Path(root) / "weartak-plugin-sdk"
                checkout.return_value = directory
                main([])
                checkout.assert_called_once_with(directory, "b" * 40, "synthetic-token")
                self.assertEqual(environment_file.read_text().splitlines(), [
                    f"WEARTAK_SDK_SOURCE={directory}",
                    f"WEARTAK_PLUGIN_SDK_AAR={directory / 'build/outputs/aar/weartak-plugin-sdk-debug.aar'}",
                ])


if __name__ == "__main__":
    unittest.main()

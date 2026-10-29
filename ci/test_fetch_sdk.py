from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from fetch_sdk import DEFAULT_SDK_REVISION, SDK_REPOSITORY, checkout_sdk, git_environment, validate_revision


class SdkProvisioningTest(unittest.TestCase):
    def test_requires_full_immutable_revision(self):
        self.assertEqual(SDK_REPOSITORY, "https://github.com/aegorsuch/weartak-plugin-sdk.git")
        self.assertEqual(DEFAULT_SDK_REVISION, "a4f784338efb1d8eb048591a4a3f57b3f7e8ffa3")
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


if __name__ == "__main__":
    unittest.main()

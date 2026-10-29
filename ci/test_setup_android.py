import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from setup_android import ARCHIVE_SHA256, ARCHIVE_URL, install_android


class AndroidSetupTest(unittest.TestCase):
    def setUp(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("cmdline-tools/bin/sdkmanager", "#!/bin/sh\nexit 0\n")
        self.archive = archive.getvalue()

    def test_pins_official_archive_and_checksum(self):
        self.assertEqual(ARCHIVE_URL, "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip")
        self.assertEqual(ARCHIVE_SHA256, "2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258")

    def test_checks_archive_before_installing_exact_platform(self):
        with tempfile.TemporaryDirectory() as root, \
                patch("setup_android.urllib.request.urlopen", return_value=io.BytesIO(self.archive)) as download, \
                patch("setup_android.ARCHIVE_SHA256", hashlib.sha256(self.archive).hexdigest()), \
                patch("setup_android.subprocess.run") as run:
            directory = Path(root) / "sdk"
            install_android(directory)
            download.assert_called_once_with(ARCHIVE_URL, timeout=120)
            sdkmanager = directory / "cmdline-tools/12.0/bin/sdkmanager"
            self.assertTrue(sdkmanager.is_file())
            self.assertEqual(run.call_count, 2)
            self.assertEqual(run.call_args_list[0].args[0], [
                str(sdkmanager), f"--sdk_root={directory}", "--licenses",
            ])
            self.assertEqual(run.call_args.args[0], [
                str(sdkmanager), f"--sdk_root={directory}",
                "platforms;android-34", "build-tools;34.0.0", "platform-tools",
            ])
            self.assertTrue(all(call.kwargs["check"] for call in run.call_args_list))

    def test_checksum_mismatch_never_extracts_or_executes(self):
        with tempfile.TemporaryDirectory() as root, \
                patch("setup_android.urllib.request.urlopen", return_value=io.BytesIO(self.archive)), \
                patch("setup_android.zipfile.ZipFile") as extract, \
                patch("setup_android.subprocess.run") as run:
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                install_android(Path(root) / "sdk")
            extract.assert_not_called()
            run.assert_not_called()

    def test_license_failure_stops_install(self):
        with tempfile.TemporaryDirectory() as root, \
                patch("setup_android.urllib.request.urlopen", return_value=io.BytesIO(self.archive)), \
                patch("setup_android.ARCHIVE_SHA256", hashlib.sha256(self.archive).hexdigest()), \
                patch("setup_android.subprocess.run", side_effect=subprocess.CalledProcessError(1, "sdkmanager")) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                install_android(Path(root) / "sdk")
            self.assertEqual(run.call_count, 1)

    def test_existing_tools_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as root, \
                patch("setup_android.urllib.request.urlopen") as download:
            directory = Path(root) / "sdk"
            (directory / "cmdline-tools/12.0").mkdir(parents=True)
            with self.assertRaises(FileExistsError):
                install_android(directory)
            download.assert_not_called()


if __name__ == "__main__":
    unittest.main()

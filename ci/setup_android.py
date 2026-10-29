"""Install checksum-pinned Linux Android tools into an isolated CI SDK."""

import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

ARCHIVE_URL = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
ARCHIVE_SHA256 = "2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"


def install_android(directory):
    tools = directory / "cmdline-tools"
    destination = tools / "12.0"
    if destination.exists() or (tools / "cmdline-tools").exists():
        raise FileExistsError("Use a fresh isolated Android SDK directory.")
    with tempfile.TemporaryDirectory() as temporary:
        archive = Path(temporary) / "tools.zip"
        digest = hashlib.sha256()
        with urllib.request.urlopen(ARCHIVE_URL, timeout=120) as response, archive.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != ARCHIVE_SHA256:
            raise ValueError("Android command-line tools SHA-256 mismatch; refusing extraction.")
        with zipfile.ZipFile(archive) as source:
            source.extractall(tools)
    (tools / "cmdline-tools").rename(destination)
    sdkmanager = destination / "bin" / "sdkmanager"
    sdkmanager.chmod(0o755)
    command = [str(sdkmanager), f"--sdk_root={directory}"]
    subprocess.run(
        [*command, "--licenses"], input="y\n" * 100, text=True,
        stdout=subprocess.DEVNULL, check=True, timeout=300,
    )
    subprocess.run(
        [*command, "platforms;android-34", "build-tools;34.0.0", "platform-tools"],
        check=True, timeout=900,
    )


if __name__ == "__main__":
    install_android(Path(os.environ["ANDROID_HOME"]).resolve())

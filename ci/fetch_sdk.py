"""Check out reviewed WearTAK SDK source at an immutable revision for CI builds."""

import base64
import os
from pathlib import Path
import re
import subprocess
import sys

SDK_REPOSITORY = "https://github.com/aegorsuch/weartak-plugin-sdk.git"
DEFAULT_SDK_REVISION = "a4f784338efb1d8eb048591a4a3f57b3f7e8ffa3"


def validate_revision(revision):
    if not re.fullmatch(r"[a-fA-F0-9]{40}", revision):
        raise ValueError("Set WEARTAK_SDK_REVISION to a published, reviewed 40-character SDK commit containing ACTION.")


def git_environment(token):
    if any(ord(char) < 32 or ord(char) == 127 for char in token):
        raise ValueError("WEARTAK_SDK_TOKEN must be a single-line read-only GitHub token.")
    environment = os.environ.copy()
    environment["GIT_TERMINAL_PROMPT"] = "0"
    # Refuse redirects rather than forwarding artifact/source credentials elsewhere.
    environment["GIT_CONFIG_COUNT"] = "1"
    environment["GIT_CONFIG_KEY_0"] = "http.followRedirects"
    environment["GIT_CONFIG_VALUE_0"] = "false"
    if token:
        encoded = base64.b64encode(f"x-access-token:{token}".encode()).decode()
        environment["GIT_CONFIG_COUNT"] = "2"
        environment["GIT_CONFIG_KEY_1"] = "http.extraHeader"
        environment["GIT_CONFIG_VALUE_1"] = f"Authorization: Basic {encoded}"
    return environment


def checkout_sdk(directory, revision, token):
    validate_revision(revision)
    environment = git_environment(token)
    directory.mkdir(parents=True, exist_ok=False)

    def git(*args):
        # Never echo credential-bearing env or raw remote error bodies.
        completed = subprocess.run(
            ["git", "-C", str(directory), *args],
            env=environment, capture_output=True, text=True, timeout=120, check=False,
        )
        if completed.returncode:
            raise RuntimeError("SDK source checkout failed; verify published commit, network, and repository read access.")
        return completed.stdout.strip()

    git("init", "--quiet")
    git("remote", "add", "origin", SDK_REPOSITORY)
    git("fetch", "--quiet", "--depth=1", "origin", revision)
    git("checkout", "--quiet", "--detach", "FETCH_HEAD")
    if git("rev-parse", "HEAD").lower() != revision.lower():
        raise RuntimeError("SDK checkout revision mismatch.")
    return directory


def main():
    revision = os.environ.get("WEARTAK_SDK_REVISION", "").strip() or DEFAULT_SDK_REVISION
    token = os.environ.get("WEARTAK_SDK_TOKEN", "")
    directory = checkout_sdk(Path(os.environ["RUNNER_TEMP"]) / "weartak-plugin-sdk", revision, token)
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as environment:
        environment.write(f"WEARTAK_SDK_SOURCE={directory}\n")
        environment.write(f"WEARTAK_PLUGIN_SDK_AAR={directory / 'build/outputs/aar/weartak-plugin-sdk-debug.aar'}\n")
    print(f"Verified SDK source revision {revision}; build source before compiling plugin.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(f"SDK provisioning failed: {error}", file=sys.stderr)
        sys.exit(1)

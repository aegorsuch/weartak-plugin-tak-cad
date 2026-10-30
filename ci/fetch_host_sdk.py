"""Build provisioning for the independently pinned public Host Services SDK."""

import argparse
import os
from pathlib import Path
import subprocess
import sys

from fetch_sdk import checkout_sdk

HOST_REPOSITORY = "https://github.com/aegorsuch/weartak-wearos.git"
DEFAULT_HOST_REVISION = "782d8a7e791dd70750d567e22115b739332c04ba"
MODULE = "weartak-plugin-host-services-sdk"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--checkout-only", action="store_true")
    args = parser.parse_args(argv)
    revision = os.environ.get("WEARTAK_HOST_SDK_REVISION", "").strip() or DEFAULT_HOST_REVISION
    if not revision:
        raise ValueError("Host Services SDK publication pin is required; no moving-branch or binary fallback.")
    if args.directory is None:
        if not os.environ.get("RUNNER_TEMP"):
            parser.error("Pass --directory or set RUNNER_TEMP.")
        args.directory = Path(os.environ["RUNNER_TEMP"]) / "weartak-host-sdk"
    if not args.checkout_only and not os.environ.get("GITHUB_ENV"):
        parser.error("Set GITHUB_ENV or pass --checkout-only.")
    directory = checkout_sdk(args.directory, revision, "", repository=HOST_REPOSITORY)
    if not args.checkout_only:
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as environment:
            environment.write(f"WEARTAK_HOST_SDK_SOURCE={directory}\n")
            environment.write(
                f"WEARTAK_HOST_SERVICES_SDK_AAR={directory / MODULE / 'build/outputs/aar' / (MODULE + '-release.aar')}\n"
            )
    print(f"Verified Host Services SDK source revision {revision}; build standalone module before compiling plugin.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(f"Host Services SDK provisioning failed: {error}", file=sys.stderr)
        sys.exit(1)

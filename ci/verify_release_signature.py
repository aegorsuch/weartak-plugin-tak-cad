"""Verify a release APK is signed by WearTAK's trusted production certificate."""

import hashlib
import re
import shutil
import subprocess
import sys
from pathlib import Path

EXPECTED_CERTIFICATE_SHA256 = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"
CERTIFICATE_DIGEST = re.compile(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})")
ARTIFACT_FILENAME = "TAK-CAD-production.apk"


def verify_release_signature(apk, apksigner, artifact_directory=None):
    apk = Path(apk)
    apksigner = Path(apksigner)
    if not apk.is_file():
        raise FileNotFoundError(f"Release APK not found: {apk}")
    if not apksigner.is_file():
        raise FileNotFoundError(f"Android apksigner not found: {apksigner}")

    result = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk)],
        capture_output=True,
        text=True,
        check=False,
        timeout=60,
    )
    if result.returncode:
        raise RuntimeError("APK signature verification failed.")

    certificates = {
        digest.lower() for digest in CERTIFICATE_DIGEST.findall(result.stdout)
    }
    if certificates != {EXPECTED_CERTIFICATE_SHA256}:
        actual = ", ".join(sorted(certificates)) or "none"
        raise RuntimeError(
            f"Unexpected APK signer certificate(s): {actual}; "
            f"expected {EXPECTED_CERTIFICATE_SHA256}."
        )
    if artifact_directory is not None:
        artifact_directory = Path(artifact_directory)
        artifact_directory.mkdir(parents=True, exist_ok=False)
        artifact = artifact_directory / ARTIFACT_FILENAME
        shutil.copyfile(apk, artifact)
        digest = hashlib.sha256()
        with artifact.open("rb") as signed_apk:
            for chunk in iter(lambda: signed_apk.read(1024 * 1024), b""):
                digest.update(chunk)
        (artifact_directory / "SHA256SUMS").write_text(
            f"{digest.hexdigest()}  {ARTIFACT_FILENAME}\n",
            encoding="ascii",
        )
    return EXPECTED_CERTIFICATE_SHA256


def main(argv=None):
    if argv is None:
        argv = sys.argv[1:]
    if len(argv) not in (2, 3):
        raise SystemExit(
            "Usage: verify_release_signature.py <release.apk> <apksigner> [artifact-directory]"
        )
    artifact_directory = argv[2] if len(argv) == 3 else None
    fingerprint = verify_release_signature(argv[0], argv[1], artifact_directory)
    print(f"Release APK signature verified: {fingerprint}")


if __name__ == "__main__":
    try:
        main()
    except (FileNotFoundError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"Release signature verification failed: {error}", file=sys.stderr)
        sys.exit(1)

"""Verify a release APK is signed by WearTAK's trusted production certificate."""

import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

EXPECTED_CERTIFICATE_SHA256 = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"
CERTIFICATE_DIGEST = re.compile(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})")
VERSION_NAME = re.compile(r"versionName='([^']+)'")
PACKAGE_NAME = re.compile(r"package: name='([^']+)'")
PLUGIN_NAME = "TAK-CAD"
APPLICATION_ID = "com.weartak.plugin.takcad"


def release_asset_filename(plugin_name, version_name, pipeline_iid):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*", plugin_name):
        raise ValueError(f"Invalid plugin name: {plugin_name}")
    pipeline_iid = str(pipeline_iid)
    if not re.fullmatch(r"[1-9][0-9]*", pipeline_iid):
        raise ValueError("CI_PIPELINE_IID must be a positive integer.")

    if not isinstance(version_name, str):
        raise ValueError(f"Invalid APK version name: {version_name}")
    version = version_name.split("+", 1)[0]
    pipeline_suffix = re.fullmatch(r"(.+)-p([1-9][0-9]*)", version)
    if pipeline_suffix:
        if pipeline_suffix.group(2) != pipeline_iid:
            raise ValueError(
                f"APK pipeline IID {pipeline_suffix.group(2)} does not match "
                f"CI_PIPELINE_IID {pipeline_iid}."
            )
        version = pipeline_suffix.group(1)
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]*", version):
        raise ValueError(f"Invalid APK version name: {version_name}")
    return f"WearTAK-Plugin-{plugin_name}-{version}-p{pipeline_iid}.apk"


def verify_release_signature(apk, apksigner, artifact_directory=None, pipeline_iid=None):
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
        if pipeline_iid is None:
            pipeline_iid = os.environ.get("CI_PIPELINE_IID")
        filename = release_asset_filename(
            PLUGIN_NAME,
            _read_version_name(apk, apksigner.with_name("aapt")),
            pipeline_iid,
        )
        artifact_directory = Path(artifact_directory)
        artifact_directory.mkdir(parents=True, exist_ok=False)
        artifact = artifact_directory / filename
        shutil.copyfile(apk, artifact)
        digest = hashlib.sha256()
        with artifact.open("rb") as signed_apk:
            for chunk in iter(lambda: signed_apk.read(1024 * 1024), b""):
                digest.update(chunk)
        (artifact_directory / "SHA256SUMS").write_text(
            f"{digest.hexdigest()}  {filename}\n",
            encoding="ascii",
        )
    return EXPECTED_CERTIFICATE_SHA256


def _read_version_name(apk, aapt):
    if not aapt.is_file():
        raise FileNotFoundError(f"Android aapt not found: {aapt}")
    result = subprocess.run(
        [str(aapt), "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
        check=False,
        timeout=60,
    )
    if result.returncode:
        raise RuntimeError("Could not read APK version metadata.")
    package = PACKAGE_NAME.search(result.stdout)
    if not package or package.group(1) != APPLICATION_ID:
        raise RuntimeError("APK package name does not match TAK-CAD.")
    match = VERSION_NAME.search(result.stdout)
    if not match:
        raise RuntimeError("APK has no readable versionName.")
    return match.group(1)


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
    except (FileNotFoundError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Release signature verification failed: {error}", file=sys.stderr)
        sys.exit(1)

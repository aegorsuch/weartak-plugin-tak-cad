import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fetch_host_sdk import DEFAULT_HOST_REVISION, HOST_REPOSITORY, MODULE, main


class HostSdkProvisioningTest(unittest.TestCase):
    def test_default_is_reviewed_full_source_pin(self):
        self.assertEqual(DEFAULT_HOST_REVISION, "782d8a7e791dd70750d567e22115b739332c04ba")
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, {}, clear=True), \
                patch("fetch_host_sdk.checkout_sdk") as checkout:
            directory = Path(root) / "host"
            checkout.return_value = directory
            main(["--directory", str(directory), "--checkout-only"])
            checkout.assert_called_once_with(directory, DEFAULT_HOST_REVISION, "", repository=HOST_REPOSITORY)

    def test_uses_only_public_personal_mirror_at_immutable_pin_without_credentials(self):
        with tempfile.TemporaryDirectory() as root, \
                patch.dict(os.environ, {"WEARTAK_HOST_SDK_REVISION": "a" * 40}, clear=True), \
                patch("fetch_host_sdk.checkout_sdk") as checkout:
            directory = Path(root) / "host"
            checkout.return_value = directory
            main(["--directory", str(directory), "--checkout-only"])
            checkout.assert_called_once_with(directory, "a" * 40, "", repository=HOST_REPOSITORY)
            self.assertEqual(HOST_REPOSITORY, "https://github.com/aegorsuch/weartak-wearos.git")

    def test_exports_separate_source_and_aar_paths(self):
        with tempfile.TemporaryDirectory() as root:
            environment = Path(root) / "github-env"
            with patch.dict(os.environ, {
                "RUNNER_TEMP": root, "GITHUB_ENV": str(environment), "WEARTAK_HOST_SDK_REVISION": "b" * 40,
            }, clear=True), patch("fetch_host_sdk.checkout_sdk") as checkout:
                directory = Path(root) / "weartak-host-sdk"
                checkout.return_value = directory
                main([])
                self.assertEqual(environment.read_text().splitlines(), [
                    f"WEARTAK_HOST_SDK_SOURCE={directory}",
                    f"WEARTAK_HOST_SERVICES_SDK_AAR={directory / MODULE / 'build/outputs/aar' / (MODULE + '-release.aar')}",
                ])

    def test_unpublished_pin_fails_before_network(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch("fetch_host_sdk.DEFAULT_HOST_REVISION", ""), \
                patch("fetch_host_sdk.checkout_sdk") as checkout:
            with self.assertRaisesRegex(ValueError, "publication pin"):
                main([])
            checkout.assert_not_called()

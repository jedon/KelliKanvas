import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class QnapDeploymentTest(unittest.TestCase):
    def test_compose_requires_designated_bind_address_and_pinned_digest(self):
        compose = (ROOT / "deploy/qnap/compose.yaml").read_text(encoding="utf-8")
        self.assertIn("${KELLIKANVAS_BIND_ADDRESS:?", compose)
        self.assertRegex(compose, r"image: nginx:[^@\s]+@sha256:[0-9a-f]{64}")
        nginx = (ROOT / "deploy/qnap/nginx.conf").read_text(encoding="utf-8")
        self.assertIn("if ($request_method !~ ^(GET|HEAD)$)", nginx)
        self.assertIn("X-Content-Type-Options nosniff", nginx)

    @unittest.skipUnless(shutil.which("powershell") or shutil.which("pwsh"), "PowerShell unavailable")
    def test_publisher_requires_signature_and_preserves_old_manifest_on_failure(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            destination = root / "destination"
            bundle.mkdir()
            destination.mkdir()
            apk = b"apk"
            digest = hashlib.sha256(apk).hexdigest()
            (bundle / "kellikanvas-2.apk").write_bytes(apk)
            (bundle / "kellikanvas-2.apk.sha256").write_text(
                f"{digest}  kellikanvas-2.apk\n", encoding="ascii"
            )
            manifest = {
                "apkUrl": "http://darklingnas:8088/kellikanvas-2.apk",
                "checksumUrl": "http://darklingnas:8088/kellikanvas-2.apk.sha256",
                "packageName": "com.jedon.kellikanvas",
                "schema": 1,
                "sequence": 2,
                "sha256": digest,
                "signerSha256": "A" * 64,
                "sizeBytes": len(apk),
                "versionCode": 2,
                "versionName": "2",
            }
            (bundle / "manifest.json").write_text(
                json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            (destination / "manifest.json").write_text("old\n", encoding="ascii")
            result = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(ROOT / "tools/publish-to-qnap.ps1"),
                    "-BundlePath",
                    str(bundle),
                    "-Destination",
                    str(destination),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("old\n", (destination / "manifest.json").read_text(encoding="ascii"))
            (bundle / "manifest.json.sig").write_bytes(b"signed-metadata")
            success = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(ROOT / "tools/publish-to-qnap.ps1"),
                    "-BundlePath",
                    str(bundle),
                    "-Destination",
                    str(destination),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertEqual(0, success.returncode, success.stdout)
            self.assertEqual(
                (bundle / "manifest.json").read_bytes(),
                (destination / "manifest.json").read_bytes(),
            )
            self.assertEqual(
                b"signed-metadata", (destination / "manifest.json.sig").read_bytes()
            )


if __name__ == "__main__":
    unittest.main()

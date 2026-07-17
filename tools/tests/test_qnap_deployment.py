import hashlib
import base64
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
        self.assertIn("location = /update-envelope.json", nginx)
        self.assertNotIn("location = /manifest.json", nginx)
        readme = (ROOT / "deploy/qnap/README.md").read_text(encoding="utf-8")
        self.assertIn("API 28, 30, 34, and 36", readme)
        self.assertNotIn("API 26", readme)

    @unittest.skipUnless(
        (shutil.which("powershell") or shutil.which("pwsh")) and shutil.which("openssl"),
        "PowerShell or OpenSSL unavailable",
    )
    def test_publisher_verifies_envelope_monotonicity_and_preserves_control_on_failure(self):
        shell = shutil.which("pwsh") or shutil.which("powershell")
        openssl = shutil.which("openssl")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            destination = root / "destination"
            bundle.mkdir()
            destination.mkdir()
            private_key = root / "metadata-private.pem"
            public_key = root / "metadata-public.pem"
            subprocess.run(
                [openssl, "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", private_key],
                check=True,
            )
            subprocess.run(
                [openssl, "pkey", "-in", private_key, "-pubout", "-out", public_key],
                check=True,
            )
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
            payload = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode()

            def write_envelope(path, payload_bytes, key_id="release-v1"):
                payload_path = root / "payload.tmp"
                signature_path = root / "signature.tmp"
                payload_path.write_bytes(payload_bytes)
                subprocess.run(
                    [
                        openssl,
                        "dgst",
                        "-sha256",
                        "-sign",
                        private_key,
                        "-out",
                        signature_path,
                        payload_path,
                    ],
                    check=True,
                )
                envelope = {
                    "envelopeSchema": 1,
                    "keyId": key_id,
                    "payload": base64.b64encode(payload_bytes).decode("ascii"),
                    "signature": base64.b64encode(signature_path.read_bytes()).decode("ascii"),
                }
                path.write_bytes(
                    (json.dumps(envelope, sort_keys=True, separators=(",", ":")) + "\n").encode(
                        "utf-8"
                    )
                )

            write_envelope(bundle / "update-envelope.json", payload)
            (bundle / "kellikanvas-2.apk.sha256").write_text("bad\n", encoding="ascii")
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
                    "-MetadataPublicKeyFile",
                    str(public_key),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((destination / "update-envelope.json").exists())
            (bundle / "kellikanvas-2.apk.sha256").write_text(
                f"{digest}  kellikanvas-2.apk\n", encoding="ascii"
            )
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
                    "-MetadataPublicKeyFile",
                    str(public_key),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertEqual(0, success.returncode, success.stdout)
            self.assertEqual(
                (bundle / "update-envelope.json").read_bytes(),
                (destination / "update-envelope.json").read_bytes(),
            )
            successful_control = (destination / "update-envelope.json").read_bytes()
            older_manifest = dict(manifest)
            older_manifest["sequence"] = 1
            older_payload = (
                json.dumps(older_manifest, sort_keys=True, separators=(",", ":")) + "\n"
            ).encode()
            write_envelope(bundle / "update-envelope.json", older_payload)
            rollback = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(ROOT / "tools/publish-to-qnap.ps1"),
                    "-BundlePath",
                    str(bundle),
                    "-Destination",
                    str(destination),
                    "-MetadataPublicKeyFile",
                    str(public_key),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(0, rollback.returncode)
            self.assertIn("not monotonic", rollback.stdout)
            self.assertEqual(successful_control, (destination / "update-envelope.json").read_bytes())
            (bundle / "update-envelope.json").write_bytes(successful_control)
            tampered = json.loads((bundle / "update-envelope.json").read_text(encoding="utf-8"))
            tampered["signature"] = base64.b64encode(b"bad-der").decode("ascii")
            (bundle / "update-envelope.json").write_bytes(
                (json.dumps(tampered, sort_keys=True, separators=(",", ":")) + "\n").encode(
                    "utf-8"
                )
            )
            rejected = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(ROOT / "tools/publish-to-qnap.ps1"),
                    "-BundlePath",
                    str(bundle),
                    "-Destination",
                    str(destination),
                    "-MetadataPublicKeyFile",
                    str(public_key),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(0, rejected.returncode)
            self.assertEqual(
                successful_control,
                (destination / "update-envelope.json").read_bytes(),
            )

            same_version = dict(manifest)
            same_version["sequence"] = 3
            same_version_payload = (
                json.dumps(same_version, sort_keys=True, separators=(",", ":")) + "\n"
            ).encode()
            write_envelope(bundle / "update-envelope.json", same_version_payload)
            same_version_result = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(ROOT / "tools/publish-to-qnap.ps1"),
                    "-BundlePath",
                    str(bundle),
                    "-Destination",
                    str(destination),
                    "-MetadataPublicKeyFile",
                    str(public_key),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(0, same_version_result.returncode)
            self.assertEqual(successful_control, (destination / "update-envelope.json").read_bytes())

            (bundle / "update-envelope.json").write_bytes(successful_control)
            original_checksum = (destination / "kellikanvas-2.apk.sha256").read_bytes()
            (bundle / "kellikanvas-2.apk.sha256").write_bytes(original_checksum + b"# changed\n")
            nonidentical_retry = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(ROOT / "tools/publish-to-qnap.ps1"),
                    "-BundlePath",
                    str(bundle),
                    "-Destination",
                    str(destination),
                    "-MetadataPublicKeyFile",
                    str(public_key),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(0, nonidentical_retry.returncode)
            self.assertEqual(
                original_checksum,
                (destination / "kellikanvas-2.apk.sha256").read_bytes(),
            )

            apk3 = b"apk-version-3"
            digest3 = hashlib.sha256(apk3).hexdigest()
            (bundle / "kellikanvas-3.apk").write_bytes(apk3)
            (bundle / "kellikanvas-3.apk.sha256").write_text(
                f"{digest3}  kellikanvas-3.apk\n", encoding="ascii"
            )
            manifest3 = dict(manifest)
            manifest3.update(
                {
                    "apkUrl": "http://darklingnas:8088/kellikanvas-3.apk",
                    "checksumUrl": "http://darklingnas:8088/kellikanvas-3.apk.sha256",
                    "sequence": 4,
                    "sha256": digest3,
                    "sizeBytes": len(apk3),
                    "versionCode": 3,
                    "versionName": "3",
                }
            )
            payload3 = (
                json.dumps(manifest3, sort_keys=True, separators=(",", ":")) + "\n"
            ).encode()
            write_envelope(bundle / "update-envelope.json", payload3)
            interrupted_environment = os.environ.copy()
            interrupted_environment["KELLIKANVAS_TEST_FAIL_CONTROL_COMMIT"] = "1"
            interrupted = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(ROOT / "tools/publish-to-qnap.ps1"),
                    "-BundlePath",
                    str(bundle),
                    "-Destination",
                    str(destination),
                    "-MetadataPublicKeyFile",
                    str(public_key),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                env=interrupted_environment,
            )
            self.assertNotEqual(0, interrupted.returncode)
            self.assertEqual(successful_control, (destination / "update-envelope.json").read_bytes())


if __name__ == "__main__":
    unittest.main()

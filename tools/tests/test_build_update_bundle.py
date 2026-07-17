import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import build_update_bundle


class BuildUpdateBundleTest(unittest.TestCase):
    def test_verifies_tools_and_writes_deterministic_versioned_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "input.apk"
            apk.write_bytes(b"signed release")
            metadata_key = root / "metadata-key.pem"
            metadata_key.write_text("secret supplied by test", encoding="ascii")
            dist = root / "dist"
            signer = "AA" * 32

            def tool(command, **_kwargs):
                if command[0] == "openssl":
                    return completed_bytes(b"metadata-signature")
                if command[0] == "apksigner":
                    return completed(f"Signer #1 certificate SHA-256 digest: {signer}\n")
                field = command[2]
                values = {
                    "application-id": "com.jedon.kellikanvas\n",
                    "version-code": "42\n",
                    "version-name": "1.2.3\n",
                }
                return completed(values[field])

            with patch("tools.build_update_bundle.subprocess.run", side_effect=tool) as run:
                manifest = build_update_bundle.build_bundle(
                    apk,
                    dist,
                    "http://darklingnas:8088",
                    "apksigner",
                    "apkanalyzer",
                    metadata_key,
                    sequence=9,
                )

            copied = dist / "kellikanvas-42.apk"
            checksum = dist / "kellikanvas-42.apk.sha256"
            self.assertEqual(apk.read_bytes(), copied.read_bytes())
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            self.assertEqual(f"{digest}  kellikanvas-42.apk\n", checksum.read_text(encoding="ascii"))
            self.assertEqual(signer, manifest["signerSha256"])
            self.assertEqual(9, manifest["sequence"])
            self.assertEqual(b"metadata-signature", (dist / "manifest.json.sig").read_bytes())
            self.assertEqual(
                json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                (dist / "manifest.json").read_text(encoding="utf-8"),
            )
            self.assertEqual("apksigner", run.call_args_list[0].args[0][0])
            self.assertEqual(5, run.call_count)

    def test_rejects_wrong_package_or_missing_signer(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "input.apk"
            apk.write_bytes(b"apk")
            dist = Path(temporary) / "dist"

            with patch(
                "tools.build_update_bundle.inspect_apk",
                return_value=build_update_bundle.ApkMetadata("other", 2, "2", "AA"),
            ):
                with self.assertRaises(build_update_bundle.BundleError):
                    build_update_bundle.build_bundle(apk, dist, metadata_private_key=Path("key"), sequence=1)

            with patch(
                "tools.build_update_bundle.inspect_apk",
                return_value=build_update_bundle.ApkMetadata("com.jedon.kellikanvas", 2, "2", ""),
            ):
                with self.assertRaises(build_update_bundle.BundleError):
                    build_update_bundle.build_bundle(apk, dist, metadata_private_key=Path("key"), sequence=1)

    def test_requires_offline_metadata_key_and_positive_sequence(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "input.apk"
            apk.write_bytes(b"apk")
            with self.assertRaises(build_update_bundle.BundleError):
                build_update_bundle.build_bundle(apk, Path(temporary) / "dist")
            with self.assertRaises(build_update_bundle.BundleError):
                build_update_bundle.build_bundle(
                    apk, Path(temporary) / "dist", metadata_private_key=Path("key"), sequence=0
                )


def completed(stdout):
    class Result:
        pass

    result = Result()
    result.stdout = stdout
    return result


def completed_bytes(stdout):
    class Result:
        pass

    result = Result()
    result.stdout = stdout
    return result


if __name__ == "__main__":
    unittest.main()

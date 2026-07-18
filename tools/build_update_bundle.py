#!/usr/bin/env python3
"""Build a deterministic, independently verifiable private update bundle."""

import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

PACKAGE_NAME = "com.jedon.kellikanvas"
ORIGIN = "http://darklingnas:8088"
APK_MAX_BYTES = 500 * 1024 * 1024


class BundleError(RuntimeError):
    pass


@dataclass(frozen=True)
class ApkMetadata:
    package_name: str
    version_code: int
    version_name: str
    signer_sha256: str


def _run(command):
    try:
        return subprocess.run(
            command, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise BundleError(f"tool failed: {command[0]}") from error


def _sign_metadata(data, private_key, openssl="openssl"):
    try:
        return subprocess.run(
            [openssl, "dgst", "-sha256", "-sign", str(private_key)],
            input=data,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise BundleError("metadata signing failed") from error


def inspect_apk(apk, apksigner="apksigner", apkanalyzer="apkanalyzer"):
    signer_output = _run([apksigner, "verify", "--print-certs", str(apk)])
    marker = "certificate SHA-256 digest:"
    signer_lines = [line for line in signer_output.splitlines() if marker in line]
    if len(signer_lines) != 1:
        raise BundleError("APK must expose exactly one signer SHA-256 digest")
    signer = signer_lines[0].split(marker, 1)[1].strip().replace(":", "").upper()
    if len(signer) != 64 or any(character not in "0123456789ABCDEF" for character in signer):
        raise BundleError("invalid APK signer digest")

    def manifest_field(field):
        return _run([apkanalyzer, "manifest", field, str(apk)])

    try:
        version_code = int(manifest_field("version-code"))
    except ValueError as error:
        raise BundleError("invalid APK version code") from error
    return ApkMetadata(
        package_name=manifest_field("application-id"),
        version_code=version_code,
        version_name=manifest_field("version-name"),
        signer_sha256=signer,
    )


def _atomic_write(path, data):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


ALLOWED_ORIGINS = {
    "http://darklingnas:8088",
    "http://192.168.68.81:8088",
}


def prepare_bundle(
    apk,
    dist,
    origin=ORIGIN,
    apksigner="apksigner",
    apkanalyzer="apkanalyzer",
    sequence=None,
):
    apk = Path(apk)
    dist = Path(dist)
    if sequence is None or sequence <= 0:
        raise BundleError("positive authenticated release sequence is required")
    parsed = urlparse(origin)
    normalized = f"{parsed.scheme}://{parsed.hostname}:{parsed.port}"
    if (
        origin.rstrip("/") not in ALLOWED_ORIGINS
        or normalized not in ALLOWED_ORIGINS
        or (parsed.path, parsed.query, parsed.fragment) != ("", "", "")
    ):
        raise BundleError(
            "origin must be http://darklingnas:8088 or http://192.168.68.81:8088"
        )
    size = apk.stat().st_size
    if size <= 0 or size > APK_MAX_BYTES:
        raise BundleError("APK size is outside limits")
    metadata = inspect_apk(apk, apksigner, apkanalyzer)
    if metadata.package_name != PACKAGE_NAME:
        raise BundleError("APK package is not KelliKanvas")
    if not metadata.signer_sha256:
        raise BundleError("APK signer is missing")

    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk_name = f"kellikanvas-{metadata.version_code}.apk"
    checksum_name = f"{apk_name}.sha256"
    manifest = {
        "apkUrl": f"{origin}/{apk_name}",
        "checksumUrl": f"{origin}/{checksum_name}",
        "packageName": PACKAGE_NAME,
        "schema": 1,
        "sequence": sequence,
        "sha256": digest,
        "signerSha256": metadata.signer_sha256,
        "sizeBytes": size,
        "versionCode": metadata.version_code,
        "versionName": metadata.version_name,
    }

    dist.mkdir(parents=True, exist_ok=True)
    temporary_apk = dist / f"{apk_name}.tmp"
    shutil.copyfile(apk, temporary_apk)
    if hashlib.sha256(temporary_apk.read_bytes()).hexdigest() != digest:
        temporary_apk.unlink(missing_ok=True)
        raise BundleError("copied APK hash mismatch")
    os.replace(temporary_apk, dist / apk_name)
    _atomic_write(dist / checksum_name, f"{digest}  {apk_name}\n".encode("ascii"))
    manifest_bytes = (
        json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n"
    ).encode("utf-8")
    _atomic_write(dist / "manifest.payload.json", manifest_bytes)
    return manifest


def sign_prepared_bundle(
    dist,
    metadata_private_key,
    key_id,
    openssl="openssl",
):
    dist = Path(dist)
    if metadata_private_key is None:
        raise BundleError("offline metadata private key is required")
    if (
        not key_id
        or len(key_id) > 64
        or not all(character.isalnum() or character in "._-" for character in key_id)
    ):
        raise BundleError("valid metadata key id is required")
    payload_path = dist / "manifest.payload.json"
    manifest_bytes = payload_path.read_bytes()
    signature = _sign_metadata(manifest_bytes, metadata_private_key, openssl)
    if not signature or len(signature) > 1024:
        raise BundleError("metadata signature is outside limits")
    envelope = {
        "envelopeSchema": 1,
        "keyId": key_id,
        "payload": base64.b64encode(manifest_bytes).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    envelope_bytes = (
        json.dumps(envelope, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n"
    ).encode("utf-8")
    _atomic_write(dist / "update-envelope.json", envelope_bytes)
    payload_path.unlink()


def build_bundle(
    apk,
    dist,
    origin=ORIGIN,
    apksigner="apksigner",
    apkanalyzer="apkanalyzer",
    metadata_private_key=None,
    sequence=None,
    key_id=None,
    openssl="openssl",
):
    manifest = prepare_bundle(apk, dist, origin, apksigner, apkanalyzer, sequence)
    sign_prepared_bundle(dist, metadata_private_key, key_id, openssl)
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    parser.add_argument("--origin", default=ORIGIN)
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--metadata-private-key", type=Path)
    parser.add_argument("--sequence", type=int, required=True)
    parser.add_argument("--key-id")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--openssl", default="openssl")
    arguments = parser.parse_args()
    if arguments.prepare_only:
        prepare_bundle(
            arguments.apk,
            arguments.dist,
            arguments.origin,
            arguments.apksigner,
            arguments.apkanalyzer,
            arguments.sequence,
        )
    else:
        build_bundle(
            arguments.apk,
            arguments.dist,
            arguments.origin,
            arguments.apksigner,
            arguments.apkanalyzer,
            arguments.metadata_private_key,
            arguments.sequence,
            arguments.key_id,
            arguments.openssl,
        )


if __name__ == "__main__":
    main()

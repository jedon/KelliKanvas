#!/usr/bin/env python3
"""Strictly verify one authenticated update control envelope."""

import argparse
import base64
import json
import subprocess
import tempfile
from pathlib import Path

MAX_METADATA_BYTES = 64 * 1024
OUTER_FIELDS = {"envelopeSchema", "keyId", "payload", "signature"}
PAYLOAD_FIELDS = {
    "apkUrl",
    "checksumUrl",
    "packageName",
    "schema",
    "sequence",
    "sha256",
    "signerSha256",
    "sizeBytes",
    "versionCode",
    "versionName",
}


class EnvelopeError(RuntimeError):
    pass


def _strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise EnvelopeError("duplicate JSON field")
        result[key] = value
    return result


def _canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n").encode(
        "utf-8"
    )


def verify_envelope(path, public_key, expected_key_id, openssl="openssl"):
    envelope_bytes = Path(path).read_bytes()
    if not envelope_bytes or len(envelope_bytes) > MAX_METADATA_BYTES:
        raise EnvelopeError("control envelope is outside limits")
    try:
        envelope = json.loads(envelope_bytes, object_pairs_hook=_strict_object)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise EnvelopeError("malformed control envelope") from error
    if (
        not isinstance(envelope, dict)
        or set(envelope) != OUTER_FIELDS
        or type(envelope["envelopeSchema"]) is not int
        or envelope["envelopeSchema"] != 1
        or not all(isinstance(envelope[name], str) for name in ("keyId", "payload", "signature"))
        or envelope["keyId"] != expected_key_id
        or _canonical(envelope) != envelope_bytes
    ):
        raise EnvelopeError("non-canonical or unsupported control envelope")
    try:
        payload = base64.b64decode(envelope["payload"], validate=True)
        signature = base64.b64decode(envelope["signature"], validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise EnvelopeError("malformed control envelope encoding") from error
    if not payload or len(payload) > MAX_METADATA_BYTES or not signature or len(signature) > 1024:
        raise EnvelopeError("authenticated envelope content is outside limits")
    try:
        manifest = json.loads(payload, object_pairs_hook=_strict_object)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise EnvelopeError("malformed authenticated payload") from error
    if (
        not isinstance(manifest, dict)
        or set(manifest) != PAYLOAD_FIELDS
        or _canonical(manifest) != payload
        or type(manifest["schema"]) is not int
        or type(manifest["sequence"]) is not int
        or type(manifest["versionCode"]) is not int
        or type(manifest["sizeBytes"]) is not int
    ):
        raise EnvelopeError("non-canonical or unsupported authenticated payload")
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        payload_path = root / "payload"
        signature_path = root / "signature"
        payload_path.write_bytes(payload)
        signature_path.write_bytes(signature)
        try:
            subprocess.run(
                [
                    openssl,
                    "dgst",
                    "-sha256",
                    "-verify",
                    str(public_key),
                    "-signature",
                    str(signature_path),
                    str(payload_path),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            raise EnvelopeError("control envelope authentication failed") from error
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--envelope", type=Path, required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--openssl", default="openssl")
    arguments = parser.parse_args()
    try:
        manifest = verify_envelope(
            arguments.envelope,
            arguments.public_key,
            arguments.key_id,
            arguments.openssl,
        )
    except EnvelopeError as error:
        parser.error(str(error))
    print(json.dumps(manifest, sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()

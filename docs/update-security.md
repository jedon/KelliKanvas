# Private update security

KelliKanvas authenticates the exact canonical UTF-8 bytes of `manifest.json`
with ECDSA P-256/SHA-256 before parsing or trusting any URL, package, version,
hash, size, or APK signer metadata. Canonical JSON has lexicographically sorted
known fields, compact separators, ASCII-safe strings, and exactly one trailing
LF. Duplicate, unknown, missing, mistyped, reordered, or non-canonical fields
are rejected. `manifest.json.sig` is a DER ECDSA signature and is bounded to
1 KiB.

The release app embeds the metadata public key from
`KELLIKANVAS_METADATA_PUBLIC_KEY_BASE64`. The corresponding private PEM is an
offline release secret used only by the isolated signing workflow step and is
deleted with the APK keystore immediately afterward. Neither key material nor
secret values may be logged or committed.

## Rotation

Metadata key rotation requires an app release signed by the existing APK
signer that pins both the current and next public keys. After that release is
installed on all supported devices, sign metadata with the next key. A later
APK may remove the old key. Never replace the sole pinned key and metadata
signature simultaneously: devices that missed the bridge release could not
authenticate updates.

## Replay and suppression

After signature verification, the app persists the highest authenticated
release sequence and version. Lower or equal sequences and lower versions are
rejected, while an empty store permits a fresh installation to bootstrap.
This bounds replay to metadata no newer than the highest release already seen.
An attacker who can indefinitely suppress network traffic can still prevent
updates; complete suppression prevention requires an independent availability
channel or trusted time, neither of which this private LAN design provides.

## Transport boundary

Remote/OVH origins must use an explicitly configured HTTPS origin. The QNAP
LAN endpoint remains exactly `http://darklingnas:8088`; HTTP is not described
as TLS. For that endpoint the signed canonical manifest, streamed SHA-256 and
size checks, independent checksum, exact package/version inspection, and match
to the currently installed APK signer are the trust boundary. Redirects,
userinfo, query/fragment tricks, and any unconfigured origin are rejected.

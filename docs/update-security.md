# Private update security

KelliKanvas fetches one canonical `update-envelope.json` control file. Its
strict schema is `envelopeSchema`, `keyId`, base64 `payload`, and base64
`signature`. The ECDSA P-256/SHA-256 signature authenticates the exact canonical
UTF-8 payload bytes before any URL, package, version, hash, size, or APK signer
metadata is trusted. Both JSON objects use lexicographically sorted known
fields, compact separators, ASCII-safe strings, and exactly one trailing LF.
Duplicate, unknown, missing, mistyped, reordered, non-canonical, malformed DER,
and unknown-key inputs are uniformly rejected.

The release app embeds the metadata public key from
`KELLIKANVAS_METADATA_PUBLIC_KEY_BASE64` as comma-separated
`keyId=SubjectPublicKeyInfoBase64` pins. The corresponding private PEM is used
only by an artifact-only metadata-signing job. APK signing runs in a different
job with only APK credentials; neither signing job runs Gradle or co-exposes
the other key. Secret files are deleted immediately.

## Rotation

Metadata key rotation requires an app release signed by the existing APK
signer that pins both the current and next public keys. After that release is
installed on all supported devices, sign metadata with the next key. A later
APK may remove the old key. Never replace the sole pinned key and metadata
signature simultaneously: devices that missed the bridge release could not
authenticate updates.

## Replay and suppression

After signature verification, the app atomically persists the highest
authenticated `(sequence, versionCode, payload SHA-256)` tuple. The exact same
tuple is an idempotent retry after a download failure, deferred install, or
restart. Reusing a sequence with different authenticated content, lower
sequences, and version rollback are rejected. An empty store permits bootstrap.
Installation is not a second replay marker: the installed package version and
signer are read from Android, while retryability remains tied to the exact
authenticated tuple.
This bounds replay to metadata no newer than the highest release already seen.
An attacker who can indefinitely suppress network traffic can still prevent
updates; complete suppression prevention requires an independent availability
channel or trusted time, neither of which this private LAN design provides.

The signed-release workflow also refuses to mint metadata when
`workflow_dispatch` `release_sequence` is not strictly greater than
`deploy/qnap/last-release-sequence.txt` (bootstrap `0`). Operators must commit
that watermark to the newly published sequence after a successful metadata
sign—otherwise the next release fails the same gate. The private-update
artifact includes `last-release-sequence.txt` as a reminder copy.

## Transport boundary

Remote/OVH origins must use an explicitly configured HTTPS origin. The QNAP
LAN trust set is `http://darklingnas:8088` (canonical published origin) and the
LAN IP alias `http://192.168.68.62:8088` for devices whose DNS does not resolve
`darklingnas`. HTTP is not described as TLS. For those endpoints the signed
canonical manifest, streamed SHA-256 and size checks, independent checksum,
exact package/version inspection, and match to the currently installed APK
signer are the trust boundary. Redirects, userinfo, query/fragment tricks, and
any unconfigured origin are rejected. Control-file fetch tries the hostname
first, then the LAN IP. Published envelope APK and checksum URLs must still use
an allowed origin the device can reach (prefer `darklingnas` when LAN DNS works).

The device may additionally allow one cached last-known-good LAN IP alias
(ordered hostname, cached IP, static IP for both the origin set and control-file
fetch). That IP is recorded only after a successful SMB/DLNA photo-source
connection to the NAS — never from SSDP discovery — must be an IPv4 literal, and
is passed to the policy explicitly when the update stack is constructed rather
than read from global state. This does not weaken the trust boundary: the
envelope signature, streamed SHA-256/size checks, independent checksum, and
installed-signer match remain what is trusted, while the origin list stays
defense-in-depth against fetching from arbitrary hosts. A poisoned cache entry
could at worst point the fetch at a host that cannot produce a validly signed
envelope.

# QNAP private update host

This container serves signed KelliKanvas APK updates from
`/share/Public/KelliKanvas` at `http://darklingnas:8088`. This is HTTP, not TLS.
Canonical signed metadata, APK hashes, and installed-signer matching provide
authenticity on the private LAN. Remote hosting must use HTTPS.

## Deploy

1. Install Container Station and make this directory available on the NAS.
2. Ensure `/share/Public/KelliKanvas` exists and is readable by container UID
   101. Do not grant the container write access.
3. Set `KELLIKANVAS_BIND_ADDRESS` to the NAS address on the designated trusted
   LAN interface (for example `192.168.10.20`). Never use `0.0.0.0`.
4. Configure the QNAP firewall to allow TCP/8088 only from the TV VLAN or
   explicit device addresses and deny WAN, guest, and management ingress.
5. Run `docker compose -f compose.yaml config`, then
   `docker compose -f compose.yaml up -d`.
6. Confirm `docker compose -f compose.yaml ps` reports healthy and test
   `curl -I http://darklingnas:8088/update-envelope.json`.

The image is pinned to the verified multi-architecture OCI index digest for
the stable nginx tag. Re-resolve and review the digest deliberately when
upgrading nginx.

## Publish

CI builds an unsigned APK, APK-signs it in a job that only has APK credentials,
and metadata-signs a prepared canonical payload in a separate job that only has
the offline metadata key. For a trusted local release:

```powershell
python tools/build_update_bundle.py .\kellikanvas-signed.apk `
  --metadata-private-key X:\offline\metadata-key.pem --key-id release-v1 --sequence 42
.\tools\publish-to-qnap.ps1 -BundlePath .\dist `
  -MetadataPublicKeyFile X:\offline\metadata-public.pem -MetadataKeyId release-v1
```

The publisher cryptographically validates the strict envelope, validates APK
and checksum hashes, and enforces monotonic sequence/version against the
deployed valid envelope. It stages and publishes versioned data first, then
atomically replaces the single `update-envelope.json` control file last. Any
failure before that rename leaves the previous control file active.
The server allows only GET and HEAD, disables directory listing, marks metadata
as `no-store`, and marks versioned APKs immutable.

Keep old versioned APKs for rollback diagnostics, but rollback installation is
not allowed by the app. Remove abandoned files manually after all devices have
upgraded.

## Instrumented updater gate

`./gradlew :platform:update:assembleDebugAndroidTest` compiles device tests
without requiring a stored fixture key. The Android build signs the generated
target/test APKs with its ephemeral debug keystore; tests inspect that real APK
through `PackageManager`, exercise a real `PackageInstaller` session lifecycle,
and verify `FileProvider` grants. Before release, run
`:platform:update:connectedDebugAndroidTest` on API 26, 28, 30, 34, and 36
emulators/devices. JVM/Robolectric success is not a substitute for this gate;
this repository does not claim those device tests ran unless those targets
were actually connected.

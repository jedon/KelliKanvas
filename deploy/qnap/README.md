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
   `curl -I http://darklingnas:8088/manifest.json`.

The image is pinned to the verified multi-architecture OCI index digest for
the stable nginx tag. Re-resolve and review the digest deliberately when
upgrading nginx.

## Publish

Build a signed release APK using the four `KELLIKANVAS_KEYSTORE_*` environment
variables, then create and publish the bundle from a trusted Windows machine:

```powershell
python tools/build_update_bundle.py app/build/outputs/apk/release/app-release.apk `
  --metadata-private-key X:\offline\metadata-key.pem --sequence 42
.\tools\publish-to-qnap.ps1 -BundlePath .\dist
```

The publisher verifies the source and copied hashes. It publishes the
versioned APK, checksum, and signature first and atomically replaces
`manifest.json` last.
The server allows only GET and HEAD, disables directory listing, marks metadata
as `no-store`, and marks versioned APKs immutable.

Keep old versioned APKs for rollback diagnostics, but rollback installation is
not allowed by the app. Remove abandoned files manually after all devices have
upgraded.

# QNAP private update host

This container serves signed KelliKanvas APK updates from
`/share/Public/KelliKanvas` at `http://darklingnas:8088`. It is intentionally
HTTP-only on the private LAN; the Android network policy permits cleartext only
for the exact `darklingnas` host.

## Deploy

1. Install Container Station and make this directory available on the NAS.
2. Ensure `/share/Public/KelliKanvas` exists and is readable by container UID
   101. Do not grant the container write access.
3. Run `docker compose -f compose.yaml config`, then
   `docker compose -f compose.yaml up -d`.
4. Confirm `docker compose -f compose.yaml ps` reports healthy and test
   `curl -I http://darklingnas:8088/manifest.json`.

The image is pinned to a stable nginx patch/tag. For stricter supply-chain
control, resolve the tag on the QNAP architecture and append its registry
digest (`image: nginx:...@sha256:...`) before deployment.

## Publish

Build a signed release APK using the four `KELLIKANVAS_KEYSTORE_*` environment
variables, then create and publish the bundle from a trusted Windows machine:

```powershell
python tools/build_update_bundle.py app/build/outputs/apk/release/app-release.apk
.\tools\publish-to-qnap.ps1 -BundlePath .\dist
```

The publisher verifies the source and copied hashes. It publishes the
versioned APK and checksum first and atomically replaces `manifest.json` last.
The server allows only GET and HEAD, disables directory listing, marks metadata
as `no-store`, and marks versioned APKs immutable.

Keep old versioned APKs for rollback diagnostics, but rollback installation is
not allowed by the app. Remove abandoned files manually after all devices have
upgraded.

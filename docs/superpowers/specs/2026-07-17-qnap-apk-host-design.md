# QNAP APK Host Design

**Status:** Approved  
**Date:** 2026-07-17  
**Audience:** Household LAN users installing KelliKanvas through Send to TV Quick

## Goal

Host all published KelliKanvas APK versions on DarklingNAS and provide a
mobile-friendly page whose direct APK URLs can be copied into the TV downloader
workflow.

## Architecture

QNAP Container Station runs a dedicated nginx container on
`192.168.68.81:8088`, the NAS's stable household LAN address. It does not bind
the NAS Tailscale interface. nginx mounts `/share/Public/KelliKanvas` read-only
and serves only static files. Container configuration lives separately under
the QNAP Container share so an APK publication cannot modify the web-server
configuration.

The service is available on the household LAN at:

- `http://darklingnas.local:8088/` for the version list.
- `http://darklingnas.local:8088/KelliKanvas-<version>.apk` for each published
  APK.

The QNAP Web Server remains disabled. The dedicated container avoids changing a
NAS-wide service and matches the existing KelliKanvas distribution plan.

## Version Page

The generated `index.html`:

- Lists versioned APKs newest-first.
- Highlights the newest APK as the latest version.
- Shows each version's filename, size, and publication time.
- Provides a normal download link and a Copy URL button for every version.
- Resolves copied URLs against the page's current origin, so the copied hostname
  or IP matches the address used on the phone.
- Uses the Clipboard API when available and a local fallback on cleartext LAN
  HTTP.
- Is responsive, keyboard-accessible, and usable without external scripts,
  fonts, analytics, or internet access.

The page is generated during publication rather than using nginx directory
listing. Only files intentionally included by the publisher appear on the page.

## Publication Flow

Published APKs use the filename `KelliKanvas-<semver>.apk`.

1. Copy a completed APK to a temporary name in
   `/share/Public/KelliKanvas`.
2. Verify its SHA-256 digest after transfer.
3. Atomically rename it to its versioned filename.
4. Generate a temporary index from all valid versioned APK filenames.
5. Atomically replace `index.html`.

An interrupted publication therefore leaves the previous page valid and does
not advertise an incomplete APK. Existing versions remain available for
rollback and manual installation. The later application-update manifest can
share this directory without being exposed in the human-facing list.

## Content Permissions

The configured SSH publisher owns `/share/Public/KelliKanvas`. Its effective
policy is mode `0755` with no named, mask, inherited, or default ACL entries, so
the publisher is the sole non-root writer while nginx UID 101 and household
clients retain read/traverse access. APKs and `index.html` are mode `0644`. The
root ephemeral generator is the intentional administrative writer. Deployment
and recovery never alter `/share/Public` or another share.

## nginx Policy

- Bind host port 8088 only on `192.168.68.81`.
- Pin nginx 1.30.4 at the reviewed multi-architecture digest containing fixes
  for CVE-2026-42533, CVE-2026-60005, and CVE-2026-56434.
- Mount APK content read-only.
- Limit the container to 0.5 CPU, 64 MiB memory, and 64 PIDs.
- Rotate Docker JSON logs and omit client address, host, query, and path from
  nginx access logs.
- Permit only `GET` and `HEAD`; reject mutation methods.
- Disable generic directory listing.
- Serve APKs as `application/vnd.android.package-archive`.
- Set `X-Content-Type-Options: nosniff` and a restrictive Content Security
  Policy suitable for the self-contained page.
- Avoid caching `index.html`; allow immutable caching for versioned APKs.
- Expose a lightweight health endpoint.
- Restart automatically unless explicitly stopped.

The host is intentionally unauthenticated because the TV downloader needs a
direct URL. It is intended only for the trusted household LAN and is not to be
forwarded through the router or exposed to the public internet.

## Failure Handling

- No APKs: show a valid page explaining that no versions are published.
- Invalid filenames: ignore them when generating the list.
- Failed digest verification: keep the temporary file unlisted and fail
  publication.
- Failed page generation: retain the previous `index.html`.
- Container restart or NAS reboot: Container Station restarts nginx
  automatically.
- Hostname resolution failure on a client: access the same page by the NAS IP;
  copied links then use that IP.

## Verification

Deployment is accepted when:

1. Container Station reports nginx healthy after deployment and after restart.
2. `http://darklingnas.local:8088/` and `http://192.168.68.81:8088/` return the
   mobile version page while `100.121.137.73:8088` rejects connections.
3. The page handles an empty APK directory.
4. Multiple fixture APK names appear newest-first with correct links.
5. Copy URL returns an absolute URL under the page's current origin.
6. GET and HEAD succeed while POST is rejected.
7. Directory listing and temporary files are inaccessible.
8. A downloaded APK's SHA-256 matches its source.
9. The page and an APK URL are reachable from a phone on the TV's LAN.


# QNAP APK host

QNAP Container Station serves `/share/Public/KelliKanvas` through nginx at
`http://darklingnas:8088`. The Compose file, nginx configuration, and index
generator live separately in `/share/Container/KelliKanvas`.

This service is for the trusted household LAN only. Do not forward port 8088
through the router or otherwise expose it to the public internet. It deliberately
has no authentication because Send to TV Quick must download each direct APK URL
without an interactive login.

## Deploy

Prerequisites:

- Container Station is installed and running.
- The operator has an SSH shell with permission to use Container Station and
  write both QNAP shares.
- `compose.yaml`, `nginx.conf`, and `generate_apk_index.py` have been copied to
  `/share/Container/KelliKanvas`.
- `/share/Public/KelliKanvas` exists and is traversable by container UID 101.

The expected files are:

```text
/share/Container/KelliKanvas/compose.yaml
/share/Container/KelliKanvas/nginx.conf
/share/Container/KelliKanvas/generate_apk_index.py
/share/Public/KelliKanvas/
```

Generate the initial index using the command in
[Regenerate the index](#regenerate-the-index), then locate Container Station
with `getcfg`, validate the Compose configuration, and start the service:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml config
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml up -d
```

## Publish an APK

The final filename must be `KelliKanvas-<semver>.apk` using strict SemVer.
Stable and prerelease examples are `KelliKanvas-1.2.3.apk` and
`KelliKanvas-1.2.3-rc.1.apk`. Do not add a `v`, omit a numeric component, use
leading zeroes in numeric identifiers, or use underscores.

Set `SOURCE` to the completed APK and `NAME` to its exact basename. Copy it to a
hidden temporary name, calculate both SHA-256 digests, explicitly compare them,
and only then atomically rename it:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
SOURCE=/path/to/KelliKanvas-1.2.3.apk
NAME=KelliKanvas-1.2.3.apk
TEMP="$CONTENT/.$NAME.tmp"
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'

test "$(basename "$SOURCE")" = "$NAME" || {
  echo "SOURCE basename and NAME differ; publication stopped" >&2
  exit 1
}
printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" || {
  echo "Invalid KelliKanvas SemVer filename; publication stopped" >&2
  exit 1
}

rm -f "$TEMP"
trap 'rm -f "$TEMP"' EXIT HUP INT TERM
cp "$SOURCE" "$TEMP"
SOURCE_SHA256="$(sha256sum "$SOURCE")"
SOURCE_SHA256="${SOURCE_SHA256%% *}"
COPIED_SHA256="$(sha256sum "$TEMP")"
COPIED_SHA256="${COPIED_SHA256%% *}"

if [ "$SOURCE_SHA256" != "$COPIED_SHA256" ]; then
  echo "SHA-256 mismatch; deleting temporary copy" >&2
  rm -f "$TEMP"
  exit 1
fi

chmod 0644 "$TEMP"
mv "$TEMP" "$CONTENT/$NAME"
trap - EXIT HUP INT TERM
```

Do not continue after a digest mismatch. After a successful rename, regenerate
the index.

## Regenerate the index

Run the generator in the pinned, official, multi-architecture Python image. The
content mount is read-write so the generator can replace `index.html`; the
generator itself is mounted read-only:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" run --rm \
  -v /share/Public/KelliKanvas:/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python /app/generate.py --directory /content
```

The generator writes `index.html` through a same-directory temporary file,
flushes it, sets mode `0644`, and atomically replaces the old index. nginx mounts
the entire content directory read-only, so it cannot alter APKs or the index.

## Send an APK from a phone

1. On the phone, browse to `http://darklingnas:8088`.
2. Tap **Copy URL** beside the required version.
3. Open [sendtoquick.com](https://sendtoquick.com) and paste the URL into the
   device paired with Send to TV Quick.

The phone and TV must both resolve and reach the LAN hostname in the copied URL.
If `darklingnas` does not resolve through mDNS or local DNS, open the page by the
NAS IP, for example `http://192.168.1.10:8088`; **Copy URL** will then copy a URL
using that IP.

## Verify

Run the container-health check on the NAS. The HTTP checks can run there or on
another LAN machine. Set `NAME` to a published version:

```sh
BASE=http://darklingnas:8088
NAME=KelliKanvas-1.2.3.apk

# Container health
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test "$("$CS/bin/docker" inspect \
  --format '{{.State.Health.Status}}' kellikanvas-apk-host)" = healthy

# Health endpoint and page
test "$(curl -fsS "$BASE/healthz")" = ok
curl -fsS "$BASE/" >/dev/null

# Published APK supports HEAD
curl -fsSI "$BASE/$NAME" >/dev/null

# Mutation is rejected
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "$BASE/")" = 405
```

Verify the page's security headers and ensure the `Server` header does not
disclose an nginx version:

```sh
HEADERS="$(mktemp)"
curl -fsSI "$BASE/" >"$HEADERS"
grep -Eiq '^X-Content-Type-Options:[[:space:]]*nosniff' "$HEADERS"
grep -Eiq '^Referrer-Policy:[[:space:]]*no-referrer' "$HEADERS"
grep -Eiq "^Content-Security-Policy:.*default-src 'none'" "$HEADERS"
grep -Eiq '^Cache-Control:[[:space:]]*no-store' "$HEADERS"
grep -Eiq '^Server:[[:space:]]*nginx[[:space:]]*$' "$HEADERS"

if grep -Eiq '^Server:.*nginx/' "$HEADERS"; then
  echo "nginx version disclosed" >&2
  rm -f "$HEADERS"
  exit 1
fi
rm -f "$HEADERS"
```

Finally, download the APK and explicitly compare its SHA-256 with the source:

```sh
set -eu
SOURCE=/path/to/KelliKanvas-1.2.3.apk
DOWNLOADED="$(mktemp)"
trap 'rm -f "$DOWNLOADED"' EXIT HUP INT TERM
curl -fsS "$BASE/$NAME" -o "$DOWNLOADED"
SOURCE_SHA256="$(sha256sum "$SOURCE")"
SOURCE_SHA256="${SOURCE_SHA256%% *}"
DOWNLOADED_SHA256="$(sha256sum "$DOWNLOADED")"
DOWNLOADED_SHA256="${DOWNLOADED_SHA256%% *}"

if [ "$SOURCE_SHA256" != "$DOWNLOADED_SHA256" ]; then
  echo "Downloaded APK SHA-256 mismatch" >&2
  rm -f "$DOWNLOADED"
  exit 1
fi
rm -f "$DOWNLOADED"
trap - EXIT HUP INT TERM
```

## Roll back, remove, or stop

Published URLs are versioned and remain stable when newer APKs are added. To
remove a version, delete only its versioned APK and rerun
[Regenerate the index](#regenerate-the-index). Existing versions are unaffected.

Stop and remove the Compose service with:

```sh
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml down
```

## Troubleshooting

- **Container is unhealthy:** inspect
  `"$CS/bin/docker" logs kellikanvas-apk-host` and
  `"$CS/bin/docker" inspect kellikanvas-apk-host`. Confirm Container Station is
  running, port 8088 is available, and `nginx.conf` is present under the
  configuration directory.
- **Permission denied or 404:** APKs and `index.html` need mode `0644`, and every
  directory in their path must be traversable by UID 101. Use mode `0755` on the
  content directory when a stricter compatible mode has not been configured.
- **Hostname does not resolve:** use the NAS LAN IP to open the page. Confirm the
  phone and TV are on a network that can reach that IP and port 8088.
- **An APK is present but unavailable:** its filename is invalid or does not
  exactly match the strict `KelliKanvas-<semver>.apk` form. Rename or republish
  it with a valid stable or prerelease SemVer filename, then regenerate the
  index.

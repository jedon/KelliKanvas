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
- The workstation has `ssh` and `scp`, and the repository is the current
  directory.
- `QNAP_NAS_HOST` and `QNAP_NAS_USERNAME` are configured in the workstation
  environment. Use an SSH key, agent, or interactive password prompt; never put
  a password in a command or shell history.
- That SSH account can use Container Station and write both QNAP shares.

The expected files are:

```text
/share/Container/KelliKanvas/compose.yaml
/share/Container/KelliKanvas/nginx.conf
/share/Container/KelliKanvas/generate_apk_index.py
/share/Public/KelliKanvas/
```

From PowerShell at the repository root, create the remote directories and copy
the approved files:

```powershell
if ([string]::IsNullOrWhiteSpace($env:QNAP_NAS_HOST) -or
    [string]::IsNullOrWhiteSpace($env:QNAP_NAS_USERNAME)) {
    throw "Set QNAP_NAS_HOST and QNAP_NAS_USERNAME first"
}
$Remote = "$($env:QNAP_NAS_USERNAME)@$($env:QNAP_NAS_HOST)"

$Bootstrap = @'
set -eu
mkdir -p /share/Container/KelliKanvas /share/Public/KelliKanvas
chmod 0755 /share/Container/KelliKanvas /share/Public/KelliKanvas
'@
ssh $Remote $Bootstrap

scp deploy/qnap/compose.yaml deploy/qnap/nginx.conf `
  tools/generate_apk_index.py `
  "${Remote}:/share/Container/KelliKanvas/"

$Permissions = @'
set -eu
chmod 0644 \
  /share/Container/KelliKanvas/compose.yaml \
  /share/Container/KelliKanvas/nginx.conf \
  /share/Container/KelliKanvas/generate_apk_index.py
'@
ssh $Remote $Permissions
```

Generate the initial index and start Compose from the workstation. The remote
command locates Container Station with `getcfg`, checks the Docker executable,
validates Compose, and only then starts it:

```powershell
$Start = @'
set -eu
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
"$CS/bin/docker" run --rm \
  -v /share/Public/KelliKanvas:/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python /app/generate.py --directory /content
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml config
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml up -d
'@
ssh $Remote $Start
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
FINAL="$CONTENT/$NAME"
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'

test "$(basename "$SOURCE")" = "$NAME" || {
  echo "SOURCE basename and NAME differ; publication stopped" >&2
  exit 1
}
printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" || {
  echo "Invalid KelliKanvas SemVer filename; publication stopped" >&2
  exit 1
}
if [ -e "$FINAL" ] || [ -L "$FINAL" ]; then
  echo "Version already exists; immutable APK will not be overwritten: $FINAL" >&2
  exit 1
fi

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
mv "$TEMP" "$FINAL"
trap - EXIT HUP INT TERM
```

Do not continue after a digest mismatch. A published version is immutable: never
replace an existing final path. After a successful rename, regenerate the index.

## Regenerate the index

Run the generator in the pinned, official, multi-architecture Python image. The
content mount is read-write so the generator can replace `index.html`; the
generator itself is mounted read-only:

```sh
set -eu
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
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

Run the container-health check on the NAS:

```sh
set -eu
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
HEALTH="$("$CS/bin/docker" inspect \
  --format '{{.State.Health.Status}}' kellikanvas-apk-host)"
test "$HEALTH" = healthy || {
  echo "Container health is $HEALTH, not healthy" >&2
  exit 1
}
```

Run the HTTP checks on the NAS or another LAN machine. Set `NAME` to a
published version:

```sh
set -eu
BASE=http://darklingnas:8088
NAME=KelliKanvas-1.2.3.apk

# Health endpoint and page
HEALTH_BODY="$(curl -fsS "$BASE/healthz")"
test "$HEALTH_BODY" = ok || {
  echo "Unexpected health response: $HEALTH_BODY" >&2
  exit 1
}
curl -fsS "$BASE/" >/dev/null

# Published APK supports HEAD
curl -fsSI "$BASE/$NAME" >/dev/null

# Mutation is rejected
POST_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "$BASE/")"
test "$POST_STATUS" = 405 || {
  echo "POST returned $POST_STATUS, expected 405" >&2
  exit 1
}
```

Verify the page's security headers and ensure the `Server` header does not
disclose an nginx version:

```sh
set -eu
BASE=http://darklingnas:8088
HEADERS="$(mktemp)"
trap 'rm -f "$HEADERS"' EXIT HUP INT TERM
curl -fsSI "$BASE/" >"$HEADERS"
grep -Eiq '^X-Content-Type-Options:[[:space:]]*nosniff' "$HEADERS" || {
  echo "Missing X-Content-Type-Options: nosniff" >&2
  exit 1
}
grep -Eiq '^Referrer-Policy:[[:space:]]*no-referrer' "$HEADERS" || {
  echo "Missing Referrer-Policy: no-referrer" >&2
  exit 1
}
grep -Eiq "^Content-Security-Policy:.*default-src 'none'" "$HEADERS" || {
  echo "Missing restrictive Content-Security-Policy" >&2
  exit 1
}
grep -Eiq '^Cache-Control:[[:space:]]*no-store' "$HEADERS" || {
  echo "Missing Cache-Control: no-store" >&2
  exit 1
}
grep -Eiq '^Server:[[:space:]]*nginx[[:space:]]*$' "$HEADERS" || {
  echo "Missing version-free Server: nginx header" >&2
  exit 1
}

if grep -Eiq '^Server:.*nginx/' "$HEADERS"; then
  echo "nginx version disclosed" >&2
  exit 1
fi
rm -f "$HEADERS"
trap - EXIT HUP INT TERM
```

Finally, download the APK and explicitly compare its SHA-256 with the source:

```sh
set -eu
BASE=http://darklingnas:8088
NAME=KelliKanvas-1.2.3.apk
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

Published URLs are immutable and remain stable when newer APKs are added.
Publication refuses to overwrite an existing version. To remove one explicitly
selected version, run the following on the NAS. If it was the latest version,
the next valid version becomes latest after regeneration:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
BASE=http://darklingnas:8088
NAME=KelliKanvas-1.2.3.apk
FINAL="$CONTENT/$NAME"
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'

printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" || {
  echo "Refusing to remove an invalid or ambiguous filename" >&2
  exit 1
}
if [ ! -f "$FINAL" ] || [ -L "$FINAL" ]; then
  echo "Selected version does not exist: $FINAL" >&2
  exit 1
fi
rm "$FINAL"

CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
"$CS/bin/docker" run --rm \
  -v /share/Public/KelliKanvas:/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python /app/generate.py --directory /content

PAGE="$(mktemp)"
trap 'rm -f "$PAGE"' EXIT HUP INT TERM
curl -fsS "$BASE/" -o "$PAGE"
if grep -Fq "$NAME" "$PAGE"; then
  echo "Removed version is still advertised" >&2
  exit 1
fi
REMOVED_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/$NAME")"
test "$REMOVED_STATUS" = 404 || {
  echo "Removed URL returned $REMOVED_STATUS, expected 404" >&2
  exit 1
}
rm -f "$PAGE"
trap - EXIT HUP INT TERM
```

This intentionally retires only the selected URL; all other versioned URLs and
files remain unchanged.

Stop and remove the Compose service with:

```sh
set -eu
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml down
```

## Troubleshooting

### Container is unhealthy

Run on the NAS. Confirm Container Station is running, port 8088 is available,
and the configuration files exist:

```sh
set -eu
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
ls -l \
  /share/Container/KelliKanvas/compose.yaml \
  /share/Container/KelliKanvas/nginx.conf
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml ps
"$CS/bin/docker" logs --tail 100 kellikanvas-apk-host
"$CS/bin/docker" inspect \
  --format 'status={{.State.Status}} health={{.State.Health.Status}}' \
  kellikanvas-apk-host
```

### Permission denied or 404

APKs and `index.html` must be mode `0644`. Every directory component must be
traversable by container UID 101. `ls` and `stat` are available on QNAP even
when `namei` is not:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"

for DIRECTORY in /share /share/Public "$CONTENT"; do
  ls -ld "$DIRECTORY"
done

for FILE in "$CONTENT/index.html" "$CONTENT"/KelliKanvas-*.apk; do
  test -e "$FILE" || continue
  MODE="$(stat -c '%a' "$FILE")"
  printf '%s %s\n' "$MODE" "$FILE"
  test "$MODE" = 644 || {
    echo "Expected mode 0644: $FILE" >&2
    exit 1
  }
done

"$CS/bin/docker" exec -u 101:101 kellikanvas-apk-host \
  test -r /usr/share/nginx/html/index.html
```

Use `chmod 0755 /share/Public/KelliKanvas` and `chmod 0644` on the index and
APKs to correct the documented default modes.

### Hostname does not resolve

Run from the affected client or the NAS, replacing `NAS_IP` with the actual LAN
address. The commands report hostname resolution separately from direct-IP
reachability:

```sh
set -u
HOST=darklingnas
NAS_IP=192.168.1.10

if command -v getent >/dev/null 2>&1; then
  getent hosts "$HOST" || echo "Hostname lookup failed: $HOST" >&2
else
  ping -c 1 "$HOST" || echo "Hostname lookup or reachability failed: $HOST" >&2
fi

curl -fsS "http://$HOST:8088/healthz" ||
  echo "Hostname URL is unreachable" >&2
curl -fsS "http://$NAS_IP:8088/healthz" ||
  echo "NAS IP URL is unreachable" >&2
```

If only the IP works, open the page by IP so **Copy URL** also uses the IP.

### APK is present but unavailable

The generator ignores invalid filenames, directories, and symlinks. Run this on
the NAS to print malformed APK filenames and then show exactly which APK links
the real generator discovers:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'

for APK in "$CONTENT"/*.apk; do
  test -e "$APK" || continue
  NAME="${APK##*/}"
  if test -L "$APK" || test ! -f "$APK" ||
    ! printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN"; then
    echo "Ignored by generator: $NAME"
  fi
done

CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
"$CS/bin/docker" run --rm \
  -v /share/Public/KelliKanvas:/content:ro \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  sh -c 'python /app/generate.py --directory /content --output /tmp/discovery.html
if ! grep -o "href=\"KelliKanvas-[^\"]*\.apk\"" /tmp/discovery.html; then
  echo "No valid APKs discovered"
fi'
```

Republish malformed files with a valid stable or prerelease SemVer filename,
then regenerate the index.

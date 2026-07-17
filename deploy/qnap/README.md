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
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:QNAP_NAS_HOST) -or
    [string]::IsNullOrWhiteSpace($env:QNAP_NAS_USERNAME)) {
    throw "Set QNAP_NAS_HOST and QNAP_NAS_USERNAME first"
}
$Remote = "$($env:QNAP_NAS_USERNAME)@$($env:QNAP_NAS_HOST)"

$Bootstrap = @'
set -eu
for DIRECTORY in /share/Container/KelliKanvas /share/Public/KelliKanvas; do
  if [ ! -d "$DIRECTORY" ]; then
    mkdir -p "$DIRECTORY"
    chmod 0755 "$DIRECTORY"
  fi
  test -x "$DIRECTORY" || {
    echo "Directory is not traversable: $DIRECTORY" >&2
    exit 1
  }
done
'@
ssh $Remote $Bootstrap
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create or validate remote directories"
}

scp deploy/qnap/compose.yaml deploy/qnap/nginx.conf `
  tools/generate_apk_index.py `
  "${Remote}:/share/Container/KelliKanvas/"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to upload QNAP deployment files"
}

$Permissions = @'
set -eu
chmod 0644 \
  /share/Container/KelliKanvas/compose.yaml \
  /share/Container/KelliKanvas/nginx.conf \
  /share/Container/KelliKanvas/generate_apk_index.py
'@
ssh $Remote $Permissions
if ($LASTEXITCODE -ne 0) {
    throw "Failed to set remote deployment-file modes"
}
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
"$CS/bin/docker" run --rm --user 101:101 \
  -v /share/Public/KelliKanvas:/content:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  sh -c 'test -x /content && test -r /content/index.html'
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml config
"$CS/bin/docker" compose \
  -f /share/Container/KelliKanvas/compose.yaml up -d

ATTEMPT=0
HEALTH=starting
while [ "$ATTEMPT" -lt 30 ]; do
  if HEALTH="$("$CS/bin/docker" inspect \
    --format '{{.State.Health.Status}}' kellikanvas-apk-host 2>/dev/null)"; then
    test "$HEALTH" = healthy && break
  else
    HEALTH=unavailable
  fi
  ATTEMPT=$((ATTEMPT + 1))
  sleep 2
done
test "$HEALTH" = healthy || {
  echo "Container did not become healthy; last state: $HEALTH" >&2
  exit 1
}
'@
ssh $Remote $Start
if ($LASTEXITCODE -ne 0) {
    throw "Failed to generate the index or start a healthy APK host"
}
```

## Publish an APK

The final filename must be `KelliKanvas-<semver>.apk` using strict SemVer.
Stable and prerelease examples are `KelliKanvas-1.2.3.apk` and
`KelliKanvas-1.2.3-rc.1.apk`. Do not add a `v`, omit a numeric component, use
leading zeroes in numeric identifiers, or use underscores.

Run the publication commands on the NAS. Set `SOURCE` to a completed APK on a
non-public path and `NAME` to its exact basename. If the APK must be transferred
from a workstation, SCP it first to a restricted staging directory such as
`/share/Container/KelliKanvas/incoming` created with mode `0700`, wait for SCP to
finish successfully, and use that staged file as `SOURCE`; never SCP directly
into the public content directory.

The command creates a unique temporary file in the content directory, compares
the complete source and copied SHA-256 digests, and creates the final path with
a same-filesystem hard link. `ln` is atomic and fails rather than replacing an
existing version:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
SOURCE=/share/Container/KelliKanvas/incoming/KelliKanvas-1.2.3.apk
NAME=KelliKanvas-1.2.3.apk
FINAL="$CONTENT/$NAME"
LOCK="$CONTENT/.$NAME.lock"
TEMP=
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'

cleanup_publication() {
  STATUS=$?
  trap - 0 HUP INT TERM
  set +e
  test -z "$TEMP" || rm -f "$TEMP"
  rmdir "$LOCK" 2>/dev/null || true
  exit "$STATUS"
}

test "$(basename "$SOURCE")" = "$NAME" || {
  echo "SOURCE basename and NAME differ; publication stopped" >&2
  exit 1
}
printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" || {
  echo "Invalid KelliKanvas SemVer filename; publication stopped" >&2
  exit 1
}
if ! mkdir "$LOCK"; then
  echo "Another operation holds the version lock: $LOCK" >&2
  exit 1
fi
trap cleanup_publication 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

TEMP="$(mktemp "$CONTENT/.$NAME.publish.XXXXXX")"
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
if ! ln "$TEMP" "$FINAL"; then
  echo "Version already exists or cannot be published: $FINAL" >&2
  exit 1
fi
rm "$TEMP"
TEMP=
rmdir "$LOCK"
trap - 0 HUP INT TERM
exit 0
```

Do not continue after a digest mismatch or failed `ln`. The temporary and final
links refer to the same verified inode until the temporary link is removed. A
published version is immutable: never replace an existing final path. After a
successful publication, regenerate the index.

`SIGKILL` and a NAS reboot cannot run shell traps. They can leave a locked
version with a hidden publication temporary or quarantined APK while
`index.html` reflects either the old or new state. If `mkdir "$LOCK"` reports a
lock, run this conservative reconciliation on the NAS for exactly one version.
It refuses ambiguous states before changing anything, requires interactive
confirmation, reconciles the APK, regenerates the index, and removes the lock
only after generation succeeds:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
NAME=KelliKanvas-1.2.3.apk
FINAL="$CONTENT/$NAME"
LOCK="$CONTENT/.$NAME.lock"
PUBLISH_TEMP=
PUBLISH_COUNT=0
QUARANTINE_DIR=
QUARANTINE_APK=
QUARANTINE_COUNT=0
QUARANTINE_EMPTY=0
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'

refuse_recovery() {
  echo "Recovery refused: $*" >&2
  echo "Lock retained for manual investigation: $LOCK" >&2
  exit 1
}

printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" ||
  refuse_recovery "NAME is not strict KelliKanvas SemVer"
test -d "$LOCK" && test ! -L "$LOCK" ||
  refuse_recovery "exact lock is missing, not a directory, or a symlink"
test -z "$(ls -A "$LOCK")" ||
  refuse_recovery "exact lock directory contains unexpected entries"

if [ -e "$FINAL" ] || [ -L "$FINAL" ]; then
  test -f "$FINAL" && test ! -L "$FINAL" ||
    refuse_recovery "FINAL exists but is not a regular non-symlink APK"
  FINAL_PRESENT=1
else
  FINAL_PRESENT=0
fi

for CANDIDATE in "$CONTENT/.$NAME.publish."*; do
  if [ ! -e "$CANDIDATE" ] && [ ! -L "$CANDIDATE" ]; then
    continue
  fi
  PUBLISH_COUNT=$((PUBLISH_COUNT + 1))
  test "$PUBLISH_COUNT" -eq 1 ||
    refuse_recovery "multiple matching publication temporaries"
  test -f "$CANDIDATE" && test ! -L "$CANDIDATE" ||
    refuse_recovery "publication temporary has an unexpected type"
  PUBLISH_TEMP="$CANDIDATE"
done

for CANDIDATE in "$CONTENT/.$NAME.quarantine."*; do
  if [ ! -e "$CANDIDATE" ] && [ ! -L "$CANDIDATE" ]; then
    continue
  fi
  QUARANTINE_COUNT=$((QUARANTINE_COUNT + 1))
  test "$QUARANTINE_COUNT" -eq 1 ||
    refuse_recovery "multiple matching quarantine paths"
  test -d "$CANDIDATE" && test ! -L "$CANDIDATE" ||
    refuse_recovery "quarantine path is not a regular directory"
  QUARANTINE_DIR="$CANDIDATE"
  QUARANTINE_ENTRIES="$(ls -A "$CANDIDATE")"
  if [ -z "$QUARANTINE_ENTRIES" ]; then
    QUARANTINE_EMPTY=1
  elif [ "$QUARANTINE_ENTRIES" = "$NAME" ]; then
    test -f "$CANDIDATE/$NAME" && test ! -L "$CANDIDATE/$NAME" ||
      refuse_recovery "quarantined APK has an unexpected type"
    QUARANTINE_APK="$CANDIDATE/$NAME"
  else
    refuse_recovery "quarantine contains unexpected entries"
  fi
done

test "$PUBLISH_COUNT" -eq 0 || test "$QUARANTINE_COUNT" -eq 0 ||
  refuse_recovery "publication temporary and quarantine both exist"
test "$FINAL_PRESENT" -eq 0 || test "$QUARANTINE_COUNT" -eq 0 ||
  test "$QUARANTINE_EMPTY" -eq 1 ||
  refuse_recovery "FINAL and quarantine both exist"

echo "Exact recovery state:"
ls -lid "$LOCK"
if [ "$FINAL_PRESENT" -eq 1 ]; then
  ls -li "$FINAL"
else
  echo "FINAL is absent: $FINAL"
fi
if [ "$PUBLISH_COUNT" -eq 1 ]; then
  ls -li "$PUBLISH_TEMP"
  if [ "$FINAL_PRESENT" -eq 1 ]; then
    TEMP_ID="$(stat -c '%d:%i' "$PUBLISH_TEMP")"
    FINAL_ID="$(stat -c '%d:%i' "$FINAL")"
    if [ "$TEMP_ID" = "$FINAL_ID" ]; then
      echo "Publication temporary is another hard link to FINAL."
    else
      echo "Publication temporary is distinct from FINAL."
    fi
  fi
fi
if [ "$QUARANTINE_COUNT" -eq 1 ]; then
  ls -lid "$QUARANTINE_DIR"
  if [ "$QUARANTINE_EMPTY" -eq 1 ]; then
    echo "Quarantine directory is empty; FINAL state will be preserved."
  else
    ls -li "$QUARANTINE_APK"
  fi
fi
ps

printf 'After checking all sessions/processes, type the exact NAME to reconcile: '
IFS= read -r CONFIRMED_NAME ||
  refuse_recovery "interactive confirmation was not read"
test "$CONFIRMED_NAME" = "$NAME" ||
  refuse_recovery "confirmation did not exactly match NAME"

if [ "$QUARANTINE_COUNT" -eq 1 ]; then
  if [ "$QUARANTINE_EMPTY" -eq 0 ]; then
    mv "$QUARANTINE_APK" "$FINAL"
    FINAL_PRESENT=1
  fi
  rmdir "$QUARANTINE_DIR"
fi

if [ "$PUBLISH_COUNT" -eq 1 ]; then
  rm "$PUBLISH_TEMP"
fi

CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker" ||
  refuse_recovery "Container Station Docker is unavailable"
"$CS/bin/docker" run --rm \
  -v /share/Public/KelliKanvas:/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python /app/generate.py --directory /content ||
  refuse_recovery "index regeneration failed after APK reconciliation"

rmdir "$LOCK" ||
  refuse_recovery "index is consistent but exact lock removal failed"
```

If the procedure refuses or any command fails, leave the exact lock in place and
investigate manually. Do not remove matching files with a wildcard, recursively
delete a lock/quarantine, or use this as a general cleanup utility.

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

Run the HTTP policy checks on the NAS because the procedure creates two harmless
unique sentinel files in the served content directory. Set `NAME` to a published
version:

```sh
set -eu
BASE=http://darklingnas:8088
CONTENT=/share/Public/KelliKanvas
NAME=KelliKanvas-1.2.3.apk
CHECK_DIR=
HIDDEN_SENTINEL=
ARBITRARY_SENTINEL=

cleanup_http_check() {
  STATUS=$?
  trap - 0 HUP INT TERM
  set +e
  test -z "$HIDDEN_SENTINEL" || rm -f "$HIDDEN_SENTINEL"
  test -z "$ARBITRARY_SENTINEL" || rm -f "$ARBITRARY_SENTINEL"
  test -z "$CHECK_DIR" || rm -rf "$CHECK_DIR"
  exit "$STATUS"
}

trap cleanup_http_check 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

CHECK_DIR="$(mktemp -d)"
HIDDEN_SENTINEL="$(mktemp "$CONTENT/.http-policy-hidden.tmp.XXXXXX")"
ARBITRARY_SENTINEL="$(mktemp "$CONTENT/http-policy-arbitrary.txt.XXXXXX")"
printf '%s\n' "harmless HTTP policy sentinel" >"$HIDDEN_SENTINEL"
printf '%s\n' "harmless HTTP policy sentinel" >"$ARBITRARY_SENTINEL"
chmod 0644 "$HIDDEN_SENTINEL" "$ARBITRARY_SENTINEL"
HIDDEN_NAME="${HIDDEN_SENTINEL##*/}"
ARBITRARY_NAME="${ARBITRARY_SENTINEL##*/}"

HEALTH_STATUS="$(curl -fsS -o "$CHECK_DIR/health" \
  -w '%{http_code}' "$BASE/healthz")"
test "$HEALTH_STATUS" = 200 || {
  echo "Health returned $HEALTH_STATUS, expected 200" >&2
  exit 1
}
test "$(cat "$CHECK_DIR/health")" = ok || {
  echo "Unexpected health response body" >&2
  exit 1
}

ROOT_STATUS="$(curl -fsS -D "$CHECK_DIR/root.headers" \
  -o "$CHECK_DIR/root.body" -w '%{http_code}' "$BASE/")"
test "$ROOT_STATUS" = 200 || {
  echo "Root returned $ROOT_STATUS, expected 200" >&2
  exit 1
}

APK_STATUS="$(curl -fsSI -o "$CHECK_DIR/apk.headers" \
  -w '%{http_code}' "$BASE/$NAME")"
test "$APK_STATUS" = 200 || {
  echo "APK HEAD returned $APK_STATUS, expected 200" >&2
  exit 1
}

POST_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "$BASE/")"
test "$POST_STATUS" = 405 || {
  echo "POST returned $POST_STATUS, expected 405" >&2
  exit 1
}

HIDDEN_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' \
  "$BASE/$HIDDEN_NAME")"
test "$HIDDEN_STATUS" = 404 || {
  echo "Hidden path returned $HIDDEN_STATUS, expected 404" >&2
  exit 1
}

ARBITRARY_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' \
  "$BASE/$ARBITRARY_NAME")"
test "$ARBITRARY_STATUS" = 404 || {
  echo "Arbitrary path returned $ARBITRARY_STATUS, expected 404" >&2
  exit 1
}

grep -Eiq '^X-Content-Type-Options:[[:space:]]*nosniff' \
  "$CHECK_DIR/root.headers" || {
  echo "Missing X-Content-Type-Options: nosniff" >&2
  exit 1
}
grep -Eiq '^Referrer-Policy:[[:space:]]*no-referrer' \
  "$CHECK_DIR/root.headers" || {
  echo "Missing Referrer-Policy: no-referrer" >&2
  exit 1
}
grep -Eiq "^Content-Security-Policy:.*default-src 'none'.*frame-ancestors 'none'" \
  "$CHECK_DIR/root.headers" || {
  echo "Missing restrictive Content-Security-Policy" >&2
  exit 1
}
grep -Eiq '^Cache-Control:[[:space:]]*no-store' \
  "$CHECK_DIR/root.headers" || {
  echo "Missing Cache-Control: no-store" >&2
  exit 1
}
grep -Eiq '^Server:[[:space:]]*nginx[[:space:]]*$' \
  "$CHECK_DIR/root.headers" || {
  echo "Missing version-free Server: nginx header" >&2
  exit 1
}
if grep -Eiq '^Server:.*nginx/' "$CHECK_DIR/root.headers"; then
  echo "nginx version disclosed" >&2
  exit 1
fi

grep -Eiq '^Content-Type:[[:space:]]*application/vnd\.android\.package-archive' \
  "$CHECK_DIR/apk.headers" || {
  echo "Missing Android APK Content-Type" >&2
  exit 1
}
grep -Eiq '^Cache-Control:[[:space:]]*public,[[:space:]]*max-age=31536000,[[:space:]]*immutable' \
  "$CHECK_DIR/apk.headers" || {
  echo "Missing immutable APK Cache-Control" >&2
  exit 1
}
grep -Eiq '^Content-Disposition:[[:space:]]*attachment' \
  "$CHECK_DIR/apk.headers" || {
  echo "Missing attachment Content-Disposition" >&2
  exit 1
}
grep -Eiq '^X-Content-Type-Options:[[:space:]]*nosniff' \
  "$CHECK_DIR/apk.headers" || {
  echo "Missing APK X-Content-Type-Options: nosniff" >&2
  exit 1
}
grep -Eiq '^Referrer-Policy:[[:space:]]*no-referrer' \
  "$CHECK_DIR/apk.headers" || {
  echo "Missing APK Referrer-Policy: no-referrer" >&2
  exit 1
}

exit 0
```

Finally, download the APK and explicitly compare its SHA-256 with the source:

```sh
set -eu
BASE=http://darklingnas:8088
NAME=KelliKanvas-1.2.3.apk
SOURCE=/path/to/KelliKanvas-1.2.3.apk
DOWNLOADED=

cleanup_download_check() {
  STATUS=$?
  trap - 0 HUP INT TERM
  set +e
  test -z "$DOWNLOADED" || rm -f "$DOWNLOADED"
  exit "$STATUS"
}

trap cleanup_download_check 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

DOWNLOADED="$(mktemp)"
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
exit 0
```

## Roll back, remove, or stop

Published URLs are immutable and remain stable when newer APKs are added.
Publication refuses to overwrite an existing version. To remove one explicitly
selected version, run the following on the NAS. It takes the same per-version
lock used by publication, atomically moves the APK into a unique hidden
quarantine directory, regenerates and verifies the page, and only then deletes
the quarantined file. If generation or verification fails, the exit trap
atomically restores the APK and regenerates the old index before returning a
nonzero status. If the removed APK was latest, the next valid version becomes
latest:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
BASE=http://darklingnas:8088
NAME=KelliKanvas-1.2.3.apk
FINAL="$CONTENT/$NAME"
LOCK="$CONTENT/.$NAME.lock"
QUARANTINE_DIR=
QUARANTINE=
PAGE=
RESTORE_NEEDED=0
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"

regenerate_index() {
  "$CS/bin/docker" run --rm \
    -v /share/Public/KelliKanvas:/content \
    -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
    python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
    python /app/generate.py --directory /content
}

rollback_removal() {
  STATUS=$?
  trap - 0 HUP INT TERM
  set +e
  test "$STATUS" -ne 0 || STATUS=1
  RECOVERY_FAILED=0
  APK_READY=0

  recovery_failed() {
    echo "RECOVERY REQUIRED: $*" >&2
    RECOVERY_FAILED=1
  }

  if [ -n "$PAGE" ] && ! rm -f "$PAGE"; then
    recovery_failed "failed to remove verification temporary: $PAGE"
  fi

  if [ "$RESTORE_NEEDED" -eq 1 ]; then
    if [ -f "$QUARANTINE" ] && [ ! -L "$QUARANTINE" ]; then
      if mv "$QUARANTINE" "$FINAL"; then
        APK_READY=1
      else
        recovery_failed "failed to restore quarantined APK: $QUARANTINE"
      fi
    elif [ -f "$FINAL" ] && [ ! -L "$FINAL" ]; then
      APK_READY=1
    else
      recovery_failed "neither FINAL nor a restorable quarantine APK exists"
    fi

    if [ "$APK_READY" -eq 1 ] && ! regenerate_index; then
      recovery_failed "failed to regenerate the old index after APK restoration"
    fi
  fi

  if [ -n "$QUARANTINE_DIR" ]; then
    if [ -d "$QUARANTINE_DIR" ] && [ ! -L "$QUARANTINE_DIR" ]; then
      rmdir "$QUARANTINE_DIR" 2>/dev/null ||
        recovery_failed "quarantine cleanup failed: $QUARANTINE_DIR"
    elif [ -e "$QUARANTINE_DIR" ] || [ -L "$QUARANTINE_DIR" ]; then
      recovery_failed "quarantine path has an unexpected type: $QUARANTINE_DIR"
    fi
  fi

  if [ "$RECOVERY_FAILED" -eq 0 ]; then
    rmdir "$LOCK" 2>/dev/null ||
      recovery_failed "failed to release the exact version lock: $LOCK"
  fi

  if [ "$RECOVERY_FAILED" -ne 0 ]; then
    echo "RECOVERY REQUIRED: lock retained; reconcile this exact NAME before another operation." >&2
  fi
  exit "$STATUS"
}

printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" || {
  echo "Refusing to remove an invalid or ambiguous filename" >&2
  exit 1
}
if [ ! -f "$FINAL" ] || [ -L "$FINAL" ]; then
  echo "Selected version does not exist: $FINAL" >&2
  exit 1
fi
if ! mkdir "$LOCK"; then
  echo "Another operation holds the version lock: $LOCK" >&2
  exit 1
fi
trap rollback_removal 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

QUARANTINE_DIR="$(mktemp -d "$CONTENT/.$NAME.quarantine.XXXXXX")"
QUARANTINE="$QUARANTINE_DIR/$NAME"
RESTORE_NEEDED=1
mv "$FINAL" "$QUARANTINE"
regenerate_index

PAGE="$(mktemp)"
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

rm "$QUARANTINE"
RESTORE_NEEDED=0
rm -f "$PAGE"
PAGE=
rmdir "$QUARANTINE_DIR"
QUARANTINE_DIR=
rmdir "$LOCK"
trap - 0 HUP INT TERM
exit 0
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
traversable by container UID 101. New dedicated directories use mode `0755`;
existing directory modes are not reset because they may implement a local QNAP
access policy. `ls` and `stat` are available on QNAP even when `namei` is not:

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

Use `chmod 0644` on an incorrectly-modeled index or APK. Change an existing
directory policy only after confirming the intended QNAP ACLs; the container
read test above is the authoritative traversability check.

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
  if [ ! -e "$APK" ] && [ ! -L "$APK" ]; then
    continue
  fi
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

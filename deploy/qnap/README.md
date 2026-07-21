# QNAP APK host

QNAP Container Station serves `/share/Public/KelliKanvas` through nginx at
`http://darklingnas.local:8088` (`http://192.168.68.62:8088`). The Compose
file binds only the stable household LAN address `192.168.68.62`; the NAS
Tailscale address does not accept this service. Configuration and the index
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
- That SSH account can use Container Station and owns the dedicated
  `/share/Public/KelliKanvas` child directory. It is the only non-root writer;
  the ephemeral root generator is the intentional administrative exception.

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
CONFIG=/share/Container/KelliKanvas
CONTENT=/share/Public/KelliKanvas

if [ ! -d "$CONFIG" ]; then
  mkdir -p "$CONFIG"
  chmod 0755 "$CONFIG"
fi
if [ ! -d "$CONTENT" ]; then
  mkdir -p "$CONTENT"
fi

test -x "$CONFIG"
test "$(stat -c '%u' "$CONTENT")" = "$(id -u)" || {
  echo "Content directory is not owned by the configured SSH publisher" >&2
  exit 1
}
command -v getfacl >/dev/null
command -v setfacl >/dev/null

echo "Existing child ACL (inspect before continuing):"
getfacl -n -p "$CONTENT"

# Change only the dedicated child. Never apply these commands to /share/Public.
setfacl -k "$CONTENT"
setfacl -b "$CONTENT"
chmod 0755 "$CONTENT"

ACL="$(getfacl -n -p "$CONTENT")"
test "$(stat -c '%a' "$CONTENT")" = 755
printf '%s\n' "$ACL" | grep -Eq '^user::rwx$'
printf '%s\n' "$ACL" | grep -Eq '^group::r-x$'
printf '%s\n' "$ACL" | grep -Eq '^other::r-x$'
if printf '%s\n' "$ACL" |
  grep -Eq '^(user|group):[0-9]+:|^mask::|^default:'; then
  echo "Extended or default ACL entries still grant inherited access" >&2
  exit 1
fi
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
CONTENT=/share/Public/KelliKanvas
LOCK="$CONTENT/.kellikanvas-operation.lock"
LOCK_HELD=0

cleanup_initial_generation() {
  STATUS=$?
  trap - 0 HUP INT TERM
  set +e
  if [ "$LOCK_HELD" -eq 1 ]; then
    rm -f "$LOCK/owner"
    rmdir "$LOCK" || STATUS=1
  fi
  exit "$STATUS"
}

mkdir "$LOCK" || {
  echo "Another APK/index operation holds $LOCK" >&2
  exit 1
}
LOCK_HELD=1
trap cleanup_initial_generation 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'operation=regenerate\nname=-\npid=%s\n' "$$" >"$LOCK/owner"
chmod 0644 "$LOCK/owner"

"$CS/bin/docker" run --rm \
  -v "$CONTENT":/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python /app/generate.py --directory /content
rm "$LOCK/owner"
rmdir "$LOCK"
LOCK_HELD=0
trap - 0 HUP INT TERM

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

The service pins the multi-architecture
`nginx:1.30.4-alpine@sha256:59d10bca5c674965ef4ff884715000dd60ef5567c36663523f108eec8e4105d4`
image, which includes the 2026-07-15 fixes for CVE-2026-42533,
CVE-2026-60005, and CVE-2026-56434. Compose limits nginx to 0.5 CPU, 64 MiB,
and 64 PIDs. Docker JSON logs rotate at 10 MiB with three files. Access logs
retain only timestamp, method, status, and response bytes; they omit client
address, hostname, query, and requested path.

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

The command acquires the one content-wide operation lock before creating public
state, records minimal owner metadata, creates a unique temporary file, compares
the complete source and copied SHA-256 digests, and publishes with a
same-filesystem hard link. `ln` is atomic and fails rather than replacing an
existing version. The lock remains held through index generation and HTTP
verification:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
BASE=http://darklingnas.local:8088
SOURCE=/share/Container/KelliKanvas/incoming/KelliKanvas-1.2.3.apk
NAME=KelliKanvas-1.2.3.apk
FINAL="$CONTENT/$NAME"
LOCK="$CONTENT/.kellikanvas-operation.lock"
TEMP=
PAGE=
FINAL_CREATED=0
INDEX_CONSISTENT=0
DUPLICATE_NOOP=0
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"

regenerate_index() {
  "$CS/bin/docker" run --rm \
    -v "$CONTENT":/content \
    -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
    python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
    python /app/generate.py --directory /content
}

release_global_lock() {
  rm -f "$LOCK/owner" && rmdir "$LOCK"
}

cleanup_publication() {
  STATUS=$?
  trap - 0 HUP INT TERM
  set +e
  test "$STATUS" -ne 0 || STATUS=1
  RECOVERY_FAILED=0
  OWN_FINAL="$FINAL_CREATED"

  if [ "$DUPLICATE_NOOP" -eq 1 ]; then
    if ! release_global_lock; then
      echo "RECOVERY REQUIRED: duplicate was unchanged but global lock release failed" >&2
      echo "RECOVERY REQUIRED: global lock retained for stale recovery." >&2
    fi
    exit "$STATUS"
  fi

  if [ -n "$PAGE" ] && ! rm -f "$PAGE"; then
    echo "RECOVERY REQUIRED: failed to remove verification temporary: $PAGE" >&2
    RECOVERY_FAILED=1
  fi

  if [ "$OWN_FINAL" -eq 0 ] &&
    [ -n "$TEMP" ] &&
    [ -f "$TEMP" ] && [ ! -L "$TEMP" ] &&
    [ -f "$FINAL" ] && [ ! -L "$FINAL" ]; then
    if TEMP_ID="$(stat -c '%d:%i' "$TEMP")" &&
      FINAL_ID="$(stat -c '%d:%i' "$FINAL")"; then
      test "$TEMP_ID" != "$FINAL_ID" || OWN_FINAL=1
    else
      echo "RECOVERY REQUIRED: failed to identify publication hard links" >&2
      RECOVERY_FAILED=1
    fi
  fi

  if [ "$INDEX_CONSISTENT" -eq 0 ] && [ "$OWN_FINAL" -eq 1 ]; then
    if rm "$FINAL"; then
      if ! regenerate_index; then
        echo "RECOVERY REQUIRED: previous index regeneration failed" >&2
        RECOVERY_FAILED=1
      fi
    else
      echo "RECOVERY REQUIRED: failed to remove newly published FINAL" >&2
      RECOVERY_FAILED=1
    fi
  elif [ "$INDEX_CONSISTENT" -eq 0 ] &&
    { [ -e "$FINAL" ] || [ -L "$FINAL" ]; }; then
    echo "RECOVERY REQUIRED: unexpected FINAL is not the publication temporary inode" >&2
    RECOVERY_FAILED=1
  fi

  if [ "$RECOVERY_FAILED" -eq 0 ] &&
    [ -n "$TEMP" ] && ! rm -f "$TEMP"; then
    echo "RECOVERY REQUIRED: failed to remove publication temporary: $TEMP" >&2
    RECOVERY_FAILED=1
  fi

  if [ "$RECOVERY_FAILED" -eq 0 ]; then
    if ! release_global_lock; then
      echo "RECOVERY REQUIRED: failed to release global operation lock" >&2
      RECOVERY_FAILED=1
    fi
  fi
  if [ "$RECOVERY_FAILED" -ne 0 ]; then
    echo "RECOVERY REQUIRED: global lock retained for stale recovery." >&2
  fi
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
  echo "Another APK/index operation holds the global lock: $LOCK" >&2
  exit 1
fi
trap cleanup_publication 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'operation=publish\nname=%s\npid=%s\n' "$NAME" "$$" >"$LOCK/owner"
chmod 0644 "$LOCK/owner"
if [ -e "$FINAL" ] || [ -L "$FINAL" ]; then
  if [ -f "$FINAL" ] && [ ! -L "$FINAL" ]; then
    DUPLICATE_NOOP=1
    echo "Version already exists; immutable APK will not be overwritten: $FINAL" >&2
  else
    echo "RECOVERY REQUIRED: existing FINAL has an unexpected type: $FINAL" >&2
  fi
  exit 1
fi

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
FINAL_CREATED=1
rm "$TEMP"
TEMP=
regenerate_index

PAGE="$(mktemp)"
curl -fsS "$BASE/" -o "$PAGE"
grep -Fq "href=\"$NAME\"" "$PAGE" || {
  echo "Published version is missing from generated page" >&2
  exit 1
}
APK_STATUS="$(curl -fsSI -o /dev/null -w '%{http_code}' "$BASE/$NAME")"
test "$APK_STATUS" = 200 || {
  echo "Published APK HEAD returned $APK_STATUS, expected 200" >&2
  exit 1
}
INDEX_CONSISTENT=1
rm -f "$PAGE"
PAGE=
release_global_lock
trap - 0 HUP INT TERM
exit 0
```

Do not continue after a digest mismatch or failed `ln`. The temporary and final
links refer to the same verified inode until the temporary link is removed. A
published version is immutable: never replace an existing final path. If
generation or verification fails after the link is created, rollback removes
that newly created final and regenerates the previous index before unlocking.
Any rollback failure retains the global lock for stale recovery.
A duplicate immutable-version attempt is checked while the global lock is held,
before TEMP creation. It exits nonzero without changing content and explicitly
removes its owner metadata and lock; it does not require stale recovery.

`SIGKILL` and a NAS reboot cannot run shell traps. They can leave the global
lock while an APK and `index.html` reflect different phases of one operation.
The lock's `owner` file identifies `publish`, `remove`, or `regenerate`, the
exact SemVer `NAME` where applicable, and the shell PID.

If lock acquisition fails, first inspect the metadata and all NAS sessions and
processes. Run this conservative recovery only after confirming that the
recorded operation is not live. It validates the metadata and every matching
publication/quarantine path across the content directory, reconciles only the
identified operation, regenerates the index, and unlocks only after consistency
is restored. Missing, corrupt, unexpected, or ambiguous state retains the lock:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
LOCK="$CONTENT/.kellikanvas-operation.lock"
OWNER="$LOCK/owner"
PUBLISH_TEMP=
PUBLISH_COUNT=0
QUARANTINE_DIR=
QUARANTINE_APK=
QUARANTINE_COUNT=0
QUARANTINE_EMPTY=0
FINAL_PRESENT=0
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'

refuse_recovery() {
  echo "Recovery refused: $*" >&2
  echo "Global lock retained for manual investigation: $LOCK" >&2
  exit 1
}

test -d "$LOCK" && test ! -L "$LOCK" ||
  refuse_recovery "global lock is missing, not a directory, or a symlink"
test "$(ls -A "$LOCK")" = owner ||
  refuse_recovery "lock must contain exactly one owner file"
test -f "$OWNER" && test ! -L "$OWNER" ||
  refuse_recovery "owner metadata is not a regular non-symlink file"

LINE_COUNT="$(wc -l <"$OWNER" | tr -d ' ')"
OPERATION_COUNT="$(grep -c '^operation=' "$OWNER" || true)"
NAME_COUNT="$(grep -c '^name=' "$OWNER" || true)"
PID_COUNT="$(grep -c '^pid=' "$OWNER" || true)"
VALID_COUNT="$(grep -Ec '^(operation|name|pid)=' "$OWNER" || true)"
test "$LINE_COUNT" = 3 &&
  test "$OPERATION_COUNT" = 1 &&
  test "$NAME_COUNT" = 1 &&
  test "$PID_COUNT" = 1 &&
  test "$VALID_COUNT" = 3 ||
  refuse_recovery "owner metadata keys are missing, duplicated, or unexpected"

OPERATION="$(sed -n 's/^operation=//p' "$OWNER")"
NAME="$(sed -n 's/^name=//p' "$OWNER")"
OWNER_PID="$(sed -n 's/^pid=//p' "$OWNER")"
case "$OPERATION" in
  publish|remove)
    printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" ||
      refuse_recovery "owner NAME is not strict KelliKanvas SemVer"
    ;;
  regenerate)
    test "$NAME" = - ||
      refuse_recovery "regenerate metadata must use name=-"
    ;;
  *)
    refuse_recovery "unknown owner operation"
    ;;
esac
printf '%s\n' "$OWNER_PID" | grep -Eq '^[1-9][0-9]*$' ||
  refuse_recovery "owner PID is invalid"

if [ "$OPERATION" != regenerate ]; then
  FINAL="$CONTENT/$NAME"
  if [ -e "$FINAL" ] || [ -L "$FINAL" ]; then
    test -f "$FINAL" && test ! -L "$FINAL" ||
      refuse_recovery "FINAL has an unexpected type"
    FINAL_PRESENT=1
  fi
fi

for CANDIDATE in "$CONTENT"/.KelliKanvas-*.publish.*; do
  if [ ! -e "$CANDIDATE" ] && [ ! -L "$CANDIDATE" ]; then
    continue
  fi
  test "$OPERATION" = publish ||
    refuse_recovery "publication state does not match owner operation"
  case "$CANDIDATE" in
    "$CONTENT/.$NAME.publish."*) ;;
    *) refuse_recovery "publication state belongs to another NAME" ;;
  esac
  PUBLISH_COUNT=$((PUBLISH_COUNT + 1))
  test "$PUBLISH_COUNT" -eq 1 ||
    refuse_recovery "multiple publication temporaries"
  test -f "$CANDIDATE" && test ! -L "$CANDIDATE" ||
    refuse_recovery "publication temporary has an unexpected type"
  PUBLISH_TEMP="$CANDIDATE"
done

for CANDIDATE in "$CONTENT"/.KelliKanvas-*.quarantine.*; do
  if [ ! -e "$CANDIDATE" ] && [ ! -L "$CANDIDATE" ]; then
    continue
  fi
  test "$OPERATION" = remove ||
    refuse_recovery "quarantine state does not match owner operation"
  case "$CANDIDATE" in
    "$CONTENT/.$NAME.quarantine."*) ;;
    *) refuse_recovery "quarantine state belongs to another NAME" ;;
  esac
  QUARANTINE_COUNT=$((QUARANTINE_COUNT + 1))
  test "$QUARANTINE_COUNT" -eq 1 ||
    refuse_recovery "multiple quarantine paths"
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

if [ "$OPERATION" = publish ]; then
  test "$QUARANTINE_COUNT" -eq 0 ||
    refuse_recovery "publish owner has quarantine state"
  if [ "$FINAL_PRESENT" -eq 1 ] && [ "$PUBLISH_COUNT" -eq 1 ]; then
    FINAL_ID="$(stat -c '%d:%i' "$FINAL")" ||
      refuse_recovery "cannot read FINAL device and inode"
    TEMP_ID="$(stat -c '%d:%i' "$PUBLISH_TEMP")" ||
      refuse_recovery "cannot read publication TEMP device and inode"
    test "$FINAL_ID" = "$TEMP_ID" ||
      refuse_recovery "FINAL and publication TEMP are different inodes"
  fi
elif [ "$OPERATION" = remove ]; then
  test "$PUBLISH_COUNT" -eq 0 ||
    refuse_recovery "remove owner has publication state"
  test "$FINAL_PRESENT" -eq 0 || test "$QUARANTINE_COUNT" -eq 0 ||
    test "$QUARANTINE_EMPTY" -eq 1 ||
    refuse_recovery "FINAL and nonempty quarantine both exist"
else
  test "$PUBLISH_COUNT" -eq 0 && test "$QUARANTINE_COUNT" -eq 0 ||
    refuse_recovery "regenerate owner has unexpected APK operation state"
fi

echo "Validated recovery owner:"
cat "$OWNER"
ls -lid "$LOCK"
if [ "$OPERATION" != regenerate ]; then
  if [ "$FINAL_PRESENT" -eq 1 ]; then
    ls -li "$FINAL"
  else
    echo "FINAL is absent: $FINAL"
  fi
fi
test "$PUBLISH_COUNT" -eq 0 || ls -li "$PUBLISH_TEMP"
if [ "$QUARANTINE_COUNT" -eq 1 ]; then
  ls -lid "$QUARANTINE_DIR"
  if [ "$QUARANTINE_EMPTY" -eq 1 ]; then
    echo "Quarantine is empty; current FINAL state will be preserved."
  else
    ls -li "$QUARANTINE_APK"
  fi
fi
if kill -0 "$OWNER_PID" 2>/dev/null; then
  echo "WARNING: recorded PID $OWNER_PID still exists; verify it is unrelated." >&2
fi
ps

TOKEN="RECOVER $OPERATION:$NAME:$OWNER_PID"
printf 'After proving no live operation owns this lock, type "%s": ' "$TOKEN"
IFS= read -r CONFIRMED ||
  refuse_recovery "interactive confirmation was not read"
test "$CONFIRMED" = "$TOKEN" ||
  refuse_recovery "confirmation did not exactly match owner metadata"

if [ "$OPERATION" = publish ] && [ "$PUBLISH_COUNT" -eq 1 ]; then
  rm "$PUBLISH_TEMP"
fi
if [ "$OPERATION" = remove ] && [ "$QUARANTINE_COUNT" -eq 1 ]; then
  if [ "$QUARANTINE_EMPTY" -eq 0 ]; then
    mv "$QUARANTINE_APK" "$FINAL"
  fi
  rmdir "$QUARANTINE_DIR"
fi

CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker" ||
  refuse_recovery "Container Station Docker is unavailable"
"$CS/bin/docker" run --rm \
  -v "$CONTENT":/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python /app/generate.py --directory /content ||
  refuse_recovery "index regeneration failed after state reconciliation"

rm "$OWNER" ||
  refuse_recovery "index is consistent but owner metadata removal failed"
rmdir "$LOCK" ||
  refuse_recovery "index is consistent but global lock removal failed"
```

If the procedure refuses or any command fails, leave the global lock in place
and investigate manually. Do not remove state with a wildcard, recursively
delete the lock/quarantine, invent metadata, or use this as general cleanup.

## Regenerate the index

Run the generator in the pinned, official, multi-architecture Python image. A
standalone regeneration acquires the same content-wide lock and refuses to run
beside publication, removal, recovery, or another regeneration. The content
mount is read-write so the generator can replace `index.html`; the generator
itself is mounted read-only:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
LOCK="$CONTENT/.kellikanvas-operation.lock"
LOCK_HELD=0
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"

cleanup_regeneration() {
  STATUS=$?
  trap - 0 HUP INT TERM
  set +e
  if [ "$LOCK_HELD" -eq 1 ]; then
    if rm -f "$LOCK/owner"; then
      rmdir "$LOCK" || STATUS=1
    else
      STATUS=1
    fi
  fi
  exit "$STATUS"
}

mkdir "$LOCK" || {
  echo "Another APK/index operation holds the global lock: $LOCK" >&2
  exit 1
}
LOCK_HELD=1
trap cleanup_regeneration 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'operation=regenerate\nname=-\npid=%s\n' "$$" >"$LOCK/owner"
chmod 0644 "$LOCK/owner"

"$CS/bin/docker" run --rm \
  -v "$CONTENT":/content \
  -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python /app/generate.py --directory /content
rm "$LOCK/owner"
rmdir "$LOCK"
LOCK_HELD=0
trap - 0 HUP INT TERM
exit 0
```

The generator writes `index.html` through a same-directory temporary file,
flushes it, sets mode `0644`, and atomically replaces the old index. nginx mounts
the entire content directory read-only, so it cannot alter APKs or the index.

## Send an APK from a phone

1. On the phone, browse to `http://darklingnas.local:8088`.
2. Tap **Copy URL** beside the required version.
3. Open [sendtoquick.com](https://sendtoquick.com) and paste the URL into the
   device paired with Send to TV Quick.

The phone and TV must both resolve and reach the LAN hostname in the copied URL.
If `darklingnas.local` does not resolve through mDNS or local DNS, open the page
at `http://192.168.68.62:8088`; **Copy URL** will then copy a URL using that IP.

## Install on Google TV / Hisense (Downloader)

TV Downloader apps typically reject cleartext `http://192.168.68.62:8088/...`
URLs and often refuse debug-signed APKs. Use a **release-signed** APK over
**public HTTPS** (GitHub Releases already terminates TLS with a publicly trusted
certificate; no Let's Encrypt setup is required on the NAS).

Current TV-installable build:

- HTTPS (paste into Downloader):
  `https://github.com/jedon/KelliKanvas/releases/download/v1.0.11/KelliKanvas-1.0.11.apk`
- LAN mirror (browsers / phone workflows):
  `http://192.168.68.62:8088/KelliKanvas-1.0.11.apk`

### Republish a newer TV build

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Build unsigned release with
   `KELLIKANVAS_METADATA_PUBLIC_KEY_BASE64` set from
   `~/.kellikanvas/release/metadata-pin.txt`.
3. Sign with `apksigner` using
   `~/.kellikanvas/release/kellikanvas-release.p12` and
   `release-secrets.env` (never commit those files).
4. Publish the SemVer file to QNAP (see **Publish an APK**) so the LAN index
   **Latest** row updates.
5. Create or update the matching GitHub Release asset
   (`gh release create vX.Y.Z KelliKanvas-X.Y.Z.apk ...`) so Downloader keeps a
   trusted `https://github.com/jedon/KelliKanvas/releases/download/...` URL.

### Certificate renewal

GitHub manages the HTTPS certificate for `github.com` / release CDN hosts.
There is nothing to renew on DarklingNAS for the TV Downloader URL. The QNAP
host intentionally remains LAN-only HTTP on port 8088 and should not be
forwarded to the public internet.

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
BASE=http://darklingnas.local:8088
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
BASE=http://darklingnas.local:8088
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
selected version, run the following on the NAS. It takes the same content-wide
lock used by publication and regeneration, records the exact operation owner,
atomically moves the APK into a unique hidden
quarantine directory, regenerates and verifies the page, and only then deletes
the quarantined file. If generation or verification fails, the exit trap
atomically restores the APK and regenerates the old index before returning a
nonzero status. If the removed APK was latest, the next valid version becomes
latest:

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
BASE=http://darklingnas.local:8088
NAME=KelliKanvas-1.2.3.apk
FINAL="$CONTENT/$NAME"
LOCK="$CONTENT/.kellikanvas-operation.lock"
QUARANTINE_DIR=
QUARANTINE=
PAGE=
RESTORE_NEEDED=0
SEMVER_APK_PATTERN='^KelliKanvas-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\.apk$'
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"

regenerate_index() {
  "$CS/bin/docker" run --rm \
    -v "$CONTENT":/content \
    -v /share/Container/KelliKanvas/generate_apk_index.py:/app/generate.py:ro \
    python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
    python /app/generate.py --directory /content
}

release_global_lock() {
  rm -f "$LOCK/owner" && rmdir "$LOCK"
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
    release_global_lock ||
      recovery_failed "failed to release the global operation lock: $LOCK"
  fi

  if [ "$RECOVERY_FAILED" -ne 0 ]; then
    echo "RECOVERY REQUIRED: global lock retained; run conservative stale recovery." >&2
  fi
  exit "$STATUS"
}

printf '%s\n' "$NAME" | grep -Eq "$SEMVER_APK_PATTERN" || {
  echo "Refusing to remove an invalid or ambiguous filename" >&2
  exit 1
}
if ! mkdir "$LOCK"; then
  echo "Another APK/index operation holds the global lock: $LOCK" >&2
  exit 1
fi
trap rollback_removal 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'operation=remove\nname=%s\npid=%s\n' "$NAME" "$$" >"$LOCK/owner"
chmod 0644 "$LOCK/owner"
if [ ! -f "$FINAL" ] || [ -L "$FINAL" ]; then
  echo "Selected version does not exist: $FINAL" >&2
  exit 1
fi

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
release_global_lock
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

## Validate content-wide serialization

Run this reproducible test from a Linux/WSL shell at the repository root, never
against the NAS content directory. It uses a throwaway container directory and
controlled pauses before generation. Each competing operation first attempts
the same global `mkdir`; the test proves a publish/publish and publish/remove
competitor cannot create, quarantine, or remove an APK while the first publisher
holds the lock. Each competitor is then retried after release, and the final
page is compared exactly with the real generator's discovered APK list. It also
proves a duplicate version is a clean no-op, matching stale hard links reconcile,
and different stale inodes remain locked without changing the page:

```sh
set -eu
docker run --rm -i \
  -v "$PWD/tools/generate_apk_index.py:/app/generate.py:ro" \
  python:3.13-alpine@sha256:399babc8b49529dabfd9c922f2b5eea81d611e4512e3ed250d75bd2e7683f4b0 \
  python - /app/generate.py <<'PY'
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import uuid

generator_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("apk_generator", generator_path)
assert spec is not None and spec.loader is not None
generator = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = generator
spec.loader.exec_module(generator)

with tempfile.TemporaryDirectory() as directory:
    content = Path(directory)
    lock = content / ".kellikanvas-operation.lock"

    def acquire(operation: str, name: str) -> None:
        lock.mkdir()
        (lock / "owner").write_text(
            f"operation={operation}\nname={name}\npid={os.getpid()}\n",
            encoding="ascii",
        )

    def release() -> None:
        (lock / "owner").unlink()
        lock.rmdir()

    def generate() -> None:
        subprocess.run(
            [sys.executable, str(generator_path), "--directory", str(content)],
            check=True,
        )

    def require_same_inode(final: Path, temporary: Path) -> None:
        final_stat = final.stat()
        temporary_stat = temporary.stat()
        if (final_stat.st_dev, final_stat.st_ino) != (
            temporary_stat.st_dev,
            temporary_stat.st_ino,
        ):
            raise RuntimeError("FINAL and publication TEMP are different inodes")

    def publish(
        name: str,
        entered: threading.Event | None = None,
        proceed: threading.Event | None = None,
    ) -> None:
        acquire("publish", name)
        if (content / name).exists():
            release()
            raise FileExistsError(name)
        temporary = content / f".{name}.publish.{uuid.uuid4().hex}"
        try:
            temporary.write_bytes(name.encode("ascii"))
            os.chmod(temporary, 0o644)
            os.link(temporary, content / name)
            temporary.unlink()
            if entered is not None and proceed is not None:
                entered.set()
                assert proceed.wait(10), "controlled publisher pause timed out"
            generate()
        finally:
            if temporary.exists():
                temporary.unlink()
            release()

    def blocked(operation: str, name: str) -> None:
        try:
            acquire(operation, name)
        except FileExistsError:
            return
        release()
        raise AssertionError(f"{operation} unexpectedly acquired the global lock")

    def paused_publish(name: str) -> tuple[threading.Thread, threading.Event]:
        entered = threading.Event()
        proceed = threading.Event()
        errors: list[BaseException] = []

        def worker() -> None:
            try:
                publish(name, entered, proceed)
            except BaseException as error:
                errors.append(error)

        thread = threading.Thread(target=worker)
        thread.start()
        assert entered.wait(10), "publisher did not reach controlled pause"
        thread.errors = errors  # type: ignore[attr-defined]
        return thread, proceed

    def finish(thread: threading.Thread, proceed: threading.Event) -> None:
        proceed.set()
        thread.join(10)
        assert not thread.is_alive(), "publisher did not finish"
        errors = thread.errors  # type: ignore[attr-defined]
        if errors:
            raise errors[0]

    first = "KelliKanvas-1.0.0.apk"
    second = "KelliKanvas-2.0.0.apk"
    third = "KelliKanvas-3.0.0.apk"

    thread, proceed = paused_publish(first)
    blocked("publish", second)
    assert not (content / second).exists()
    assert not list(content.glob(f".{second}.publish.*"))
    finish(thread, proceed)
    publish(second)

    page_before_duplicate = (content / "index.html").read_bytes()
    first_before_duplicate = (content / first).read_bytes()
    try:
        publish(first)
    except FileExistsError:
        pass
    else:
        raise AssertionError("duplicate immutable publication unexpectedly succeeded")
    assert not lock.exists()
    assert not list(content.glob(f".{first}.publish.*"))
    assert (content / first).read_bytes() == first_before_duplicate
    assert (content / "index.html").read_bytes() == page_before_duplicate

    thread, proceed = paused_publish(third)
    blocked("remove", second)
    assert (content / second).is_file()
    assert not list(content.glob(f".{second}.quarantine.*"))
    finish(thread, proceed)

    acquire("remove", second)
    quarantine = content / f".{second}.quarantine.{uuid.uuid4().hex}"
    try:
        quarantine.mkdir()
        os.replace(content / second, quarantine / second)
        generate()
        (quarantine / second).unlink()
        quarantine.rmdir()
    finally:
        release()

    discovered = [release.filename for release in generator.discover_apks(content)]
    page = (content / "index.html").read_text(encoding="utf-8")
    advertised = re.findall(r'class="download" href="([^"]+)"', page)
    assert advertised == discovered, (advertised, discovered)
    assert discovered == [third, first], discovered
    assert not lock.exists()
    assert not list(content.glob(".*.publish.*"))
    assert not list(content.glob(".*.quarantine.*"))

    matching = "KelliKanvas-4.0.0.apk"
    matching_final = content / matching
    matching_temp = content / f".{matching}.publish.matching"
    acquire("publish", matching)
    matching_temp.write_bytes(b"matching")
    os.link(matching_temp, matching_final)
    require_same_inode(matching_final, matching_temp)
    matching_temp.unlink()
    generate()
    release()
    assert matching in (content / "index.html").read_text(encoding="utf-8")

    acquire("remove", matching)
    matching_final.unlink()
    generate()
    release()

    mismatched = "KelliKanvas-5.0.0.apk"
    mismatched_final = content / mismatched
    mismatched_temp = content / f".{mismatched}.publish.mismatched"
    page_before_mismatch = (content / "index.html").read_bytes()
    acquire("publish", mismatched)
    mismatched_final.write_bytes(b"final")
    mismatched_temp.write_bytes(b"temporary")
    try:
        require_same_inode(mismatched_final, mismatched_temp)
    except RuntimeError:
        pass
    else:
        raise AssertionError("different stale publication inodes were accepted")
    assert lock.exists()
    assert mismatched_final.exists() and mismatched_temp.exists()
    assert (content / "index.html").read_bytes() == page_before_mismatch
    assert mismatched not in page_before_mismatch.decode("utf-8")

    mismatched_temp.unlink()
    mismatched_final.unlink()
    release()
    assert not lock.exists()
    assert (content / "index.html").read_bytes() == page_before_mismatch

print("global serialization, stale inode policy, and exact final index: PASS")
PY
```

The container's temporary directory is removed on success or failure, so the
test leaves no fixture APKs, lock, quarantine, or generated page on the host.

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

APKs and `index.html` must be mode `0644`. The dedicated content child must be
owned by the configured SSH publisher, mode `0755`, and have no named, mask, or
default ACL entries. That leaves the publisher as the only non-root writer
while nginx UID 101 and household clients retain read/traverse access. The
ephemeral generator intentionally runs as root. Never change `/share/Public` or
another share while repairing this child.

```sh
set -eu
CONTENT=/share/Public/KelliKanvas
CS="$(getcfg container-station Install_Path -f /etc/config/qpkg.conf)"
test -x "$CS/bin/docker"
command -v getfacl >/dev/null
command -v setfacl >/dev/null

for DIRECTORY in /share /share/Public "$CONTENT"; do
  ls -ld "$DIRECTORY"
done

test "$(stat -c '%u' "$CONTENT")" = "$(id -u)" || {
  echo "Content directory owner is not the configured publisher" >&2
  exit 1
}

ACL="$(getfacl -n -p "$CONTENT")"
printf '%s\n' "$ACL"
test "$(stat -c '%a' "$CONTENT")" = 755
printf '%s\n' "$ACL" | grep -Eq '^user::rwx$'
printf '%s\n' "$ACL" | grep -Eq '^group::r-x$'
printf '%s\n' "$ACL" | grep -Eq '^other::r-x$'
if printf '%s\n' "$ACL" |
  grep -Eq '^(user|group):[0-9]+:|^mask::|^default:'; then
  echo "Unexpected extended or default ACL on dedicated content child" >&2
  exit 1
fi

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

Use `chmod 0644` on an incorrectly-modeled index or APK. If QNAP share settings
or a restore reintroduce inherited/default rights, inspect the numeric ACL, then
run `setfacl -k "$CONTENT"`, `setfacl -b "$CONTENT"`, and
`chmod 0755 "$CONTENT"` on this exact child only. Repeat every assertion above
before publishing or recovering an APK.

### Hostname does not resolve

Run from the affected client or the NAS, replacing `NAS_IP` with the actual LAN
address. The commands report hostname resolution separately from direct-IP
reachability:

```sh
set -u
HOST=darklingnas.local
NAS_IP=192.168.68.62

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

## Signed in-app updates

The same read-only host can also serve the authenticated in-app update channel.
The human-facing site uses `KelliKanvas-<semver>.apk`; the updater publishes
immutable `kellikanvas-<versionCode>.apk`, its checksum, and a signed
`update-envelope.json`.

Build a signed bundle with the offline metadata key, then use the verified
publisher:

```powershell
python tools/build_update_bundle.py .\kellikanvas-signed.apk `
  --metadata-private-key X:\offline\metadata-key.pem `
  --key-id release-v1 --sequence 1
.\tools\publish-to-qnap.ps1 -BundlePath .\dist `
  -MetadataPublicKeyFile X:\offline\metadata-public.pem `
  -MetadataKeyId release-v1
```

The publisher validates the envelope signature, APK signer and hashes,
monotonic sequence/version, and immutable destination files. It writes the
single authenticated control envelope last. The metadata private key must
remain offline and must never be copied to the NAS.

# Shell permission launch-gate MVP

Date: 2026-07-19  
Branch: `fix/enterprise-high-findings`  
Status: Approved for planning

## Goal

Add a cold-start permission launch gate to the KelliKanvas app shell so the user can grant **local network** and optional **ambient sensor** runtime permissions from the TV/phone UI. Internet is shown as already granted at install time (no runtime dialog).

This does **not** by itself fix “image not found.” It only removes an OS-level blocker when local-network access was denied. Image loading failures continue to be diagnosed via SAF / DLNA / adapter `SourceFailure` paths.

## Decisions

| Topic | Choice |
|-------|--------|
| Timing | Cold-start gate every launch until local network is granted; **Not now** skips this process lifetime only |
| Layout | Stacked cards with per-permission Grant actions (TV-focus friendly) |
| Approach | `PermissionCoordinator` + Compose gate in `:app` |
| Internet | Status-only (install-time); no Grant button that pretends to request `INTERNET` |
| Sensors | Optional — gate auto-dismisses when **local network** is granted even if sensors remain denied |
| Local network | Request `ACCESS_LOCAL_NETWORK` when the running SDK requires it; older APIs treat as NotApplicable → granted for gate purposes |
| Sensor perms | Declare and optionally request `ACTIVITY_RECOGNITION` and `BODY_SENSORS` |

## Architecture

- **Module:** `:app` only (no new Gradle module).
- **`PermissionCoordinator`:** Pure-ish helper over `Context` / `PackageManager` / `ContextCompat.checkSelfPermission`. Returns a snapshot of permission rows and whether the gate should show.
- **`ShellPermissionGate`:** Compose UI — stacked cards + Not now.
- **`MainActivity`:** If `shouldShowGate && !sessionSkip` → gate; else existing placeholder shell.
- **Manifest:** Keep `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_LOCAL_NETWORK`. Add `ACTIVITY_RECOGNITION` and `BODY_SENSORS`.

## Components

### Permission rows

1. **Internet** — always display status *Granted at install* (normal permission).
2. **Local network** — runtime when applicable; primary requirement to dismiss the gate.
3. **Activity recognition** — optional presence / step sensors.
4. **Body sensors** — optional heart-rate-class sensors.

### Flow

```
cold start
  → coordinator.snapshot()
  → if localNetwork.granted → shell
  → else if sessionSkip → shell
  → else ShellPermissionGate
       Grant(card) → RequestPermission / RequestMultiplePermissions
       result → resnapshot → maybe shell
       Not now → sessionSkip=true → shell
```

Permanent denial (no rationale): card CTA switches to **Open app settings** (`ACTION_APPLICATION_DETAILS_SETTINGS`).

## Error handling

- Unknown / NotApplicable permission on this API → card shows “Not required on this Android version”; counts as satisfied for gate logic when it is local network.
- Never throw out of the gate UI on permission APIs.
- Sensor denial never blocks leaving the gate.

## Testing

Unit / Robolectric (preferred over emulator for MVP):

- Snapshot: granted / denied / N/A mapping.
- Gate visibility: hidden when local network granted (sensors denied).
- Gate visibility: shown when local network denied.
- Not now sets session skip for the Activity instance.
- Permanent-deny path exposes settings intent (can assert intent construction).

## Out of scope

- Full Settings feature module / ambient binding in dream.
- Automatic Diagnosing of SAF/DLNA “image not found.”
- Declaring or requesting unrelated dangerous permissions.
- Changing QNAP / cleartext policy.

## Follow-up hardenings (same branch, separate task)

- DLNA `secureParser`: disable DOCDECL / external entity features in addition to string bans.

## Success criteria

1. Fresh install on API that enforces local-network permission shows the stacked gate on launch.
2. Granting local network reveals the placeholder shell without restart.
3. Not now reveals shell; relaunch shows the gate again.
4. Internet row never invokes a fake runtime request.
5. Tests cover coordinator + gate visibility rules.

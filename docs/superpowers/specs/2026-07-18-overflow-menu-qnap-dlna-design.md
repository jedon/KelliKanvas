# KelliKanvas Overflow Menu + QNAP DLNA Design

**Date:** 2026-07-18  
**Status:** Approved for implementation  
**Parent:** [2026-07-17-kellikanvas-design.md](./2026-07-17-kellikanvas-design.md)  
**Builds on:** [2026-07-18-full-app-shell-design.md](./2026-07-18-full-app-shell-design.md) (SAF shell)

## Summary

Replace the on-screen Home destination list with a standard Android overflow menu, and add QNAP photo access via DLNA so selected SAF and DLNA folders can coexist in one collection and one simple slideshow.

## Goals

- Home uses a Material3 top app bar with an overflow (⋮) menu for secondary destinations.
- Home body shows collection status and Start/Resume only.
- Collection hub lists selected roots and supports Add local folder (SAF), Add QNAP (DLNA), and remove root.
- DLNA setup supports SSDP discovery plus manual host/IP fallback.
- Persist DLNA connection details; merge SAF + DLNA roots into the existing simple slideshow.

## Non-goals

- Full tri-state multi-source collection browser from the parent design.
- SMB, HTTP/website API setup UI.
- SurfaceView / 4K renderer, portrait pairing, transition suite.
- Full Appearance / Playback / Ambient editors (routes remain placeholders).
- Dedicated DLNA repair UI beyond retry and clear errors (QNAP reindex repair can come later).
- QNAP APK update-host changes.

## Architecture

```
Home (TopAppBar + overflow)
  ├── Start/Resume → Slideshow (multi-adapter playlist)
  ├── Collection hub
  │     ├── Add local folder → existing SAF setup
  │     ├── Add QNAP → Discover | Manual IP → Browse → Select roots
  │     └── Remove root
  ├── Appearance / Playback / Ambient → placeholders
  └── Persistence: selected_roots + saf_connections + dlna_connections
```

### Modules touched

- `app`: Home chrome, nav routes, multi-adapter shell load, playlist merge wiring.
- `feature/setup` (or new collection feature): Collection hub + DLNA setup screens.
- `source/dlna`: existing adapter/discovery; wire into app DI; add manual description resolution if missing.
- `core/catalog`: `dlna_connections` entity + Room migration.

## Home and menu

- Top app bar title: collection label, or `KelliKanvas` when empty.
- Overflow items: Collection, Appearance, Playback, Ambient and System.
- Body: status copy when no roots; enabled Start/Resume when at least one selected root can yield photos after load.
- Collection and placeholder settings screens use a top bar with Up navigation back to Home.
- Do not show Collection / Appearance / Playback / Ambient as on-screen Home rows.

## Collection hub

- List each selected root with display label and source kind (`Local` for SAF, `QNAP`/`DLNA` for DLNA).
- **Add local folder:** existing SAF tree-pick + folder select flow; appends roots without deleting other profiles’ roots.
- **Add QNAP:** starts the DLNA flow below; appends roots.
- **Remove:** deletes that selected root; if a profile has no remaining roots and no other use, profile/connection cleanup is allowed.
- One shared collection continues to hold all roots.

## Add QNAP (DLNA) flow

1. **Discover:** short SSDP window (~3s) with multicast lock only during discovery; list MediaServers by friendly name.
2. **Manual host/IP:** user enters host or IP; resolve device description / ContentDirectory control URL; validate with existing DLNA endpoint policy.
3. User selects a server.
4. Browse folders via paged ContentDirectory (existing `DlnaSourceAdapter`).
5. Select one or more folders; optional include-subfolders.
6. Persist `SourceProfile` (kind DLNA), `dlna_connections` row, and `SelectedRoot` rows.

Failure modes stay on the current step with Retry / edit host; do not wipe existing SAF or other DLNA roots.

## Persistence

| Store | Role |
| --- | --- |
| `selected_roots` | Unchanged multi-profile roots |
| `saf_connections` | Existing tree URI per SAF profile |
| `dlna_connections` | New: `profile_id`, server UDN, description location, control URL, ContentDirectory version, display name |

Room schema migrates forward; existing SAF-only installs keep working.

## Slideshow

- Flatten photos by walking every selected root through its matching `SourceAdapter`.
- Open bytes through that adapter’s `PhotoByteStream`; keep the current simple Compose slideshow controls.
- If one source fails while listing, skip that root’s contribution and continue with others when possible; surface a clear error only when the resulting playlist is empty.

## Testing

- Unit: DLNA connection persistence round-trip; multi-root playlist merge; Home overflow destinations / route smoke as practical.
- Device: overflow → Collection → Add QNAP (discover or manual IP) → select folder → Start slideshow; confirm SAF roots still work when both are present.

## Acceptance

- Overflow menu is the only Home path to Collection and settings placeholders.
- User can add a QNAP/DLNA folder without removing an existing SAF folder.
- Slideshow plays photos from combined roots when both sources are configured and reachable.

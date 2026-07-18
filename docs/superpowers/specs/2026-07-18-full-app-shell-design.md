# KelliKanvas Full App Shell Design

**Date:** 2026-07-18  
**Status:** Approved for implementation  
**Parent:** [2026-07-17-kellikanvas-design.md](./2026-07-17-kellikanvas-design.md)

## Summary

Ship the remaining product surface so the installed app is usable on phone and Google TV: first-run setup for all four sources, Home, settings, and the full slideshow engine. Foundations already exist (models, catalog, preferences, SAF, DLNA library, security, ambient policies, updates). Feature UI modules, HTTP/SMB adapters, image pipeline, and SurfaceView renderer are empty and must be implemented.

## Goals

- Replace the placeholder `MainActivity` with a real navigation graph.
- Complete setup for SAF, DLNA, HTTP, and SMB.
- Persist source profile configuration so adapters recover after process death.
- Provide Home with the five approved destinations and focus restore.
- Implement the slideshow engine: decode pipeline, SurfaceView renderer, layout modes, portrait look-ahead pairing (4), transitions, timing, shuffle, remote/touch controls.
- Wire Appearance, Playback, Ambient, and Collection screens to DataStore/Room.
- Keep the app installable on phone (`leanback` optional) and TV (`LEANBACK_LAUNCHER`).

## Non-goals

- Changing the approved product defaults from the parent design.
- Replacing the QNAP signed-update channel.
- Live QNAP DLNA hardware certification beyond fixture/unit coverage (fix if time allows).
- Play Store distribution.

## Architecture

```
app (MainActivity, Application, NavHost, DI)
  ├── feature/setup        first-run + add-source flows
  ├── feature/collection   multi-folder collection editing
  ├── feature/settings     Appearance / Playback / Ambient / Overlays
  ├── feature/slideshow    session controller + controls overlay
  ├── core/ui-tv           shared TV/phone Compose tokens and focus helpers
  ├── core/image           decode, scale, blur-border, cache
  ├── renderer/surface     4K SurfaceView presenter + transitions
  ├── source/saf|dlna|http|smb
  ├── core/catalog         Room + DataStore (existing)
  └── platform/ambient|update (existing)
```

### Navigation

Routes: `Setup`, `Home`, `Collection`, `Appearance`, `Playback`, `Ambient`, `Slideshow`.

- Cold start with no collection or no selected roots → `Setup`.
- Otherwise → `Home`.
- `Start/Resume Slideshow` → `Slideshow`.
- Back from Home exits the app.
- Back from Slideshow returns to Home without clearing session position.

### Dependency injection

Single process `KelliKanvasApp` builds:

- Room database and preference repository
- Credential vault
- Source adapter registry keyed by `SourceKind`
- Playlist/session coordinator
- Image loader and renderer factory

No third-party DI framework required; manual constructors with interfaces for tests.

## Setup

Follow parent §9.2 wizard steps, shortened only where a source makes a step inapplicable.

| Source | Connect step | Notes |
|--------|--------------|-------|
| SAF | Storage Access Framework tree pick + persistable grant | Phone-first path; TV uses document providers when available |
| DLNA | SSDP discovery → pick MediaServer → ContentDirectory browse | Reuse existing adapter |
| HTTP | Base URL + optional bearer/basic credential in vault | New adapter |
| SMB | Host/share/path + credentials in vault | New adapter |

After folder selection (with optional recursion), persist:

- `SourceProfile` plus adapter-specific config blob/table (URI, UDN/endpoint, URL, SMB target)
- Collection + selected roots + filters
- Mark setup complete by presence of ≥1 selected root (no separate flag required)

## Home

Five focusable destinations (parent §9.1):

1. Start or Resume Slideshow — show collection name and source/folder/photo counts
2. Collection
3. Appearance
4. Playback
5. Ambient and System

Restore `lastHomeControl` from DataStore. Disable Start/Resume when the active collection has zero playable assets.

## Slideshow

Implement parent rendering and control semantics:

- Landscape: fill screen
- Portrait: full photo + blurred background (fill height)
- Portrait pairing: look ahead up to 4 photos for a partner
- Default timing 15s, crossfade 700ms, shuffle cycles, resume on
- Controls: prev/next, pause/resume, transient overlay (auto-hide 5s), photo info, Back behavior per parent §9.3
- Phone: touch equivalents for the same actions

Engine pieces:

1. `PlaylistEngine` — builds ordered cycle from selected roots via adapters; persists session ordinals
2. `core/image` — decode/downsample for display size; produce paired/blurred frames
3. `renderer/surface` — presents frames with transitions on a SurfaceView hosted in Compose

## Settings

Wire existing `AppPreferences` fields to Compose screens under Appearance, Playback, Overlays (if exposed), and Ambient. Validate with existing preference bounds. Ambient screen reflects capability inventory and schedule/sensor modes already modeled.

## Source adapters still missing

### HTTP

- List children from a JSON or directory-style photo API as specified in the parent design (`api` photos).
- Stream bytes over HTTPS/HTTP with size limits and cancellation.
- Store base URL + secrets in vault; never log credentials.

### SMB

- Browse share folders with `smbj`.
- Stream file bytes with cancellation.
- Store host/share/user/password in vault; privacy-safe failures.

Both must pass `AdapterContract` tests.

## Profile persistence gap

Catalog currently stores profile kind/name/status without adapter connection details. Add a stable `SourceConnectionEntity` (or per-kind tables) holding opaque, non-secret config (SAF tree URI string, DLNA UDN/endpoint, HTTP base URL, SMB host/share/path). Secrets remain in the credential vault keyed by `SourceProfileId`.

## Error handling

Reuse `SourceFailure` taxonomy. Setup and slideshow surfaces show privacy-safe messages only. Transient network errors allow retry; revoked SAF grants route to repair.

## Testing

- Unit: playlist ordering/pairing, preference wiring, HTTP/SMB contract suites
- Robolectric/instrumentation: SAF setup path, navigation first-run gate
- Manual: S21 Ultra Wi-Fi ADB and Hisense Canvas TV sideload smoke

## Delivery

Parallel workstreams, then integration:

1. HTTP + SMB adapters + connection persistence
2. Nav shell + Home + SAF setup
3. Image pipeline + SurfaceView + slideshow controls
4. DLNA/HTTP/SMB setup UI + Collection/Settings/Ambient screens
5. App integration, debug install to phone, release APK republish if requested

## Acceptance

- Fresh install opens Setup; completing SAF (and each other source) reaches Home
- Home starts a slideshow that advances, pauses, and navigates
- Landscape fill and portrait blur+pair behave per parent defaults
- Preferences changes affect subsequent playback
- App runs on phone and TV launchers
- No credentials or URIs in logs/diagnostics

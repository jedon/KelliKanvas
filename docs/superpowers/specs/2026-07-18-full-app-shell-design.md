# KelliKanvas SAF Shell Design

**Date:** 2026-07-18  
**Status:** Approved for implementation  
**Parent:** [2026-07-17-kellikanvas-design.md](./2026-07-17-kellikanvas-design.md)  
**Supersedes scope of:** earlier full-app draft in this filename’s history — this document is the active scope.

## Summary

Ship a first usable vertical slice on phone (and TV launcher-compatible): SAF folder pick → Home → simple Compose slideshow. Defer DLNA/HTTP/SMB setup UI, SurfaceView renderer, portrait pairing, transition suite, and full settings taxonomy.

## Goals

- Replace placeholder `MainActivity` with Setup → Home → Slideshow navigation.
- First-run SAF tree pick, persist grant, select one or more folders (optional recursion).
- Persist collection + selected roots + SAF connection details in Room.
- Home with Start/Resume and stub entries for Collection / Appearance / Playback / Ambient.
- Simple slideshow: timed advance, pause/resume, prev/next, basic fit/fill of the current photo.
- Installable on phone and TV (`leanback` optional, both launcher categories).

## Non-goals

- HTTP, SMB, or DLNA setup UI in this slice (adapters may remain unused).
- SurfaceView / 4K renderer, portrait pairing, crossfade suite.
- Full Appearance/Playback/Ambient editors (show destinations; deep screens can be placeholders).
- QNAP update-channel changes.

## Architecture

```
app (Application, NavHost, DI)
  ├── feature/setup      SAF-only first-run
  ├── feature/home       five destinations (Start live; others stub or minimal)
  ├── feature/slideshow  Compose image pager + timer + controls
  ├── core/ui-tv         shared focus/spacing helpers as needed
  ├── source/saf         existing adapter
  └── core/catalog       Room + DataStore (extend for SAF connection)
```

### Navigation

- No selected roots → `Setup`
- Else → `Home`
- Start/Resume → `Slideshow`
- Back from Slideshow → Home (keep session ordinal)
- Back from Home → exit app

### DI

Manual `KelliKanvasApp` graph: database, preferences, SAF adapter factory, playlist lister for the active collection.

## Setup (SAF)

1. Welcome
2. Launch SAF tree picker (`SafTreePickerContract`) and take persistable permission
3. Browse children via `SafSourceAdapter`; user selects folder roots; optional “include subfolders”
4. Write `SourceProfile` + SAF tree URI connection row, collection, selected roots
5. Navigate to Home

## Home

Five focusable rows:

1. Start or Resume Slideshow — enabled when ≥1 photo can be listed; show collection label and rough counts when cheap to compute
2. Collection — placeholder (“Coming next”)
3. Appearance — placeholder
4. Playback — placeholder
5. Ambient and System — placeholder

Restore `lastHomeControl` when present.

## Simple slideshow

- Build a flat photo list by walking selected roots through SAF (respect recursion flag).
- Show one image at a time with Coil/Compose or BitmapFactory downsample.
- Default interval from `AppPreferences.slideDuration` (15s default) or hard-default 15s if wiring prefs is costly.
- Controls: pause/resume, previous, next; touch and D-pad.
- Layout: center-crop fill for landscape; for portrait, fit height centered on dark background (no blur pairing yet).
- Persist last ordinal in slideshow session table when practical; otherwise in-memory resume for the process lifetime is acceptable for this slice.

## Persistence gap (minimal)

Add SAF connection storage keyed by `SourceProfileId` (tree URI string). Secrets not applicable for SAF.

## Error handling

Privacy-safe messages via `SourceFailure`. Revoked grant → return to Setup repair / re-pick.

## Testing

- Unit: first-run gate (has roots or not), photo list flattening with recursion flag
- Robolectric/instrumentation where SAF test fakes already exist
- Manual: S21 Ultra Wi-Fi ADB — pick folder → Home → play

## Acceptance

- Fresh install opens SAF setup and can grant a folder
- Home Start opens a slideshow that advances and pauses
- App runs on phone launcher
- No URIs/credentials in log messages

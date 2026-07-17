# KelliKanvas Product and Technical Design

**Status:** Approved design  
**Date:** 2026-07-17  
**Target:** Google TV, with optional Hisense CanvasTV enhancements  
**Distribution:** Privately sideloaded APK  
**License:** MIT

## 1. Product Summary

KelliKanvas is a native Kotlin photo-viewer application for a 16:9 4K Google TV. It turns the television into a remote-controlled digital canvas for family photography stored on a QNAP NAS, a controlled website, and removable storage.

The first release supports all four requested source types:

1. DLNA/UPnP MediaServer, primarily a QNAP NAS.
2. SMB 2/3 shares, primarily a QNAP NAS.
3. USB and system document providers through Android's Storage Access Framework.
4. A stable HTTPS JSON API controlled by the household.

The first release displays still photos only. It supports multiple selected folders across multiple sources, optional recursive traversal, native-4K rendering, automatic portrait pairing, extensive layout and playback settings, and capability-gated ambient-light and presence behavior.

## 2. Goals

- Render photographs at a native 3840 x 2160 surface when the Google TV device exposes a compatible display mode.
- Preserve a simple, remote-first ten-foot experience using only D-pad, Select, and Back.
- Browse and combine folders from every supported source without exposing protocol details during playback.
- Stream photographs without maintaining a persistent full-resolution offline library.
- Provide predictable shuffle, ordering, timing, layout, transition, and overlay controls.
- Pair portrait photographs automatically without destroying slideshow order.
- Use CanvasTV ambient-light and presence capabilities when Android exposes them.
- Degrade honestly and safely when Hisense hardware features or privileged power controls are unavailable.
- Keep credentials private and treat every LAN endpoint as untrusted.

## 3. Non-Goals for the First Release

- Videos, animated GIFs, motion photos, or audio.
- Multiple household profiles or separate per-user accounts.
- A phone companion or phone-required setup.
- Generic scraping of arbitrary HTML or JavaScript-heavy websites.
- Persistent full-resolution offline collections.
- Public Google Play distribution.
- Root access, hidden Android APIs, reverse-engineered Hisense commands, or firmware modification.
- Guaranteed physical TV power-on or power-off from an ordinary Android application.
- Claiming native-4K or Hisense sensor certification before testing compatible physical hardware.

## 4. Product Decisions

### 4.1 Source scope

All four source adapters are part of the first release. They may be delivered through internal milestones, but the release is not considered feature-complete until DLNA, SMB, USB/SAF, and the website API pass their acceptance tests.

### 4.2 Image layout

- Landscape images use **Fill Screen** by default: preserve aspect ratio, center the image, and crop overflow to fill 16:9.
- A single portrait image uses **Full Height with Blurred Background** by default: fit the complete image from top to bottom and fill the side regions with a dimmed, blurred copy.
- Every layout remains user-configurable. Available modes are Full Photo, Fill Screen, Blurred Border, Solid Background, and Stretch to Screen. Stretch displays a distortion warning.

### 4.3 Portrait pairing

When automatic pairing is enabled and the current item is portrait:

1. Search forward through at most the next four playlist positions.
2. Select the first portrait candidate found.
3. Do not reorder the intervening landscape items.
4. Mark the paired portrait as consumed so it does not appear again in the same cycle.
5. Render both portraits as one slide with a restrained center gutter and shared background.
6. If no candidate exists, render the current portrait alone using the portrait default.

Pairing can be Off, Auto, or Always. Auto is the default. A pair consumes one configured slide duration and one transition.

### 4.4 Startup and caching

Opening the app resumes the prior slideshow session when its sources remain available. The app does not attempt to launch itself after TV boot.

There is no persistent full-photo cache. Smooth playback requires a disposable buffer for the current and next compressed image and decoded frame. Temporary data is deleted when no longer needed and is not presented as offline availability. Room stores metadata, source status, selections, and playlist progress only.

### 4.5 Device scope

The baseline app supports compatible Google TV devices. CanvasTV-specific features are enabled only after runtime capability detection.

## 5. Architecture

KelliKanvas uses a hybrid presentation architecture:

- Compose for TV provides setup, browsing, settings, diagnostics, focus handling, and slideshow overlays.
- A dedicated full-screen `SurfaceView` owns photograph rendering.
- The renderer requests a buffer matching the selected physical display mode and verifies the resulting surface dimensions.
- Source adapters implement a shared contract and do not know about the UI or renderer.
- A playlist engine combines selected roots into one deterministic stream.
- Room is the local source of truth for configuration and metadata.
- DataStore stores non-secret preferences.
- Android Keystore protects source credentials.

### 5.1 Proposed module boundaries

- `app`: dependency wiring, navigation, lifecycle, and TV entry points.
- `core:model`: source-neutral identifiers, media metadata, settings, and error types.
- `core:source-api`: discovery, hierarchy browsing, and byte-stream contracts.
- `core:catalog`: Room entities, selections, indexing state, and playlist restoration.
- `core:security`: Keystore encryption and credential lifecycle.
- `core:image`: metadata extraction, target-size decode, orientation, color handling, and disposable preloading.
- `source:dlna`: SSDP, device descriptions, ContentDirectory browsing, and HTTP resources.
- `source:smb`: SMB 2/3 connection, share browsing, authentication, and streams.
- `source:saf`: persisted document-tree permissions and USB/document-provider browsing.
- `source:http`: website API client and HTTPS photo streams.
- `feature:setup`: first-run setup and source connection.
- `feature:collection`: multi-source folder tree and selection summary.
- `feature:settings`: appearance, playback, ambient, accessibility, and diagnostics.
- `feature:slideshow`: playlist state machine, controls, retries, and session restoration.
- `renderer:surface`: native-resolution surface, frame composition, backgrounds, pairing, and transitions.
- `platform:ambient`: sensor discovery, brightness policy, presence policy, schedules, and capability reporting.

Each module exposes interfaces and immutable models rather than protocol-specific objects.

### 5.2 Source contract

Every adapter exposes these logical operations:

- Discover or configure a source.
- Test connectivity and authentication.
- List children of a folder with pagination.
- Read stable metadata for a photo.
- Open a cancellable photo byte stream.
- Report supported capabilities and normalized errors.

An asset reference contains a source profile ID, stable provider object ID, MIME type, byte length when known, modified time or ETag, and provider version token. Credentials are never embedded in references or cache keys.

Normalized errors include AuthenticationRequired, PermissionRevoked, SourceUnavailable, NotFound, UnsupportedFormat, CorruptContent, Timeout, and ProtocolFailure.

## 6. Source Designs

### 6.1 DLNA/UPnP

- Discover MediaServer devices with short-lived SSDP `M-SEARCH`.
- Hold a Wi-Fi multicast lock only during discovery.
- Parse device descriptions and locate the highest compatible ContentDirectory service.
- Issue paged SOAP `Browse` calls and parse bounded DIDL-Lite XML.
- Select an image resource by supported MIME type, dimensions, and size.
- Fetch the selected resource through the shared HTTP transport.
- Store the server UDN and object ID; provide a repair flow when a QNAP reindex changes object IDs.
- Enforce XML size, depth, timeout, and entity-expansion limits.

The production implementation uses a focused control point built with OkHttp and Android XML parsing. It does not depend on the unmaintained Cling release.

### 6.2 SMB

- Use SMBJ after an Android/R8 compatibility spike.
- Require SMB 2.1 or newer and never silently fall back to SMB1.
- Support manual hostname/IP, port, share, username, password, and optional domain.
- Offer mDNS discovery when the NAS advertises `_smb._tcp`; manual setup always remains available.
- Prefer SMB signing and SMB3 encryption when supported by the NAS.
- Close streams and sessions promptly after cancellation, network loss, or source removal.

### 6.3 USB and Storage Access Framework

- Launch `ACTION_OPEN_DOCUMENT_TREE`.
- Persist read permission for the chosen tree.
- Browse through `DocumentsContract` or `DocumentFile`.
- Do not request broad storage or `MANAGE_EXTERNAL_STORAGE`.
- Detect revoked grants and removed media, mark the source as needing repair, and retain the user's collection selection for reconnection.

### 6.4 Website API

The app consumes a controlled versioned HTTPS API rather than scraping HTML.

The API contract is:

- `GET /api/kellikanvas/v1/folders?parentId=<id>&cursor=<cursor>`
- `GET /api/kellikanvas/v1/photos?folderId=<id>&cursor=<cursor>`

Folder responses contain stable ID, parent ID, display name, child-folder presence, and pagination cursor.

Photo responses contain stable ID, folder ID, content URL, optional thumbnail URL, MIME type, width, height, capture time, modified time, byte length when known, and ETag/version.

The API supports bearer-token authentication when enabled. HTTPS is mandatory for non-LAN endpoints. Cross-host redirects never forward credentials.

## 7. Collection and Playlist Model

A collection contains one or more selected roots. Each root stores:

- Source profile ID.
- Provider folder/object ID.
- Display label.
- Include-descendants flag.
- Optional file-type filters.

The collection browser uses checked, unchecked, and partially selected folder states. Selecting a parent does not duplicate explicitly selected children. Users can combine folders from all sources and see a running folder/subfolder/photo count.

The playlist engine:

- Lazily merges paged source iterators.
- Prevents recursion loops and enforces configurable safety limits.
- Deduplicates by source profile plus canonical provider object ID.
- Supports cycle-based shuffle, name order, capture-date ascending/descending, and modified-date ascending/descending.
- Persists shuffle seed, cycle identity, current position, consumed portrait partners, and current asset.
- Ensures every eligible item appears once before starting a new shuffle cycle.
- Does not inject newly discovered photos into the middle of an active cycle; they join the next cycle.

## 8. Rendering and Image Pipeline

### 8.1 Native 4K

The renderer queries compatible display modes and selects 3840 x 2160 when available. It sets the `SurfaceView` buffer to the physical mode and reports both requested and actual surface dimensions in diagnostics.

A 3840 x 2160 ARGB frame uses about 31.6 MiB. The pipeline therefore:

- Decodes to the target surface dimensions, never original camera dimensions when larger.
- Holds no more than the current and next decoded frame.
- Cancels obsolete work immediately during rapid navigation.
- Drops preload and decoded data on memory-pressure callbacks.
- Uses fade-through-black when the device cannot safely retain two full frames.
- Does not rely on `largeHeap`.

### 8.2 Metadata and color

- Apply all eight EXIF orientation and mirroring values.
- Treat missing capture dates as unknown rather than filesystem time.
- Strip GPS from logs and diagnostics.
- Use color-correct SDR/sRGB as the baseline.
- Preserve Display P3 only when the display and window support wide color and memory policy permits it.
- Treat CMYK JPEG, unsupported RAW, corrupt content, and unsupported HDR as normalized unsupported/corrupt errors rather than renderer crashes.

### 8.3 Transitions

The initial transition set is:

- Cut.
- Crossfade.
- Fade through black.
- Slide left/right.
- Slow pan and zoom.
- Random from an enabled subset.

Transition duration is configurable independently from slide duration. Reduced-motion mode forces Cut or Crossfade and disables pan/zoom and slide movement.

## 9. User Experience

### 9.1 Home

Home contains five destinations:

1. Start or Resume Slideshow.
2. Collection.
3. Appearance.
4. Playback.
5. Ambient and System.

The current collection name, source count, folder count, and known photo count appear beneath Start/Resume. Focus returns to the last-used control.

### 9.2 First-run setup

1. Welcome and remote-control explanation.
2. Add one or more sources.
3. Connect or grant source access.
4. Select one or more folders and recursion behavior.
5. Preview landscape, portrait, and portrait-pair layouts.
6. Confirm settings and start.

Setup is resumable and does not require changing every default.

### 9.3 Remote behavior

During playback:

- Left/Right: previous/next slide.
- Select: pause/resume.
- Up: transient playback controls.
- Down: photo information.
- Back: close a panel; otherwise return Home while preserving position.

Controls disappear after five seconds unless TalkBack is active. Back from Home exits normally.

### 9.4 Accessibility

- Complete D-pad operation with no touch, swipe, hover, or long-press requirement.
- Highly visible focus ring and at least 64 dp focus targets.
- Body text approximately 22-24 sp and headings approximately 30-36 sp.
- TalkBack labels for all controls and folder selection states.
- System text-scale support.
- Reduced-motion support.
- No state communicated by color alone.

## 10. Settings and Defaults

### Collection

- Include subfolders: On.
- Deduplicate: On.
- Refresh: On app start and manual.
- Unsupported files: Skip and report.

### Appearance

- Landscape: Fill Screen.
- Single portrait: Full Height with dim adaptive blur.
- Portrait pairing: Auto, look ahead at most four items.
- Pair gutter: 24 dp.
- Blur strength: Medium.
- Blur dimming: 35 percent.
- Stretch: Off.

### Playback

- Slide duration: 15 seconds.
- Transition: Crossfade.
- Transition duration: 700 ms.
- Order: Cycle-based shuffle.
- Loop: On.
- Resume session: On.
- New photos: Next cycle.

### Overlays

- Metadata: Off.
- Clock: Off.
- Capture date: Off.
- Filename: Off.
- Controls: Transient.

### Ambient

- Follow TV brightness unless a supported light sensor is enabled.
- Presence behavior: Off until explicitly enabled.
- Day/night schedule: Available and off by default.
- Automatic wake: Off unless a verified capability permits it.
- TV-managed sleep: Enabled as the fallback.

## 11. Ambient Light, Presence, Sleep, and Wake

Hisense advertises an RGB light sensor and motion detector on CanvasTV models, but no public consumer API guarantees third-party access. Generic Android motion sensors usually describe movement of the device itself, not a person in the room.

The app inventories all sensors at runtime and records type, string type, vendor, wake-up status, reporting mode, and required permission. No sensor is declared required in the manifest.

### 11.1 Brightness

When a usable ambient-light sensor exists:

- Map smoothed lux readings to a configurable minimum/maximum window brightness.
- Apply hysteresis and a minimum update interval to prevent visible oscillation.
- Allow users to calibrate low-light and bright-room points.
- Restore follow-TV behavior when the feature is disabled.

The baseline uses per-window brightness. Global TV backlight or picture-mode control is not promised.

When no usable sensor exists, configurable day/night schedules may select brightness presets while Google TV remains responsible for system display policy.

### 11.2 Presence

Presence automation is shown only after the capability probe observes a sensor whose events correlate with room presence. If supported, the user configures inactivity duration and whether a detected return should resume the slideshow.

When unsupported, the setting explains that CanvasTV presence data is unavailable to third-party apps. The app relies on TV-managed Ambient Mode/Energy Saver and the configured day/night schedule.

### 11.3 Power limitations

A normal sideloaded Android app cannot call privileged `goToSleep`, `wakeUp`, or HDMI-CEC APIs. KelliKanvas does not use hidden APIs or request device-owner provisioning.

`setTurnScreenOn` or a wake-up sensor may be used only when verified on the device and permitted by Android background-launch rules. A DreamService may be offered when the TV exposes third-party screensaver selection. Neither is treated as universal.

## 12. Error Handling and Recovery

- Keep the current photo visible while loading its successor.
- Retry a transient load once with jitter, then skip and record the reason.
- Continue using healthy sources when another disconnects.
- Back off rediscovery attempts instead of polling continuously.
- Preserve selections when USB permission, SMB credentials, DLNA object IDs, or website tokens require repair.
- Show reconnect, reauthorize, rescan, edit source, and choose another collection actions.
- If every item fails, show a stable recovery screen instead of black output.
- Persist the last playable item and playlist state after process death.
- Never allow malformed metadata, XML, JSON, paths, or image dimensions to allocate unbounded memory.

## 13. Security and Privacy

- Encrypt SMB passwords and website tokens with AES-GCM using an Android Keystore-held key.
- Exclude ciphertext and keys from backup.
- Delete unusable ciphertext after key invalidation and request credentials again.
- Require HTTPS for internet sources.
- Permit cleartext DLNA HTTP only for validated local/discovered endpoints.
- Do not send credentials across cross-host redirects.
- Reject unexpected URI schemes and public-to-private redirect chains.
- Never log credentials, authenticated URLs, filenames unless diagnostics explicitly include them, EXIF GPS, or full provider payloads.
- Keep diagnostics local and privacy-safe.
- Use SMB 2.1+ and prefer signing/encryption.
- Publish open-source notices and dependency licenses.

## 14. Diagnostics

The local diagnostics screen reports:

- App and Android versions.
- Current physical display mode and actual SurfaceView dimensions.
- Java/native/graphics memory summary.
- Available sensors and ambient/presence eligibility.
- Brightness and power capability status.
- Source connectivity and last successful refresh.
- Current collection and item counts.
- Skipped-item counts grouped by normalized error.
- Rescan, reconnect, reauthorize, and clear temporary data actions.

Diagnostics must make it clear when a requirement is implemented but cannot be verified on current hardware.

## 15. Testing Strategy

### Automated

- Shared source-adapter contract tests for paging, cancellation, stable IDs, recursion, metadata, and errors.
- Synthetic SSDP responder plus bounded SOAP/DIDL fixtures.
- Samba containers covering SMB 2.1/3, signing, encryption, bad credentials, and disconnects.
- Test DocumentsProvider fixtures for SAF grants, revocation, and media removal.
- MockWebServer fixtures for pagination, authentication, redirects, ETags, slow responses, and truncated files.
- Image corpus covering all EXIF orientations, large panoramas, malformed dimensions, CMYK JPEG, wide color, HEIF/AVIF where platform-supported, and corrupt content.
- Playlist tests for deterministic cycles, restoration, deduplication, and portrait look-ahead up to four positions.
- Compose UI tests for five-way D-pad operation, focus restoration, Back behavior, TalkBack semantics, and reduced motion.
- Renderer tests for geometry, crop/fit rules, pairing, transitions, cancellation, and low-memory fallback.

### Physical-device acceptance

- Verify the SurfaceView with a native 4K line/grid pattern; screenshots alone are insufficient.
- Run 8-hour and 24-hour slideshow soak tests while monitoring Java, native, and graphics memory.
- Validate QNAP DLNA paging and resource selection.
- Validate SMBJ against the QNAP's SMB configuration.
- Remove and reinsert USB media during playback.
- Test Wi-Fi/Ethernet loss and reconnection.
- Correlate sensor events with controlled lighting and room motion.
- Verify per-window brightness visually.
- Test system sleep, app resume, DreamService visibility, and any permitted wake behavior independently.

Because the actual CanvasTV is not currently available, physical-device acceptance remains a release qualification activity. The implementation must not claim unsupported sensor or power behavior before this testing.

## 16. Delivery Milestones

1. Repository and Android TV foundation, CI, module skeleton, and capability diagnostics.
2. Core models, source contract, Room catalog, secure credentials, and shared adapter tests.
3. USB/SAF and website API adapters.
4. SMB adapter and QNAP compatibility path.
5. DLNA discovery, ContentDirectory browsing, and QNAP compatibility path.
6. Multi-source collection browser, recursive selection, deduplication, and restoration.
7. Playlist engine, portrait look-ahead, disposable loading pipeline, and native-4K renderer.
8. Slideshow controls, layouts, transitions, overlays, and accessibility.
9. Ambient-light/presence capability integration and schedule/system fallback.
10. Failure recovery, diagnostics, memory/performance hardening, soak tests, and sideload packaging.

All milestones are required for the first feature-complete release; numbering defines implementation order, not separate public releases.

## 17. Acceptance Criteria

- A D-pad-only user can connect each source, select multiple folders, enable recursion, and start a slideshow.
- A collection can combine folders from DLNA, SMB, USB/SAF, and the website API.
- Landscape and portrait defaults match the approved layout rules.
- Automatic pairing finds the next portrait no more than four playlist positions ahead and does not replay the paired item in the cycle.
- Native 3840 x 2160 output is selected and reported when supported; unsupported devices explain their fallback.
- The slideshow completes shuffle cycles without repeats and restores its position after app restart.
- Source loss, bad credentials, corrupt photos, and permission revocation produce recovery actions rather than crashes or black output.
- No persistent full-resolution photo library is created.
- Ambient and presence controls appear only when supported, with TV-managed sleep and schedules as the fallback.
- The app uses no hidden, root-only, device-owner, or privileged power APIs.
- Automated tests pass and physical-device qualifications are documented before native-4K or CanvasTV sensor support is declared verified.

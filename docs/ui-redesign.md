# KelliKanvas UI redesign

Research and implementation reviewed on October 3, 2026. KelliKanvas is a native Android photo viewer and screensaver for Hisense CanvasTV and other Android TV devices. The redesign gives the app a consistent gallery interface, makes remote navigation visible, and separates the viewing experience from configuration.

## Research scope

This survey covers the main art TV platforms, direct Android photo/screensaver competitors, and adjacent display products. It is a broad survey of relevant competitors, not a claim to identify every regional or discontinued product. Evidence comes from official product documentation, published screenshots, and project documentation. Paid accounts and physical competitor devices were not tested. Product capabilities may vary by model, firmware, region, and subscription.

## Art TV platforms

- **Hisense CanvasTV Art Mode** is the target device's built-in alternative. It presents curated artwork and personal photographs, with direct access through the remote. KelliKanvas should make starting a personal gallery equally obvious. [Hisense product announcement](https://files.hisense-usa.com/download/f276698399fc7435).
- **Samsung The Frame and Art Store** combine artwork browsing, uploaded photos, slideshows, matte styling, brightness, and sensor settings. The useful design pattern is a recognizable collection with playback and presentation controls nearby. [Samsung Art Mode guide](https://www.samsung.com/us/support/answer/ANS10005239/).
- **TCL NXTFRAME** uses a nature-inspired visual system and art-first presentation. Its published UX materials describe seasonal colors, dynamic artwork, art guidance, and background music. Its product announcement also describes personal photo galleries and adjustable matting. The lesson adopted here is restrained color and generous space around the artwork. [TCL UX design](https://www.tcl.com/global/en/tcl-design/tcl-design-awards/tcl-nxtframe), [TCL product announcement](https://us.tcl.com/blogs/press-releases/tcl-unveils-the-future-of-digital-art-for-the-home-nxtframe-tv).
- **LG Gallery+** presents an art and ambient content service with artwork, personal imagery, and customization. Its imagery prioritizes the displayed content and its relationship to the room. KelliKanvas uses an understated dark surround and a framed preview. [LG Gallery+](https://www.lg.com/uk/tvs-soundbars/webos-lg-gallery-plus/).

## Photo and screensaver applications

- **nFolio** is the closest direct functional competitor: SMB and DLNA access, animated slideshows, metadata, time/weather, music, and screensaver support on compatible devices. Its explicit source choices support KelliKanvas's separation of local storage, household NAS, and media servers. [nFolio](https://www.snapwoodapps.com/nfolio-for-network-dlna-smb-photos/).
- **PixFolio** focuses on Google Photos galleries and TV slideshows. The broader Snapwood family includes **dFolio**, **gFolio**, **SkyFolio**, and **flickFolio** for other providers. Album selection, duration controls, animations, and automatic playback are relevant benchmarks. Cloud integrations are outside this UI rewrite. [PixFolio](https://snapwoodapps.com/pfolio-for-google-photos/), [Snapwood applications](https://www.snapwoodapps.com/).
- **Fotoo** supports local, cloud, and SMB sources, transition effects, music, scheduled playback, and photo information overlays. Its published store imagery places the photo first and keeps secondary information at the bottom. KelliKanvas now gives playback a small temporary overlay. [Fotoo official store listing](https://play.google.com/store/apps/details?id=com.bo.fotoo).
- **Photo Gallery and Screensaver** is an Android TV gallery/screensaver with local and online sources. Its official site was intermittently unavailable during research; search indexing confirmed its basic role, but detailed current feature parity was not assumed. Clear device screensaver setup is the relevant baseline. [Official site](https://photoscreensaver.furnaghan.com/).
- **Aerial Views** and **Aerial Dream** benchmark full-screen ambient viewing and Android screensaver integration. Aerial Views documents local/network sources, display overlays, and device-specific setup. KelliKanvas keeps its existing panel-sized photo renderer and introduces controls only when needed. [Aerial Views](https://github.com/theothernt/AerialViews), [Aerial Dream](https://github.com/cachapa/AerialDream).
- **Google TV Ambient Mode** supports Google Photos, an art gallery, and optional information such as weather/time. Its useful pattern is choosing content once and allowing the screen to become quiet. [Google TV screensaver guide](https://support.google.com/googletv/answer/10070821?hl=en).
- **Apple TV Photos and Memories** support personal/shared albums and curated photo screensavers. The benchmark is a direct route from a personal collection to viewing. [Apple photo screensaver guide](https://support.apple.com/guide/tv/use-your-photos-as-a-screen-saver-atvb545e0f30/tvos).

## Adjacent display products

- **Artcast** offers curated galleries, playlists, configurable display durations, mattes, music, and video art. Its distinction between browsing and display informs the gallery home and separate appearance/playback settings. [Artcast home use](https://artcast.tv/home-use/), [Artcast feature update](https://artcast.tv/2025/02/22/new-streaming-art-features/).
- **Loupe** offers contemporary streaming art channels and a business display service. It benchmarks a calm gallery experience appropriate to homes, offices, and hospitality. KelliKanvas does not claim licensed art or fleet management. [Loupe](https://loupeart.com/).
- **DAKboard** combines photos with other information on a customizable display. Its useful lesson is clarity about source selection; dashboard widgets would distract from KelliKanvas's central viewing purpose. [DAKboard](https://dakboard.com/site).
- **ImmichFrame** is an adjacent self-hosted photo display project requiring an Immich library and separate service. It demonstrates interest in personal libraries without depending on a commercial art catalog. Its server integration is a potential future source, not part of this rewrite. [ImmichFrame overview](https://immichframe.dev/docs/overview).

The design judgments above are KelliKanvas-specific interpretations of those sources, not claims that every competitor uses the same layout.

## Implemented interface

- A shared dark charcoal and sage design system replaces the mixed white mobile and default TV themes. Typography, content colors, corner shapes, spacing, and remote focus outlines are consistent across screens.
- TV Home has a persistent labeled navigation rail, a prominent slideshow action, framed collection preview, truthful connection status, and shortcuts to collection, appearance, and ambient settings.
- The preview uses existing source adapters. Discovery is limited to 12 listing requests, three candidate photos, and three descendant levels; the operation has a 12-second timeout and decodes one image to at most 960 pixels. Errors do not block Home. An original vector landscape is clearly labeled as inspiration when no photo preview is available.
- Collections show selected folders, their source, and whether subfolders are included. Local/USB, household NAS, and DLNA connections have distinct entry points. Removing a folder requires an in-app confirmation; original photos are retained.
- TV settings use a contextual side panel and consistent rows with values, switches, and remote-adjustable steppers. Informational rows remain focusable so long pages can scroll. Phone controls stack when space is limited.
- Local folder, SMB, and DLNA setup use the same heading and back navigation. First-run permissions share the gallery identity and explain optional ambient access.
- Phone Home uses visible Home, Photos, and Settings navigation. Swiping between hidden menu pages is no longer necessary.
- Slideshow controls show playback state, photo position, and navigation instructions, then hide while playback continues. Paused playback keeps its status visible.

## Existing functionality boundaries

This change redesigns the UI and adds bounded collection previews and a playback HUD. It retains the existing catalog, source adapters, preferences, update verification, screensaver integration, and photo renderer. It does not add cloud accounts, subscriptions, art licensing, fleet administration, favorites, or new rendering effects.

Several existing appearance and playback preferences have UI controls but the simple slideshow renderer does not consume every preference. A visual preview on Home is a collection sample, not a simulation of all appearance settings. Hardware light/presence support and Hisense firmware screensaver behavior still require device validation.

## Validation

The UI regression suite renders native Android screenshots with Robolectric at TV and phone sizes. It checks initial focus, remote slideshow activation, collection navigation, cancellation/confirmation of removal, TV duration adjustments, and phone decrement/increment controls. Preview tests cover request limits, repeated cursors, per-folder filters, unavailable folders, and cancellation. Screenshot files are generated under `app/build/reports/ui-redesign` and inspected separately from test assertions.

The installed Android emulator had no usable system image, and no physical Android TV was connected. Native rendering and automated remote tests validate the interface locally; physical Hisense behavior remains unverified.

Final repository validation passed: `:build-logic:test`, `ktlintCheck`, `lintDebug`, `testDebugUnitTest`, `assembleDebug`, and `assembleDebugAndroidTest`. The log is saved at `build/ui-redesign-verification.log`. All 13 new UI and preview tests passed, and nine native screenshots were visually inspected. The installable debug build is saved at `dist/KelliKanvas-ui-redesign-debug.apk`.

An initial run encountered a Windows file access failure in the existing updater's atomic APK replacement test. That test passed on retry without an updater code change. A shared Gradle cache file lock also required running final validation with build caching disabled.

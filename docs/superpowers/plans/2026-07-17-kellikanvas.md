# KelliKanvas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a privately distributed native Kotlin Google TV application that streams still photographs from DLNA, SMB, USB/SAF, and a controlled website API into a configurable native-4K slideshow with capability-gated CanvasTV ambient behavior.

**Architecture:** Compose for TV provides setup, browsing, settings, diagnostics, and overlays. Protocol-specific adapters implement one source contract, a metadata-only Room catalog builds immutable playlist cycles, and a bounded image pipeline renders through a native-resolution `SurfaceView`. Ambient and update behavior use pure policies behind Android adapters so unsupported hardware and private distribution degrade safely.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.2.0, Gradle 9.4.1, JDK 17, compile/target SDK 37, min SDK 28, Compose BOM 2026.06.00, Compose for TV 1.1.0, coroutines, Room 2.8.4, DataStore 1.2.1, OkHttp 5, SMBJ 0.14.0, AndroidX ExifInterface, Android Keystore, JUnit, Robolectric, Compose UI tests, MockWebServer, Testcontainers/Samba, GitHub Actions, nginx on QNAP.

---

## Fixed implementation decisions

- Application ID and package root: `com.jedon.kellikanvas`.
- `compileSdk = 37`, `targetSdk = 37`, `minSdk = 28`.
- Android 17 LAN access uses the runtime `ACCESS_LOCAL_NETWORK` permission.
- The app is sideloaded and updated with explicit Android package-installer confirmation.
- QNAP delivery URL: `http://darklingnas:8088`; files are backed by `\\DarklingNAS\Public\KelliKanvas`.
- No persistent full-resolution photo cache. Only current/next disposable image data is allowed under `cacheDir`.
- No boot receiver, root, device-owner provisioning, hidden APIs, silent installs, or privileged power/CEC calls.
- Native 4K and CanvasTV sensors remain `UNVERIFIED` until physical-device qualification.

## Module map

- `app`: TV manifest, application graph, navigation, home, lifecycle, DreamService.
- `core:model`: immutable identifiers, settings, source-neutral models, normalized errors.
- `core:source-api`: adapter and stream contracts plus shared contract-test fixtures.
- `core:catalog`: Room catalog/session data and DataStore preferences.
- `core:security`: Android Keystore credential vault.
- `core:image`: disposable staging, metadata, decode, and bounded frame slots.
- `core:ui-tv`: TV design tokens, focus primitives, shared controls.
- `core:testing`: coroutine/test fakes and rules.
- `source:saf`, `source:http`, `source:smb`, `source:dlna`: protocol adapters.
- `feature:setup`, `feature:collection`, `feature:settings`, `feature:slideshow`: user-facing features.
- `renderer:surface`: display-mode selection, layout geometry, transitions, render loop.
- `platform:ambient`: sensor inventory, brightness, presence, schedule, capability reports.
- `platform:update`: manifest checking, APK validation, and user-confirmed installation.

## Parallel execution waves

After Task 2 establishes the shared contracts, these lanes can run concurrently in isolated worktrees:

- Source lane: Tasks 7-10.
- Playback lane: Tasks 11-14.
- Ambient lane: Task 15.
- UI lane: Tasks 16-18 after Task 6.
- Distribution lane: Task 19.

Tasks 20-22 integrate the lanes and therefore run after their dependencies merge.

---

### Task 1: Bootstrap the Android toolchain and project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Generate: `gradle/wrapper/gradle-wrapper.jar`
- Generate: `gradlew`
- Generate: `gradlew.bat`
- Create: `.editorconfig`
- Modify: `.gitignore`
- Create: `docs/development.md`

- [ ] **Step 1: Install or locate JDK 17 and Android SDK 37**

Document these required environment variables in `docs/development.md`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH += ";$env:ANDROID_HOME\platform-tools"
```

The current machine has JDK 25 but no Android SDK, ADB, or Gradle. Install Android Studio or command-line tools, SDK Platform 37, Build Tools 36.0.0+, and platform-tools before local compilation. CI remains the reproducible fallback.

- [ ] **Step 2: Generate the Gradle 9.4.1 wrapper**

```powershell
Invoke-WebRequest https://services.gradle.org/distributions/gradle-9.4.1-bin.zip -OutFile "$env:TEMP\gradle-9.4.1-bin.zip"
Expand-Archive "$env:TEMP\gradle-9.4.1-bin.zip" "$env:TEMP\gradle-9.4.1" -Force
& "$env:TEMP\gradle-9.4.1\gradle-9.4.1\bin\gradle.bat" wrapper --gradle-version 9.4.1 --distribution-type bin
```

Expected: `gradlew.bat --version` reports Gradle 9.4.1.

- [ ] **Step 3: Pin the dependency catalog**

Create `gradle/libs.versions.toml` with at least:

```toml
[versions]
agp = "9.2.0"
kotlin = "2.3.21"
ksp = "2.3.10"
compose-bom = "2026.06.00"
tv-material = "1.1.0"
activity = "1.13.0"
core = "1.19.0"
navigation = "2.9.8"
room = "2.8.4"
datastore = "1.2.1"
coroutines = "1.11.0"
okhttp = "5.4.0"
okio = "3.17.0"
serialization = "1.11.0"
smbj = "0.14.0"
exifinterface = "1.4.2"
junit4 = "4.13.2"
truth = "1.4.5"
robolectric = "4.16.1"
turbine = "1.2.1"
```

Use AGP built-in Kotlin for Android modules; do not apply `org.jetbrains.kotlin.android` or kapt. Apply KSP for Room and the Compose compiler plugin matching Kotlin 2.3.21.

- [ ] **Step 4: Add root configuration**

Set:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```

Configure repositories with `google()`, `mavenCentral()`, and `gradlePluginPortal()` only.

- [ ] **Step 5: Expand `.gitignore`**

Add Android/Gradle output, local SDK settings, release keys, generated distributions, and IDE files:

```gitignore
.gradle/
**/build/
local.properties
*.apk
*.aab
*.jks
*.keystore
keystore.properties
dist/
.idea/
captures/
```

- [ ] **Step 6: Verify bootstrap**

Run:

```powershell
.\gradlew.bat help --warning-mode all
```

Expected: `BUILD SUCCESSFUL` with no repository-mode or plugin-resolution errors.

- [ ] **Step 7: Commit**

```powershell
git add settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat .editorconfig .gitignore docs/development.md
git commit -m "build: bootstrap Android project"
```

### Task 2: Create module graph, conventions, and CI smoke build

**Files:**
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/KelliKanvasAndroid.kt`
- Create: `build-logic/src/main/kotlin/AndroidApplicationConventionPlugin.kt`
- Create: `build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt`
- Create: `build-logic/src/main/kotlin/AndroidComposeConventionPlugin.kt`
- Create: `build-logic/src/main/kotlin/KotlinJvmConventionPlugin.kt`
- Create: `build-logic/src/test/kotlin/ConventionPluginTest.kt`
- Create: one `build.gradle.kts` and minimal manifest per module in the module map
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write failing convention tests**

```kotlin
class ConventionPluginTest {
    @Test fun applicationUsesSdk37AndJava17() { /* Gradle TestKit assertions */ }
    @Test fun libraryUsesSdk37AndJava17() { /* Gradle TestKit assertions */ }
    @Test fun composeConventionEnablesCompose() { /* Gradle TestKit assertions */ }
}
```

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat -p build-logic test
```

Expected: failure because convention plugins do not exist.

- [ ] **Step 3: Implement conventions**

Application convention sets `compileSdk = 37`, `minSdk = 28`, `targetSdk = 37`, namespace supplied by each module, Java 17, `AndroidJUnitRunner`, lint warnings as errors, and no lint baseline. Library convention sets the same compile/min SDK and Java level. Compose convention enables Compose and applies the Compose compiler.

- [ ] **Step 4: Create all modules**

Add every module from the module map to `settings.gradle.kts`. Android library manifests contain only `<manifest />` until permissions/components are needed.

- [ ] **Step 5: Add TV app manifest**

`app/src/main/AndroidManifest.xml` must include:

```xml
<uses-feature android:name="android.software.leanback" android:required="true" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

MainActivity uses `MAIN` and `LEANBACK_LAUNCHER`. Do not add boot, broad-storage, or privileged power permissions.

- [ ] **Step 6: Add CI smoke build**

CI uses JDK 17 and runs:

```bash
./gradlew ktlintCheck lintDebug testDebugUnitTest assembleDebug --stacktrace
```

Upload the debug APK and reports. Pin third-party actions to immutable commit SHAs before merging.

- [ ] **Step 7: Verify GREEN**

```powershell
.\gradlew.bat -p build-logic test
.\gradlew.bat projects
.\gradlew.bat assembleDebug
```

Expected: all modules listed and a debug APK produced.

- [ ] **Step 8: Commit**

```powershell
git add build-logic settings.gradle.kts app core source feature renderer platform .github
git commit -m "build: add Android TV module foundation"
```

### Task 3: Define core models and source contract

**Files:**
- Create: `core/model/src/main/kotlin/com/jedon/kellikanvas/model/Identifiers.kt`
- Create: `core/model/src/main/kotlin/com/jedon/kellikanvas/model/SourceModels.kt`
- Create: `core/model/src/main/kotlin/com/jedon/kellikanvas/model/PlaybackSettings.kt`
- Create: `core/model/src/main/kotlin/com/jedon/kellikanvas/model/AppPreferences.kt`
- Create: `core/model/src/main/kotlin/com/jedon/kellikanvas/model/SourceFailure.kt`
- Create: `core/source-api/src/main/kotlin/com/jedon/kellikanvas/source/SourceAdapter.kt`
- Create: `core/source-api/src/main/kotlin/com/jedon/kellikanvas/source/PhotoByteStream.kt`
- Test: matching `src/test/kotlin` files

- [ ] **Step 1: Write failing validation/default tests**

Cover blank identifiers, negative dimensions/lengths, safe `toString`, stable error codes, and approved defaults: 15-second slides, 700-ms crossfade, cycle shuffle, Auto portrait pairing, four-position look-ahead, 24-dp gutter, loop/resume enabled.

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :core:model:test :core:source-api:test
```

- [ ] **Step 3: Implement immutable contracts**

Core source API:

```kotlin
interface SourceAdapter {
    val profileId: SourceProfileId
    val kind: SourceKind
    val capabilities: SourceCapabilities
    suspend fun probe(): SourceStatus
    suspend fun listChildren(folder: FolderRef, cursor: PageCursor?, limit: Int = 100): Page<SourceEntry>
    suspend fun metadata(asset: AssetRef): PhotoMetadata
    suspend fun open(asset: AssetRef): PhotoByteStream
}
```

`SourceFailure` includes AuthenticationRequired, PermissionRevoked, SourceUnavailable, NotFound, UnsupportedFormat, CorruptContent, Timeout, and ProtocolFailure. Never translate `CancellationException`.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat :core:model:test :core:source-api:test
git add core/model core/source-api
git commit -m "feat: define source and playback contracts"
```

### Task 4: Add shared adapter contract tests

**Files:**
- Create: `core/source-api/src/testFixtures/kotlin/com/jedon/kellikanvas/source/testing/AdapterHarness.kt`
- Create: `core/source-api/src/testFixtures/kotlin/com/jedon/kellikanvas/source/testing/AdapterContract.kt`
- Create: `core/source-api/src/testFixtures/kotlin/com/jedon/kellikanvas/source/testing/FakePhotoByteStream.kt`
- Create: `core/source-api/src/testFixtures/kotlin/com/jedon/kellikanvas/source/testing/ContractDataset.kt`

- [ ] **Step 1: Implement contract assertions**

Every adapter suite must prove paging without skips/duplicates, stable IDs, metadata consistency, nested traversal, cancellation/resource closure, normalized failures, privacy-safe diagnostics, and streaming without full-photo preloading.

- [ ] **Step 2: Run fixture tests**

```powershell
.\gradlew.bat :core:source-api:test
```

- [ ] **Step 3: Commit**

```powershell
git add core/source-api
git commit -m "test: add source adapter contract"
```

### Task 5: Implement metadata catalog, session schema, and preferences

**Files:**
- Create: `core/catalog/src/main/kotlin/com/jedon/kellikanvas/catalog/KelliKanvasDatabase.kt`
- Create: entities for source profiles, selected roots, catalog assets, playlist cycles/items, consumed portrait partners, and slideshow session
- Create: DAOs for source, collection, catalog, playlist, and session
- Create: `core/catalog/src/main/kotlin/com/jedon/kellikanvas/catalog/preferences/AppPreferencesRepository.kt`
- Create: `DataStoreAppPreferencesRepository.kt`
- Test: DAO instrumentation tests and DataStore unit tests
- Generate: `core/catalog/schemas/.../1.json`

- [ ] **Step 1: Write failing schema tests**

Tests reopen Room, verify unique cycle ordinal/asset constraints, round-trip sessions and consumed partners, and query schema metadata to ensure no BLOB/photo payload columns exist.

- [ ] **Step 2: Write failing preference tests**

Test approved defaults, duration validation, focus-route restoration, and absence of password/token/secret/credential keys.

- [ ] **Step 3: Verify RED**

```powershell
.\gradlew.bat :core:catalog:testDebugUnitTest :core:catalog:connectedDebugAndroidTest
```

- [ ] **Step 4: Implement Room v1 and DataStore**

Export schema; do not use destructive migration fallback. Room contains metadata and playback state only.

- [ ] **Step 5: Verify GREEN and commit**

```powershell
.\gradlew.bat :core:catalog:testDebugUnitTest :core:catalog:connectedDebugAndroidTest
git add core/catalog
git commit -m "feat: persist catalog and slideshow state"
```

### Task 6: Implement Keystore-backed credentials

**Files:**
- Create: `core/security/src/main/kotlin/com/jedon/kellikanvas/security/CredentialVault.kt`
- Create: `CredentialCipher.kt`
- Create: `AesGcmCredentialCipher.kt`
- Create: `AndroidKeystoreKeyProvider.kt`
- Create: `AndroidCredentialVault.kt`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Test: unit and instrumentation tests

- [ ] **Step 1: Write failing crypto tests**

Test AES-GCM round-trip, source-profile authenticated data, tampering, deletion, and invalidated-key re-entry.

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :core:security:testDebugUnitTest :core:security:connectedDebugAndroidTest
```

- [ ] **Step 3: Implement vault**

Use AES-256-GCM, random 12-byte IVs, alias `kellikanvas.source-credentials.v1`, source profile ID as AAD, non-backed-up ciphertext, and zeroed intermediate byte arrays. Return Missing, Present, or RequiresReentry.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat :core:security:testDebugUnitTest :core:security:connectedDebugAndroidTest
git add core/security app/src/main/res/xml
git commit -m "feat: secure source credentials"
```

### Task 7: Implement USB/SAF source

**Files:**
- Create: `source/saf/src/main/kotlin/com/jedon/kellikanvas/source/saf/SafProfile.kt`
- Create: `SafTreeGrant.kt`
- Create: `SafTreePickerContract.kt`
- Create: `SafDocuments.kt`
- Create: `SafSourceAdapter.kt`
- Create: `SafPhotoByteStream.kt`
- Test: unit tests and a test DocumentsProvider instrumentation suite

- [ ] **Step 1: Write failing grant tests**

Verify read-only persisted grants, revoked permission mapping, and profile repair without ID replacement.

- [ ] **Step 2: Write failing adapter contract tests**

Fixtures contain nested landscape/portrait images, revoked mode, and removed-media mode.

- [ ] **Step 3: Implement SAF adapter**

Use `ACTION_OPEN_DOCUMENT_TREE`, provider document IDs rather than paths, deterministic pagination, `image/*` filtering, and owned `ParcelFileDescriptor` streams. Do not request storage permissions.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat :source:saf:testDebugUnitTest :source:saf:connectedDebugAndroidTest
git add source/saf
git commit -m "feat: add USB and SAF source"
```

### Task 8: Implement controlled website API source

**Files:**
- Create: `source/http/src/main/kotlin/com/jedon/kellikanvas/source/http/HttpSourceProfile.kt`
- Create: `WebsiteDtos.kt`
- Create: `WebsiteCursor.kt`
- Create: `EndpointPolicy.kt`
- Create: `BearerTokenInterceptor.kt`
- Create: `ValidatedRedirectExecutor.kt`
- Create: `WebsiteSourceAdapter.kt`
- Create: `HttpPhotoByteStream.kt`
- Test: MockWebServer suite and JSON fixtures

- [ ] **Step 1: Write DTO/cursor/policy tests**

Test the approved `/api/kellikanvas/v1/folders` and `/photos` envelopes, folders-before-photos paging, 2-MiB JSON bound, HTTPS base URL, no user info, redirect limits, TLS downgrade rejection, and no credential leakage across origins.

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :source:http:testDebugUnitTest
```

- [ ] **Step 3: Implement adapter**

Use OkHttp with automatic redirects disabled. Bearer credentials apply only to the configured API origin. Stream media bodies and preserve ETag/content length.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat :source:http:testDebugUnitTest
git add source/http
git commit -m "feat: add website API source"
```

### Task 9: Implement SMB 2/3 source

**Files:**
- Create: `source/smb/src/main/kotlin/com/jedon/kellikanvas/source/smb/SmbProfile.kt`
- Create: `SmbPath.kt`
- Create: `SmbClientFactory.kt`
- Create: `SmbSessionScope.kt`
- Create: `SmbFailureMapper.kt`
- Create: `SmbPhotoByteStream.kt`
- Create: `SmbSourceAdapter.kt`
- Create: `SmbMdnsDiscoverer.kt`
- Create: `source/smb/consumer-rules.pro`
- Test: unit, Android compatibility, and Testcontainers/Samba integration suites

- [ ] **Step 1: Write path/security/failure tests**

Reject NUL, absolute paths, empty segments, `.` and `..`. Test SMB 2.1/3.0/3.0.2/3.1.1 only, signing, explicit encryption downgrade status, and privacy-safe failures.

- [ ] **Step 2: Write Samba contract tests**

Use read-only fixtures and two configurations: mandatory-signing SMB2.1 and mandatory-encryption SMB3. Test browse, pagination, streams, bad password, disconnect, and cancellation.

- [ ] **Step 3: Verify RED**

```powershell
docker version
.\gradlew.bat :source:smb:testDebugUnitTest :source:smb:connectedDebugAndroidTest
```

- [ ] **Step 4: Implement adapter**

Use SMBJ 0.14.0 with no SMB1/SMB2.0 fallback. Streams own and close file/share/session/connection/client in reverse order. mDNS is optional; manual host/share entry is mandatory.

- [ ] **Step 5: Verify release/R8 compatibility and commit**

```powershell
.\gradlew.bat :source:smb:testDebugUnitTest :source:smb:connectedDebugAndroidTest :app:assembleRelease
git add source/smb
git commit -m "feat: add secure SMB source"
```

### Task 10: Implement focused DLNA/UPnP source

**Files:**
- Create: SSDP discoverer/parser/multicast-lock classes in `source/dlna`
- Create: device description, ContentDirectory, DIDL-Lite, and resource-selection classes
- Create: endpoint policy, adapter, profile, and stream classes
- Test: synthetic SSDP, MockWebServer, bounded XML, SOAP/DIDL fixtures, adapter contract

- [ ] **Step 1: Write failing SSDP tests**

Test three-second discovery, 64-KiB datagrams, bounded headers, MediaServer filtering, UDN dedupe, cancellation, and guaranteed multicast-lock release.

- [ ] **Step 2: Write failing XML/SOAP tests**

Reject DOCTYPE/entity expansion, XML deeper than 32, responses over 2 MiB, pages over 500 entries, and text/attributes over 4 KiB. Test ContentDirectory v1/v2 and resource scoring near 4K.

- [ ] **Step 3: Write failing endpoint tests**

Permit cleartext only to validated private/link-local discovered endpoints. Revalidate redirects and reject public/multicast/unspecified destinations.

- [ ] **Step 4: Verify RED**

```powershell
.\gradlew.bat :source:dlna:testDebugUnitTest
```

- [ ] **Step 5: Implement adapter**

Build a narrow OkHttp/XML control point; do not add Cling. Preserve server UDN plus object ID and map QNAP reindex invalidation to repairable NotFound.

- [ ] **Step 6: Verify GREEN and commit**

```powershell
.\gradlew.bat :source:dlna:testDebugUnitTest
git add source/dlna
git commit -m "feat: add DLNA ContentDirectory source"
```

### Task 11: Implement collection normalization and bounded indexing

**Files:**
- Create: `feature/collection/.../RootNormalizer.kt`
- Create: `IndexLimits.kt`
- Create: `TraversalGuard.kt`
- Create: `CollectionIndexer.kt`
- Test: root and indexer unit tests

- [ ] **Step 1: Write failing root tests**

Prove recursive ancestors suppress redundant explicit children while non-recursive parents and different sources remain distinct.

- [ ] **Step 2: Write failing indexer tests**

Prove round-robin paging across sources, loop prevention, dedupe by AssetKey, cancellation, and limits of depth 64, 100,000 folders, and 1,000,000 assets.

- [ ] **Step 3: Implement and verify**

```powershell
.\gradlew.bat :feature:collection:testDebugUnitTest
```

- [ ] **Step 4: Commit**

```powershell
git add feature/collection core/catalog
git commit -m "feat: index bounded multi-source collections"
```

### Task 12: Implement deterministic cycles and portrait pairing

**Files:**
- Create: `feature/slideshow/.../CycleOrder.kt`
- Create: `ShuffleRanker.kt`
- Create: `PlaylistCycleBuilder.kt`
- Create: `SlidePlan.kt`
- Create: `PortraitPairPlanner.kt`
- Create: `PlaylistNavigator.kt`
- Test: cycle, pairing, and persistence tests

- [ ] **Step 1: Write failing cycle tests**

Shuffle rank is SHA-256 of persisted seed plus source/object IDs. Prove deterministic order, one appearance per cycle, stable tie-breakers, unknown dates last, and next-cycle admission of new assets.

- [ ] **Step 2: Write failing portrait tests**

Offsets one through four pair; offset five does not. Consumed candidates are skipped, intervening landscapes retain order, partners never replay, and look-ahead does not wrap cycles.

- [ ] **Step 3: Implement immutable cycle transaction and planner**

Cycle creation snapshots eligible metadata in one transaction. Auto and Always both use the approved four-position behavior until a later design defines a distinct Always heuristic.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat :feature:slideshow:testDebugUnitTest :feature:slideshow:connectedDebugAndroidTest
git add feature/slideshow core/catalog
git commit -m "feat: build deterministic slideshow cycles"
```

### Task 13: Implement disposable image pipeline

**Files:**
- Create: transient store/lease/stager classes in `core/image`
- Create: metadata, EXIF orientation, decode planner, Android decoder, and error classes
- Create: current/next frame slots, memory policy, and controller
- Test: unit/instrumentation suites and image fixtures

- [ ] **Step 1: Write failing storage tests**

Only `cacheDir/kellikanvas/slideshow/<process-token>` is allowed. Closing, cancellation, eviction, session stop, and startup cleanup delete bytes. At most two leases exist.

- [ ] **Step 2: Write failing metadata/decode tests**

Cover all eight EXIF orientations, target-size bounds, malformed dimensions before allocation, missing dates, GPS omission, corrupt/CMYK/unsupported formats, sRGB baseline, and conditional P3.

- [ ] **Step 3: Write failing memory tests**

Exactly current/next frames; generation-based stale result rejection; low-memory drops next; critical memory enters one-frame mode; UI hidden releases all.

- [ ] **Step 4: Implement and verify**

```powershell
.\gradlew.bat :core:image:testDebugUnitTest :core:image:connectedDebugAndroidTest
```

- [ ] **Step 5: Commit**

```powershell
git add core/image
git commit -m "feat: add bounded disposable image pipeline"
```

### Task 14: Implement native-4K SurfaceView renderer

**Files:**
- Create: display mode and `NativePhotoSurfaceView` classes in `renderer/surface`
- Create: pure layout geometry and render-layer classes
- Create: transitions, timeline, low-memory coordinator, and render loop
- Test: JVM geometry/timeline tests and Android surface tests

- [ ] **Step 1: Write failing display-mode tests**

Prefer exact 3840x2160, set preferred display mode/fixed surface size, distinguish requested from actual dimensions, and report fallback reason.

- [ ] **Step 2: Write failing layout tests**

Cover Full, Fill, Blurred Border, Solid, Stretch, single portrait full height, and two portrait columns with 24-dp gutter. No full-surface intermediate bitmap is allowed.

- [ ] **Step 3: Write failing transition tests**

Cover Cut, Crossfade, Fade Through Black, Slide, Pan/Zoom, deterministic Random, reduced-motion restrictions, and one-frame fade-through-black.

- [ ] **Step 4: Implement renderer**

Use `SurfaceHolder.Callback2`, a dedicated HandlerThread, and `lockHardwareCanvas()`. Draw layers directly.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat :renderer:surface:testDebugUnitTest :renderer:surface:connectedDebugAndroidTest
git add renderer/surface
git commit -m "feat: render native-resolution slideshows"
```

### Task 15: Implement ambient, presence, schedule, and DreamService

**Files:**
- Create: sensor inventory, capability, lux, presence, schedule, coordinator, and diagnostics classes in `platform/ambient`
- Create: `app/.../dream/KelliKanvasDreamService.kt`
- Create: `DreamCapabilityProbe.kt`
- Create: `app/src/main/res/xml/dream_info.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: policy unit tests and DreamService tests

- [ ] **Step 1: Write failing sensor classification tests**

Standard light is eligible; vendor RGB is candidate-unverified; accelerometer/gyro/device-motion never imply room presence; no sensors reports unavailable.

- [ ] **Step 2: Write failing lux tests**

Use five-second half-life EMA, logarithmic 5-500 lux mapping to 0.08-0.85 window brightness, 0.03 hysteresis, and two-second minimum update interval.

- [ ] **Step 3: Write failing presence tests**

Only a qualified sensor can pause. Presence return resumes only a presence-paused slideshow. Manual pause remains paused; firmware fingerprint change invalidates qualification; never issue wake/power commands.

- [ ] **Step 4: Write failing schedule tests**

Default 07:00 day/21:00 night, midnight crossing, timezone/DST changes, and precedence: verified light, schedule, then Follow TV.

- [ ] **Step 5: Implement policies and Android adapters**

No sensor is required in the manifest. Per-window brightness only. Unsupported presence leaves power management to Google TV.

- [ ] **Step 6: Add DreamService**

Declare `BIND_DREAM_SERVICE` on the service, reuse slideshow/renderer, do not keep screen on or wake it, and exit cleanly with no playable collection.

- [ ] **Step 7: Verify and commit**

```powershell
.\gradlew.bat :platform:ambient:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
git add platform/ambient app
git commit -m "feat: add capability-gated ambient behavior"
```

### Task 16: Build TV navigation, home, and first-run setup

**Files:**
- Create: TV tokens/focus primitives in `core/ui-tv`
- Create: application graph, MainActivity, theme, destinations, and NavHost in `app`
- Create: home contract/viewmodel/screen
- Create: setup contract/viewmodel/screens/preview in `feature/setup`
- Test: unit and Compose UI tests

- [ ] **Step 1: Write failing navigation/focus tests**

Test setup-vs-home start, Back semantics, visible non-color focus, 64-dp targets, and focus restoration by stable key.

- [ ] **Step 2: Write failing setup tests**

Test resumable Welcome/Sources/Folders/Preview/Confirm steps, source/folder requirements, error preservation, defaults, and D-pad-only completion.

- [ ] **Step 3: Implement TV shell**

Home contains Start/Resume, Collection, Appearance, Playback, and Ambient & System in approved order. Back from Home is unconsumed.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat :core:ui-tv:connectedDebugAndroidTest :feature:setup:testDebugUnitTest :feature:setup:connectedDebugAndroidTest :app:connectedDebugAndroidTest
git add core/ui-tv feature/setup app
git commit -m "feat: add remote-first TV setup and home"
```

### Task 17: Build multi-source collection browser and settings

**Files:**
- Create: collection contract/viewmodel/tree/row/summary screens
- Create: settings catalog/viewmodel/screens/rows
- Test: unit and Compose UI tests

- [ ] **Step 1: Write failing collection UI tests**

Test checked/partial/unchecked semantics, Select toggle, Right expand, Left collapse/parent, multi-source selection, pagination focus preservation, recursion default, repair actions, and summary counts.

- [ ] **Step 2: Write failing settings tests**

Test approved defaults/taxonomy, stretch confirmation, reduced-motion transition replacement, presence/wake visibility gating, and non-color state semantics.

- [ ] **Step 3: Implement screens and verify**

```powershell
.\gradlew.bat :feature:collection:testDebugUnitTest :feature:collection:connectedDebugAndroidTest :feature:settings:testDebugUnitTest :feature:settings:connectedDebugAndroidTest
```

- [ ] **Step 4: Commit**

```powershell
git add feature/collection feature/settings
git commit -m "feat: add collection browser and settings"
```

### Task 18: Build slideshow controls, overlays, diagnostics, and restoration

**Files:**
- Create: slideshow contract/viewmodel/route/screen/controls
- Create: overlay coordinator, photo info, persistent metadata
- Create: session coordinator/repository and resume decisions
- Create: diagnostics viewmodel/screen under `feature/settings`
- Test: unit, Compose UI, Room reopen, and end-to-end pipeline tests

- [ ] **Step 1: Write failing remote tests**

Left/Right navigate, Select pauses, Up controls, Down information, Back closes overlay then checkpoints and returns Home, and rapid navigation cancels stale loads.

- [ ] **Step 2: Write failing overlay/accessibility tests**

Controls dismiss after five seconds unless TalkBack is active. User input resets timer. Reduced motion disables directional motion. All controls are D-pad reachable and labeled.

- [ ] **Step 3: Write failing restoration tests**

Reopen Room and recover exact item/seed/consumed partners. Unavailable current item seeks forward; repairable source retains session; all failures show stable recovery.

- [ ] **Step 4: Write failing diagnostics tests**

Report display/surface dimensions, memory, sensors, brightness/power status, source health, counts, grouped skips, and `UNVERIFIED` hardware. Redact credentials, URLs, filenames, GPS, and provider payloads.

- [ ] **Step 5: Implement, verify, and commit**

```powershell
.\gradlew.bat :feature:slideshow:testDebugUnitTest :feature:slideshow:connectedDebugAndroidTest :feature:settings:testDebugUnitTest :feature:settings:connectedDebugAndroidTest
git add feature/slideshow feature/settings app
git commit -m "feat: integrate slideshow controls and diagnostics"
```

### Task 19: Add verified private update delivery and QNAP hosting

**Files:**
- Create: update manifest/policy/repository/APK verifier/installer in `platform/update`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Create: `app/src/main/res/xml/file_paths.xml`
- Create: `tools/build_update_bundle.py`
- Create: `tools/tests/test_build_update_bundle.py`
- Create: `tools/publish-to-qnap.ps1`
- Create: `deploy/qnap/compose.yaml`
- Create: `deploy/qnap/nginx.conf`
- Create: `deploy/qnap/README.md`
- Create: `.github/workflows/release-apk.yml`
- Test: update unit tests and Python tests

- [ ] **Step 1: Write failing manifest/URL tests**

Authenticate strict canonical manifest bytes with the app-pinned offline metadata public key before trusting any field. The QNAP origin is exactly `http://darklingnas:8088`; explicitly configured remote origins require HTTPS. Metadata is maximum 64 KiB, redirects are forbidden, package is `com.jedon.kellikanvas`, release sequence and version are anti-replay monotonic, and APK size is maximum 500 MiB.

- [ ] **Step 2: Write failing APK verification tests**

Require exact size, manifest and independent checksum agreement, package identity, version, and signer matching the installed signer. Failed/stale files are deleted.

- [ ] **Step 3: Implement user-confirmed installer**

Use an Android `PackageInstaller` full-install session with mutable status callback only on API 31+, a non-exported completion receiver, and explicit system user confirmation. Request “Install unknown apps” when necessary. Never silently install.

- [ ] **Step 4: Implement signed release bundle**

Release APK signing comes only from four environment variables. An isolated workflow step obtains the offline metadata private key from a separate secret, signs canonical metadata, deletes all key material, and writes the versioned APK, checksum, metadata signature, then manifest last.

- [ ] **Step 5: Implement QNAP publisher**

Copy to `\\DarklingNAS\Public\KelliKanvas`, verify copied SHA-256, and atomically rename manifest last. nginx binds port 8088 only on a configured trusted-LAN address, uses a pinned multi-architecture digest, read-only mounts `/share/Public/KelliKanvas`, disables listing, and serves only GET/HEAD.

- [ ] **Step 6: Verify**

```powershell
.\gradlew.bat :platform:update:testDebugUnitTest :app:lintDebug
python -m unittest tools.tests.test_build_update_bundle
docker compose -f deploy/qnap/compose.yaml config
```

- [ ] **Step 7: Commit**

```powershell
git add platform/update app tools deploy .github
git commit -m "feat: add verified private APK updates"
```

### Task 20: Register all sources and complete end-to-end TV journeys

**Files:**
- Create: `app/src/main/kotlin/com/jedon/kellikanvas/di/SourceBindings.kt`
- Create: `app/src/test/kotlin/com/jedon/kellikanvas/SourceRegistryTest.kt`
- Create: `app/src/androidTest/kotlin/com/jedon/kellikanvas/e2e/FirstRunJourneyTest.kt`
- Create: `ReturningUserJourneyTest.kt`
- Create: `FiveWayNavigationTest.kt`
- Create: `BackBehaviorTest.kt`
- Create: `SlideshowPipelineTest.kt`

- [ ] **Step 1: Write failing registry test**

Require exactly one SAF, HTTP, SMB, and DLNA factory.

- [ ] **Step 2: Write failing journeys**

D-pad-only first run adds a source/selects a folder/starts; returning user resumes; every screen is operable with five-way input; Back unwinds dialog/panel/screen; pipeline indexes two fake sources, pairs a portrait at offset four, handles memory pressure, and restores after process recreation.

- [ ] **Step 3: Implement application wiring**

Wire adapters, catalog, vault, renderer, ambient, update, and feature viewmodels manually through `AppContainer`. Keep dependencies pointed inward.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
git add app
git commit -m "feat: complete KelliKanvas application flow"
```

### Task 21: Add visual, performance, and physical-device qualification

**Files:**
- Create: screenshot tests and approved images under `app/src/test`
- Create: `tools/device-checks/run-tv-checks.ps1`
- Create: `tools/device-checks/capture-tv-screens.ps1`
- Create: `docs/qa/tv-screen-matrix.md`
- Create: `docs/qa/physical-device-acceptance.md`
- Create: `docs/qualification/canvastv-ambient-and-release.md`

- [ ] **Step 1: Add screenshot cases**

Home default/font scale 1.3, partial folder selection, portrait pair preview, stretch warning, focused controls, info overlay, unsupported diagnostics, and verified-4K diagnostics. Screenshots validate layout only.

- [ ] **Step 2: Add debug 4K probe**

Expose a debug-only native line/grid pattern and diagnostics showing requested/actual surface dimensions.

- [ ] **Step 3: Record physical gates**

Require native 4K panel inspection, 8/24-hour memory soak, QNAP DLNA/SMB, USB removal, network recovery, ambient/presence correlation, brightness stability, DreamService selection, sleep/resume, signed update, and wrong-signer rejection. Keep unavailable evidence marked `UNVERIFIED`.

- [ ] **Step 4: Verify automated subset**

```powershell
.\gradlew.bat verifyRoborazziDebug
```

Manually read every generated screenshot before accepting it.

- [ ] **Step 5: Commit**

```powershell
git add app/src/test tools/device-checks docs/qa docs/qualification
git commit -m "test: add TV and CanvasTV qualification"
```

### Task 22: Write README, license, notices, and final verification

**Files:**
- Create: `README.md`
- Create: `LICENSE`
- Create: `docs/architecture.md`
- Create: `docs/sources.md`
- Create: `docs/distribution.md`
- Create: `docs/troubleshooting.md`
- Create: `docs/testing/source-adapter-qualification.md`

- [ ] **Step 1: Write README**

Include purpose, screenshots placeholder-free status text, supported/planned feature distinction, architecture, requirements, build commands, ADB install, source configuration, QNAP update hosting, permissions, sensor/power limitations, testing, roadmap, contributing, and MIT license.

- [ ] **Step 2: Add MIT license and OSS notices guidance**

Document Apache-2.0/MIT dependencies and avoid LGPL/CDDL dependencies in the selected stack.

- [ ] **Step 3: Run full automated verification**

```powershell
.\gradlew.bat clean ktlintCheck lintDebug testDebugUnitTest assembleDebug --stacktrace
.\gradlew.bat connectedDebugAndroidTest --stacktrace
.\gradlew.bat verifyRoborazziDebug
python -m unittest tools.tests.test_build_update_bundle
docker compose -f deploy/qnap/compose.yaml config
git diff --check
git status --short
```

Expected: all available checks pass; no generated secrets or APKs are tracked; hardware-only checks remain explicitly `UNVERIFIED`.

- [ ] **Step 4: Review README commands against the repository**

Execute each documented local command that does not require unavailable hardware, NAS credentials, or release keys. Correct any drift.

- [ ] **Step 5: Commit**

```powershell
git add README.md LICENSE docs
git commit -m "docs: add KelliKanvas project documentation"
```

## Final release handoff

1. Create and securely back up the long-lived release keystore.
2. Configure GitHub Actions release secrets.
3. Build and download the signed `dist` artifact.
4. Start the QNAP nginx stack and publish the bundle to `\\DarklingNAS\Public\KelliKanvas`.
5. Install the first APK with ADB or a TV file manager.
6. Grant Local Network and “Install unknown apps” permissions when Android requests them.
7. Complete the physical CanvasTV qualification checklist before marking native 4K, sensor brightness, presence, DreamService, or wake behavior verified.

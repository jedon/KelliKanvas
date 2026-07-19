# Overflow Menu + QNAP DLNA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the on-screen Home destination list with a Material3 overflow menu, and let users add QNAP folders over DLNA alongside existing SAF roots in one slideshow.

**Architecture:** Room v3 stores `dlna_connections`. Collection hub (in empty `feature/collection`) lists roots and launches SAF or DLNA setup. DLNA setup uses existing `SsdpDiscoverer` / `DlnaProfileDiscovery` plus a new manual host→description resolver, then browses via `DlnaSourceAdapter.network`. Shell load builds a `Map<SourceProfileId, SourceAdapter>` and a multi-adapter playlist flattener feeds the existing simple slideshow.

**Tech Stack:** Kotlin, Jetpack Compose Material3 (phone-friendly chrome), Navigation Compose, Room, OkHttp, existing `source/dlna` + `source/saf`, JUnit/Truth/Robolectric.

**Spec:** `docs/superpowers/specs/2026-07-18-overflow-menu-qnap-dlna-design.md`

---

## File map

| Path | Responsibility |
|------|----------------|
| `core/catalog/.../CatalogModels.kt` | `DlnaConnection` domain model |
| `core/catalog/.../RoomEntities.kt` | `DlnaConnectionEntity` |
| `core/catalog/.../RoomDaos.kt` | `RoomDlnaConnectionDao` + selected-root single delete |
| `core/catalog/.../CatalogDaos.kt` | `DlnaConnectionDao`, `SelectedRootDao.delete` / merge helpers |
| `core/catalog/.../KelliKanvasDatabase.kt` | schema v3 + `MIGRATION_2_3` |
| `core/catalog/schemas/.../3.json` | Exported schema |
| `source/dlna/.../DlnaManualResolver.kt` | Host/IP or URL → `DlnaProfile` |
| `feature/setup/.../SafSetupController.kt` | Append SAF roots; stop wiping other profiles |
| `feature/collection/.../CollectionHubController.kt` | List/remove roots; shared collection id |
| `feature/collection/.../CollectionHubScreen.kt` | Collection UI |
| `feature/collection/.../DlnaSetupController.kt` | Discover / manual / browse / persist |
| `feature/collection/.../DlnaSetupScreen.kt` | DLNA setup UI |
| `feature/slideshow/.../CollectionPhotoPlaylist.kt` | Multi-adapter flatten (replace single-adapter-only usage) |
| `feature/slideshow/.../SimpleSlideshowScreen.kt` | Adapter map lookup by `AssetRef.profileId` |
| `app/.../home/HomeScreen.kt` | TopAppBar + overflow; Start only |
| `app/.../AppContainer.kt` | OkHttp + DLNA/SAF adapter factories |
| `app/.../KelliKanvasNavHost.kt` | Collection/DLNA routes; multi-adapter `loadShellState` |
| `app/.../settings/SettingsPlaceholderScreen.kt` | Optional TopAppBar Up |

---

### Task 1: Persist DLNA connections (Room v3)

**Files:**
- Modify: `core/catalog/src/main/kotlin/com/jedon/kellikanvas/catalog/CatalogModels.kt`
- Modify: `core/catalog/src/main/kotlin/com/jedon/kellikanvas/catalog/RoomEntities.kt`
- Modify: `core/catalog/src/main/kotlin/com/jedon/kellikanvas/catalog/RoomDaos.kt`
- Modify: `core/catalog/src/main/kotlin/com/jedon/kellikanvas/catalog/CatalogDaos.kt`
- Modify: `core/catalog/src/main/kotlin/com/jedon/kellikanvas/catalog/KelliKanvasDatabase.kt`
- Create: `core/catalog/src/test/kotlin/com/jedon/kellikanvas/catalog/DlnaConnectionDaoTest.kt`
- Modify: `core/catalog/src/androidTest/kotlin/com/jedon/kellikanvas/catalog/KelliKanvasMigrationTest.kt`
- Export: `core/catalog/schemas/com.jedon.kellikanvas.catalog.KelliKanvasDatabase/3.json`

- [ ] **Step 1: Write failing test**

```kotlin
package com.jedon.kellikanvas.catalog

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DlnaConnectionDaoTest {
    @Test
    fun upsertAndReadByProfileId() = runBlocking {
        val db = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
        val profileId = SourceProfileId("dlna-1")
        db.sourceProfiles.upsert(
            SourceProfile(
                id = profileId,
                kind = SourceKind.DLNA,
                displayName = "DarklingNAS",
                createdAtMillis = 1L,
            ),
        )
        db.dlnaConnections.upsert(
            DlnaConnection(
                profileId = profileId,
                serverUdn = "uuid:qnap-1",
                descriptionLocation = "http://192.168.68.81:8200/rootDesc.xml",
                controlUrl = "http://192.168.68.81:8200/ctl/ContentDir",
                contentDirectoryVersion = 1,
                displayName = "DarklingNAS",
            ),
        )
        val loaded = db.dlnaConnections.get(profileId)
        assertThat(loaded?.serverUdn).isEqualTo("uuid:qnap-1")
        assertThat(loaded?.controlUrl).contains("ContentDir")
        db.close()
    }
}
```

- [ ] **Step 2: Run test — expect compile failure missing `DlnaConnection` / `dlnaConnections`**

```
.\gradlew.bat :core:catalog:testDebugUnitTest --tests com.jedon.kellikanvas.catalog.DlnaConnectionDaoTest
```

- [ ] **Step 3: Implement model, entity, DAO, migration**

Add domain (redact URLs in `toString`):

```kotlin
class DlnaConnection(
    val profileId: SourceProfileId,
    val serverUdn: String,
    val descriptionLocation: String,
    val controlUrl: String,
    val contentDirectoryVersion: Int,
    val displayName: String,
) {
    init {
        require(serverUdn.startsWith("uuid:", ignoreCase = true))
        require(descriptionLocation.isNotBlank() && !descriptionLocation.contains('\n'))
        require(controlUrl.isNotBlank() && !controlUrl.contains('\n'))
        require(contentDirectoryVersion in 1..2)
        require(displayName.isNotBlank())
    }
}
```

Entity table `dlna_connections` PK `profile_id` FK → `source_profiles` CASCADE.  
`RoomDlnaConnectionDao`: upsert/get/delete.  
Public `DlnaConnectionDao`.  
`@Database(version = 3)` include entity; register `roomDlnaConnections()`; expose `dlnaConnections`.  

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `dlna_connections` (
                `profile_id` TEXT NOT NULL,
                `server_udn` TEXT NOT NULL,
                `description_location` TEXT NOT NULL,
                `control_url` TEXT NOT NULL,
                `content_directory_version` INTEGER NOT NULL,
                `display_name` TEXT NOT NULL,
                PRIMARY KEY(`profile_id`),
                FOREIGN KEY(`profile_id`) REFERENCES `source_profiles`(`profile_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}
```

Factory: `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. Export schema `3.json`. Extend migration instrumented test for 2→3.

- [ ] **Step 4: Re-run unit test — PASS**

- [ ] **Step 5: Commit**

```
git add core/catalog
git commit -m "feat(catalog): persist DLNA connection endpoints"
```

---

### Task 2: Merge-friendly selected roots + SAF append

**Files:**
- Modify: `core/catalog/.../RoomDaos.kt` (`RoomSelectedRootDao`)
- Modify: `core/catalog/.../CatalogDaos.kt` (`SelectedRootDao`)
- Modify: `feature/setup/.../SafSetupController.kt`
- Modify: `feature/setup/src/test/.../SafSetupControllerTest.kt`
- Create: `feature/collection/.../CollectionHubController.kt`
- Create: `feature/collection/src/test/.../CollectionHubControllerTest.kt`
- Modify: `feature/collection/build.gradle.kts` (catalog already present; add test deps like setup)

- [ ] **Step 1: Write failing tests**

`SelectedRootDao` needs:

```kotlin
suspend fun delete(
    collectionId: String,
    profileId: SourceProfileId,
    objectId: ProviderObjectId,
)
```

`SafSetupController.complete` must **keep** existing DLNA roots when adding SAF:

```kotlin
@Test
fun complete_appendsSafWithoutDeletingOtherProfiles() = runBlocking {
    // seed collection with a DLNA root + profile
    // call complete(safProfile, ...)
    // assert both SAF and DLNA roots remain
}
```

`CollectionHubController`:

```kotlin
class CollectionHubController(private val database: KelliKanvasDatabase) {
    suspend fun listRoots(): List<SelectedRoot>
    suspend fun removeRoot(root: SelectedRoot)
    // after remove, if profile has no remaining roots in any collection, delete sourceProfiles
}
```

- [ ] **Step 2: Run tests — FAIL**

```
.\gradlew.bat :feature:setup:testDebugUnitTest :feature:collection:testDebugUnitTest --tests "*SafSetup*" --tests "*CollectionHub*"
```

- [ ] **Step 3: Implement**

Room query:

```sql
DELETE FROM selected_roots
WHERE collection_id = :collectionId AND profile_id = :profileId AND object_id = :objectId
```

Also delete matching filters first (mirror `deleteFilters`).

Rewrite `SafSetupController.complete`:

```kotlin
val previous = database.selectedRoots.list(collectionId)
val retained = previous.filter { it.profileId != profile.id }
val next = retained + SelectedRoot(
    collectionId = collectionId,
    profileId = profile.id,
    objectId = ProviderObjectId(profile.grant.documentId),
    displayLabel = displayName,
    includeDescendants = includeDescendants,
)
database.selectedRoots.replaceAllForCollection(collectionId, next)
// Do NOT delete other source profiles
```

If collection label empty/default, upsert `CatalogCollection(DEFAULT_COLLECTION_ID, displayName)` without renaming when other roots exist (keep existing label if collection already has roots).

`CollectionHubController.removeRoot`: delete one root; if `list(collectionId)` has no rows for that `profileId`, `sourceProfiles.delete(profileId)` (cascades connections).

- [ ] **Step 4: Tests PASS**

- [ ] **Step 5: Commit**

```
git commit -m "feat(collection): merge SAF roots and support root removal"
```

---

### Task 3: Manual host/IP → DlnaProfile

**Files:**
- Create: `source/dlna/src/main/kotlin/com/jedon/kellikanvas/source/dlna/DlnaManualResolver.kt`
- Create: `source/dlna/src/test/kotlin/com/jedon/kellikanvas/source/dlna/DlnaManualResolverTest.kt`

- [ ] **Step 1: Failing test with MockWebServer**

```kotlin
@Test
fun resolve_hostTriesRootDescPath() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody(qnapDescriptionXml(server.url("/").toString())))
    server.start()
    val host = server.hostName
    val port = server.port
    val resolver = DlnaManualResolver(OkHttpClient(), DeviceDescriptionClient(OkHttpClient()))
    // Pass "host:port" so candidate hits MockWebServer
    val profile = resolver.resolve("$host:$port")
    assertThat(profile.serverUdn).startsWith("uuid:")
    assertThat(profile.controlUrl).isNotNull()
    server.shutdown()
}
```

Use a minimal MediaServer description XML consistent with `DeviceDescriptionParser` expectations (ContentDirectory service + UDN).

- [ ] **Step 2: Run — FAIL missing class**

```
.\gradlew.bat :source:dlna:testDebugUnitTest --tests com.jedon.kellikanvas.source.dlna.DlnaManualResolverTest
```

- [ ] **Step 3: Implement resolver**

```kotlin
class DlnaManualResolver(
    private val loadDescription: suspend (URI) -> ByteArray,
    private val parser: DeviceDescriptionParser = DeviceDescriptionParser(),
    private val profileIdFactory: (String) -> SourceProfileId = ::stableDlnaProfileIdPublic,
) {
    constructor(httpClient: OkHttpClient) : this(DeviceDescriptionClient(httpClient)::load)

    suspend fun resolve(input: String): DlnaProfile {
        val candidates = descriptionCandidates(input.trim())
        var lastError: Exception? = null
        for (location in candidates) {
            try {
                val description = parser.parse(loadDescription(location), location.toString())
                DlnaEndpointPolicy(location).validateInitial(description.controlUrl)
                return DlnaProfile(
                    id = profileIdFactory(description.udn),
                    serverUdn = description.udn.lowercase(),
                    descriptionLocation = location,
                    controlUrl = description.controlUrl,
                    contentDirectoryVersion = description.version,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw DlnaSourceUnavailableException().apply { lastError?.let(::initCause) }
    }

    companion object {
        fun descriptionCandidates(input: String): List<URI> {
            val trimmed = input.trim()
            if (trimmed.contains("://")) return listOf(URI(trimmed))
            val hostPort = trimmed.removePrefix("http://").removePrefix("https://")
            return listOf(
                URI("http://$hostPort/rootDesc.xml"),
                URI("http://$hostPort/description.xml"),
                URI("http://$hostPort:8200/rootDesc.xml"),
            ).distinct()
        }
    }
}
```

If `stableDlnaProfileId` is private in `DlnaProfileDiscovery.kt`, either make it `internal` and share, or duplicate the hash helper as `internal fun stableDlnaProfileId`. Prefer making the existing helper `internal` and reusing it.

- [ ] **Step 4: PASS + commit**

```
git commit -m "feat(dlna): resolve MediaServer from host or description URL"
```

---

### Task 4: Multi-adapter playlist + slideshow

**Files:**
- Create: `feature/slideshow/.../CollectionPhotoPlaylist.kt`
- Modify: `feature/slideshow/.../SafPhotoPlaylist.kt` (delegate to collection playlist **or** keep as thin wrapper)
- Modify: `feature/slideshow/.../SimpleSlideshowScreen.kt`
- Modify: `feature/slideshow/src/test/.../SafPhotoPlaylistTest.kt`
- Create: `feature/slideshow/src/test/.../CollectionPhotoPlaylistTest.kt`

- [ ] **Step 1: Failing test — two adapters, two roots**

```kotlin
@Test
fun build_mergesPhotosFromMultipleAdapters() = runTest {
    val safId = SourceProfileId("saf-1")
    val dlnaId = SourceProfileId("dlna-1")
    val adapters = mapOf(
        safId to FakeSourceAdapter(safId, mapOf(FolderRef(safId, ProviderObjectId("a")) to listOf(photo(safId, "p1")))),
        dlnaId to FakeSourceAdapter(dlnaId, mapOf(FolderRef(dlnaId, ProviderObjectId("b")) to listOf(photo(dlnaId, "p2")))),
    )
    val roots = listOf(
        SelectedRoot("default", safId, ProviderObjectId("a"), "Local", true),
        SelectedRoot("default", dlnaId, ProviderObjectId("b"), "QNAP", true),
    )
    val result = CollectionPhotoPlaylist.build(adapters, roots)
    assertThat(result.map { it.objectId.value }).containsExactly("p1", "p2").inOrder()
}
```

Also: when one adapter throws on list, skip that root and continue (assert other photos remain).

- [ ] **Step 2: Run — FAIL**

- [ ] **Step 3: Implement**

```kotlin
object CollectionPhotoPlaylist {
    suspend fun build(
        adapters: Map<SourceProfileId, SourceAdapter>,
        roots: List<SelectedRoot>,
        pageSize: Int = 64,
    ): List<AssetRef> {
        val results = mutableListOf<AssetRef>()
        for (root in roots) {
            val adapter = adapters[root.profileId] ?: continue
            runCatching {
                // same collectPhotos logic as SafPhotoPlaylist
            }
        }
        return results
    }
}
```

`SimpleSlideshowScreen` signature:

```kotlin
fun SimpleSlideshowScreen(
    adapters: Map<SourceProfileId, SourceAdapter>,
    roots: List<SelectedRoot>,
    ...
)
```

Build with `CollectionPhotoPlaylist.build(adapters, roots)`.  
Decode with `adapters.getValue(asset.profileId).open(asset)` (same open API already used).

Keep `SafPhotoPlaylist.build(adapter, roots)` as:

```kotlin
CollectionPhotoPlaylist.build(mapOf(adapter.profileId to adapter), roots)
```

so old tests still compile.

- [ ] **Step 4: PASS + commit**

```
git commit -m "feat(slideshow): merge SAF and DLNA roots into one playlist"
```

---

### Task 5: Home overflow menu

**Files:**
- Modify: `app/src/main/kotlin/com/jedon/kellikanvas/home/HomeScreen.kt`
- Modify: `app/build.gradle.kts` if needed for `material-icons-core` (`Icons.Default.MoreVert`)

- [ ] **Step 1: Manual/UI acceptance criteria (no Compose UI test required unless easy)**

Replace on-screen rows for Collection/Appearance/Playback/Ambient with:

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text(collectionLabel.ifBlank { "KelliKanvas" }) },
            actions = {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Collection") }, onClick = { expanded = false; onOpenCollection() })
                    DropdownMenuItem(text = { Text("Appearance") }, onClick = { expanded = false; onOpenAppearance() })
                    DropdownMenuItem(text = { Text("Playback") }, onClick = { expanded = false; onOpenPlayback() })
                    DropdownMenuItem(text = { Text("Ambient and System") }, onClick = { expanded = false; onOpenAmbient() })
                }
            },
        )
    },
) { padding ->
    // Status + Start or Resume only (focusable/clickable Material3 Button)
}
```

Use phone Material3 (`androidx.compose.material3`), not TV Button, for touch. Keep BackHandler finish activity. Drop `HomeControl` focus list for Collection/Appearance/… or map Start only into prefs if still useful.

- [ ] **Step 2: Compile**

```
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```
git commit -m "feat(app): move Home destinations into overflow menu"
```

---

### Task 6: Collection hub UI

**Files:**
- Create: `feature/collection/.../CollectionHubScreen.kt`
- Modify: `feature/collection/build.gradle.kts` — add `compose-material3`, `activity-compose`, `source:saf` if launching SAF from hub via callback only (prefer callbacks to app nav)

- [ ] **Step 1: Implement screen**

```kotlin
@Composable
fun CollectionHubScreen(
    roots: List<SelectedRoot>,
    sourceLabels: Map<SourceProfileId, String>, // "Local" / "QNAP"
    onAddLocalFolder: () -> Unit,
    onAddQnap: () -> Unit,
    onRemoveRoot: (SelectedRoot) -> Unit,
    onBack: () -> Unit,
)
```

List roots with label + kind; buttons Add local folder / Add QNAP; remove per row; TopAppBar Up.

- [ ] **Step 2: Wire from NavHost in Task 8 (stub compile-ok with empty callbacks until then)**

- [ ] **Step 3: Commit**

```
git commit -m "feat(collection): add collection hub screen"
```

---

### Task 7: DLNA setup controller + screen

**Files:**
- Create: `feature/collection/.../DlnaSetupController.kt`
- Create: `feature/collection/.../DlnaSetupScreen.kt`
- Create: `feature/collection/src/test/.../DlnaSetupControllerTest.kt`
- Modify: `feature/collection/build.gradle.kts` — `implementation(project(":source:dlna"))`, OkHttp if needed transitively

- [ ] **Step 1: Controller API + failing persist test**

```kotlin
class DlnaSetupController(
    private val database: KelliKanvasDatabase,
    private val discoverProfiles: suspend () -> List<Pair<String, DlnaProfile>>, // friendlyName to profile
    private val resolveManual: suspend (String) -> DlnaProfile,
    private val adapterFactory: (DlnaProfile) -> SourceAdapter,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun discover(): List<DiscoveredServer>
    suspend fun resolveHost(input: String): DiscoveredServer
    suspend fun listChildren(profile: DlnaProfile, folderObjectId: String = "0"): List<BrowseEntry>
    suspend fun saveSelection(
        profile: DlnaProfile,
        friendlyName: String,
        folders: List<SelectedFolder>, // objectId, label, includeDescendants
    ): String
}

data class DiscoveredServer(val friendlyName: String, val profile: DlnaProfile)
data class BrowseEntry(val objectId: String, val title: String, val isFolder: Boolean)
data class SelectedFolder(val objectId: String, val label: String, val includeDescendants: Boolean)
```

`saveSelection` upserts `SourceProfile(kind=DLNA)`, `DlnaConnection`, merges selected roots into `default` collection (same merge pattern as SAF).

Unit test: fake discover + fake adapter listing one folder → save → assert `dlnaConnections.get` and roots.

- [ ] **Step 2: Screen states**

Compose steps: Discovering / Results+Manual field / Browsing / Confirm include-subfolders / Saving / Error with Retry.

Use Material3 buttons only.

- [ ] **Step 3: PASS tests + commit**

```
git commit -m "feat(collection): QNAP DLNA discover browse and save"
```

---

### Task 8: App wiring — DI, shell load, navigation

**Files:**
- Modify: `app/.../AppContainer.kt`
- Modify: `app/.../KelliKanvasNavHost.kt`
- Modify: `app/.../settings/SettingsPlaceholderScreen.kt` (TopAppBar optional)
- Modify: `feature/setup/.../SafSetupScreen.kt` — remove `onOpenMenu` if Home overflow covers it, or keep Back to Collection

- [ ] **Step 1: AppContainer**

```kotlin
class AppContainer(appContext: Context) {
    val database = KelliKanvasDatabaseFactory.create(appContext)
    val preferences = DataStoreAppPreferencesRepository.create(appContext)
    val contentResolver = appContext.contentResolver
    val httpClient = OkHttpClient()
    private val wifiManager = appContext.applicationContext.getSystemService(WifiManager::class.java)

    fun safAdapter(profile: SafProfile) = SafSourceAdapter(profile, ContentResolverSafDocuments(contentResolver))

    fun dlnaAdapter(profile: DlnaProfile) = DlnaSourceAdapter.network(profile, httpClient)

    fun dlnaDiscovery(): DlnaProfileDiscovery =
        DlnaProfileDiscovery(SsdpDiscoverer(multicastLock = AndroidMulticastLock(wifiManager)), httpClient)

    fun dlnaManualResolver() = DlnaManualResolver(httpClient)
}
```

- [ ] **Step 2: Rewrite `loadShellState`**

```kotlin
private data class ShellState(
    val route: ShellRoute,
    val collectionLabel: String = "",
    val roots: List<SelectedRoot> = emptyList(),
    val adapters: Map<SourceProfileId, SourceAdapter> = emptyMap(),
)

private suspend fun loadShellState(container: AppContainer): ShellState {
    val collections = container.database.collections.list()
    val rootsByCollection = collections.associate { it.id to container.database.selectedRoots.list(it.id) }
    if (!ShellStartup.hasPlayableRoots(collections, rootsByCollection)) {
        return ShellState(ShellRoute.Home, "KelliKanvas")
    }
    val active = collections.first { rootsByCollection[it.id].orEmpty().isNotEmpty() }
    val roots = rootsByCollection.getValue(active.id)
    val adapters = linkedMapOf<SourceProfileId, SourceAdapter>()
    for (profileId in roots.map { it.profileId }.distinct()) {
        container.database.safConnections.get(profileId)?.let { conn ->
            val treeUri = conn.treeUri.toUri()
            if (container.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }) {
                val grant = SafTreeGrant(
                    treeUri = treeUri,
                    documentId = DocumentsContract.getTreeDocumentId(treeUri),
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                adapters[profileId] = container.safAdapter(SafProfile(profileId, grant))
            }
        }
        container.database.dlnaConnections.get(profileId)?.let { conn ->
            val profile = DlnaProfile(
                id = profileId,
                serverUdn = conn.serverUdn,
                descriptionLocation = URI(conn.descriptionLocation),
                controlUrl = URI(conn.controlUrl),
                contentDirectoryVersion = conn.contentDirectoryVersion,
            )
            adapters[profileId] = container.dlnaAdapter(profile)
        }
    }
    val playableRoots = roots.filter { it.profileId in adapters }
    return ShellState(
        route = ShellRoute.Home,
        collectionLabel = active.label,
        roots = playableRoots,
        adapters = adapters,
    )
}
```

`canStartSlideshow = playableRoots.isNotEmpty()`.

Slideshow route:

```kotlin
SimpleSlideshowScreen(adapters = state.adapters, roots = state.roots, ...)
```

- [ ] **Step 3: Routes**

```kotlin
const val COLLECTION = "collection"
const val DLNA_SETUP = "dlna_setup"
```

Home overflow → Collection.  
Collection → Setup (SAF) or DLNA_SETUP.  
After either finishes → `loadShellState` + pop to Collection or Home.

Wire `DlnaSetupController` discover via:

```kotlin
suspend {
    container.dlnaDiscovery().setup().map { profile ->
        // friendly name: prefer connection display later; for discovery parse from description or use UDN short form
        DiscoveredServer(profile.id.value, profile) // improve: store friendlyName from DeviceDescriptionParser by extending discover to return names
    }
}
```

**Improve discovery names:** extend `DlnaProfileDiscovery.setup()` return type **or** add `setupNamed(): List<Pair<String, DlnaProfile>>` that keeps `description.friendlyName` from `resolve`. Prefer adding:

```kotlin
data class DiscoveredDlnaServer(val friendlyName: String, val profile: DlnaProfile)
suspend fun setupNamed(): List<DiscoveredDlnaServer>
```

without breaking `setup()` (delegate).

- [ ] **Step 4: Compile app**

```
.\gradlew.bat :app:assembleDebug
```

- [ ] **Step 5: Commit**

```
git commit -m "feat(app): wire collection hub and QNAP DLNA into navigation"
```

---

### Task 9: Verification gate

- [ ] **Step 1: Unit tests**

```
.\gradlew.bat :core:catalog:testDebugUnitTest :source:dlna:testDebugUnitTest :feature:setup:testDebugUnitTest :feature:collection:testDebugUnitTest :feature:slideshow:testDebugUnitTest :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Lint/ktlint as required by CI**

```
.\gradlew.bat :app:ktlintCheck :app:lintDebug
```

- [ ] **Step 3: Device smoke (S21 / wireless adb)**

1. Open app → Home shows Start only + overflow ⋮  
2. ⋮ → Collection → Add local folder still works  
3. Add QNAP → Discover or enter `192.168.68.81` (or description URL) → pick folder → save  
4. Start slideshow — photos from SAF and/or QNAP  

```
.\gradlew.bat :app:installDebug
adb shell am start -n com.jedon.kellikanvas/.MainActivity
```

- [ ] **Step 4: Final commit if fixes needed; push `feature/saf-shell`**

```
git push -u origin HEAD
```

---

## Spec coverage checklist

| Spec requirement | Task |
|------------------|------|
| Overflow menu; Home Start only | 5 |
| Collection hub list / add local / add QNAP / remove | 2, 6, 7, 8 |
| SSDP discover + manual host | 3, 7, 8 |
| Persist `dlna_connections` + migration | 1 |
| SAF + DLNA coexist | 2, 4, 8 |
| Multi-adapter slideshow | 4, 8 |
| Skip failed root when others work | 4 |
| Placeholders via overflow | 5, 8 |
| Device verification | 9 |

## Self-review notes

- No SMB/HTTP/SurfaceView/full settings in this plan (YAGNI per spec).
- `SafSetupController` wipe behavior is explicitly fixed in Task 2 — required for coexistence.
- `loadShellState` currently returns a single SAF root; Task 8 replaces that with multi-root multi-adapter maps.
- Manual resolver candidate URLs are explicit; full pasted description URLs also work.

package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SafConnection
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.dlna.DlnaProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DlnaSetupControllerTest {
    private lateinit var database: KelliKanvasDatabase
    private lateinit var profile: DlnaProfile
    private lateinit var adapter: FakeDlnaAdapter

    @Before
    fun setUp() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
        profile = DlnaProfile(
            id = SourceProfileId("dlna-qnap"),
            serverUdn = "uuid:qnap-test",
            descriptionLocation = URI("http://192.168.1.20:8200/rootDesc.xml"),
            controlUrl = URI("http://192.168.1.20:8200/ctl/ContentDir"),
            contentDirectoryVersion = 1,
        )
        adapter = FakeDlnaAdapter(profile.id)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun discoverAndResolveHost_mapProfilesToDisplayNames() = runTest {
        val controller = controller()

        assertThat(controller.discover())
            .containsExactly(DiscoveredServer("Living room QNAP", profile))
        assertThat(controller.resolveHost("  qnap.local  "))
            .isEqualTo(DiscoveredServer("qnap.local", profile))
    }

    @Test
    fun listChildren_returnsFoldersAndIgnoresPhotos() = runTest {
        val entries = controller().listChildren(profile)

        assertThat(entries)
            .containsExactly(BrowseEntry(objectId = "photos", title = "Family photos", isFolder = true))
        assertThat(adapter.requestedFolder)
            .isEqualTo(FolderRef(profile.id, ProviderObjectId("0")))
    }

    @Test
    fun saveSelection_persistsDlnaSelectionAndRetainsExistingSafRoot() = runTest {
        val collectionId = CatalogIds.DEFAULT_COLLECTION_ID
        val safProfileId = SourceProfileId("saf-existing")
        val safRoot = SelectedRoot(
            collectionId = collectionId,
            profileId = safProfileId,
            objectId = ProviderObjectId("primary:Pictures"),
            displayLabel = "Local pictures",
            includeDescendants = true,
        )
        database.sourceProfiles.upsert(
            SourceProfile(
                id = safProfileId,
                kind = SourceKind.SAF,
                displayName = "Local",
                createdAtMillis = 1L,
            ),
        )
        database.safConnections.upsert(
            SafConnection(
                profileId = safProfileId,
                treeUri = "content://com.example.documents/tree/primary%3APictures",
            ),
        )
        database.collections.upsert(CatalogCollection(collectionId, "Existing collection"))
        database.selectedRoots.replaceAllForCollection(collectionId, listOf(safRoot))

        val result = controller().saveSelection(
            profile = profile,
            friendlyName = "Living room QNAP",
            folders = listOf(
                SelectedFolder(
                    objectId = "photos",
                    label = "Family photos",
                    includeDescendants = true,
                ),
            ),
        )

        assertThat(result).isEqualTo(collectionId)
        assertThat(database.sourceProfiles.get(profile.id)?.kind)
            .isEqualTo(SourceProfileKind.Known(SourceKind.DLNA))
        assertThat(database.sourceProfiles.get(profile.id)?.displayName).isEqualTo("Living room QNAP")
        assertThat(database.dlnaConnections.get(profile.id)?.descriptionLocation)
            .isEqualTo(profile.descriptionLocation.toString())
        assertThat(database.dlnaConnections.get(profile.id)?.controlUrl)
            .isEqualTo(profile.controlUrl.toString())
        assertThat(database.collections.get(collectionId))
            .isEqualTo(CatalogCollection(collectionId, "Existing collection"))
        assertThat(database.selectedRoots.list(collectionId))
            .containsExactly(
                safRoot,
                SelectedRoot(
                    collectionId = collectionId,
                    profileId = profile.id,
                    objectId = ProviderObjectId("photos"),
                    displayLabel = "Family photos",
                    includeDescendants = true,
                ),
            )
    }

    private fun controller() = DlnaSetupController(
        database = database,
        discoverProfiles = { listOf("Living room QNAP" to profile) },
        resolveManual = { profile },
        adapterFactory = { adapter },
        nowMillis = { 123L },
    )
}

private class FakeDlnaAdapter(
    override val profileId: SourceProfileId,
) : SourceAdapter() {
    override val kind: SourceKind = SourceKind.DLNA
    override val capabilities: SourceCapabilities = SourceCapabilities()
    var requestedFolder: FolderRef? = null

    override suspend fun probe(): SourceStatus = SourceStatus(true, "Available")

    override suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry> {
        requestedFolder = folder
        return Page(
            listOf(
                SourceEntry.Folder(
                    ref = FolderRef(profileId, ProviderObjectId("photos")),
                    name = "Family photos",
                ),
                SourceEntry.Photo(
                    asset = AssetRef(
                        profileId = profileId,
                        objectId = ProviderObjectId("photo-1"),
                        mimeType = "image/jpeg",
                    ),
                    name = "Ignored photo",
                ),
            ),
        )
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = error("Not used")

    override suspend fun openStream(asset: AssetRef): PhotoByteStream = error("Not used")
}

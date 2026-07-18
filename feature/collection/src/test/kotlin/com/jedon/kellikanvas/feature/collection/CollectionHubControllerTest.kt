package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.DlnaConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SafConnection
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.feature.setup.SafSetupController
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollectionHubControllerTest {
    private lateinit var database: KelliKanvasDatabase
    private lateinit var controller: CollectionHubController

    @Before
    fun setUp() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
        controller = CollectionHubController(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun listRoots_returnsDefaultCollectionRoots() = runTest {
        val collectionId = SafSetupController.DEFAULT_COLLECTION_ID
        val safId = SourceProfileId("saf-1")
        val dlnaId = SourceProfileId("dlna-1")
        seedProfileWithSaf(safId, "content://tree/1", "doc-1")
        seedProfileWithDlna(dlnaId)
        database.collections.upsert(CatalogCollection(collectionId, "Default"))
        database.selectedRoots.replaceAllForCollection(
            collectionId,
            listOf(
                root(collectionId, safId, "doc-1", "SAF"),
                root(collectionId, dlnaId, "0", "DLNA"),
            ),
        )

        val roots = controller.listRoots()

        assertThat(roots).hasSize(2)
        assertThat(roots.map { it.profileId }).containsExactly(safId, dlnaId)
    }

    @Test
    fun removeRoot_deletesSingleRootAndRetiresProfileWhenLast() = runTest {
        val collectionId = SafSetupController.DEFAULT_COLLECTION_ID
        val safId = SourceProfileId("saf-only")
        seedProfileWithSaf(safId, "content://tree/only", "doc-only")
        val root = root(collectionId, safId, "doc-only", "Only")
        database.collections.upsert(CatalogCollection(collectionId, "Default"))
        database.selectedRoots.replaceAllForCollection(collectionId, listOf(root))

        controller.removeRoot(root)

        assertThat(controller.listRoots()).isEmpty()
        assertThat(database.sourceProfiles.get(safId)).isNull()
        assertThat(database.safConnections.get(safId)).isNull()
    }

    @Test
    fun removeRoot_keepsProfileWhenOtherRootsRemain() = runTest {
        val collectionId = SafSetupController.DEFAULT_COLLECTION_ID
        val dlnaId = SourceProfileId("dlna-multi")
        seedProfileWithDlna(dlnaId)
        val first = root(collectionId, dlnaId, "0", "Folder A")
        val second = root(collectionId, dlnaId, "1", "Folder B")
        database.collections.upsert(CatalogCollection(collectionId, "Default"))
        database.selectedRoots.replaceAllForCollection(collectionId, listOf(first, second))

        controller.removeRoot(first)

        assertThat(controller.listRoots()).containsExactly(second)
        assertThat(database.sourceProfiles.get(dlnaId)).isNotNull()
        assertThat(database.dlnaConnections.get(dlnaId)).isNotNull()
    }

    private suspend fun seedProfileWithSaf(
        profileId: SourceProfileId,
        treeUri: String,
        documentId: String,
    ) {
        database.sourceProfiles.upsert(
            SourceProfile(
                id = profileId,
                kind = SourceKind.SAF,
                displayName = "SAF",
                createdAtMillis = 1L,
            ),
        )
        database.safConnections.upsert(
            SafConnection(profileId = profileId, treeUri = treeUri),
        )
    }

    private suspend fun seedProfileWithDlna(profileId: SourceProfileId) {
        database.sourceProfiles.upsert(
            SourceProfile(
                id = profileId,
                kind = SourceKind.DLNA,
                displayName = "DLNA",
                createdAtMillis = 1L,
            ),
        )
        database.dlnaConnections.upsert(
            DlnaConnection(
                profileId = profileId,
                serverUdn = "uuid:test",
                descriptionLocation = "http://example/rootDesc.xml",
                controlUrl = "http://example/ctl/ContentDir",
                contentDirectoryVersion = 1,
                displayName = "DLNA",
            ),
        )
    }

    private fun root(
        collectionId: String,
        profileId: SourceProfileId,
        objectId: String,
        label: String,
    ) = SelectedRoot(
        collectionId = collectionId,
        profileId = profileId,
        objectId = ProviderObjectId(objectId),
        displayLabel = label,
        includeDescendants = true,
    )
}

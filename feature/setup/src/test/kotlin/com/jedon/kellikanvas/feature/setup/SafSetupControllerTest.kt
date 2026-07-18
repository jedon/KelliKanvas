package com.jedon.kellikanvas.feature.setup

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.CatalogIds
import com.jedon.kellikanvas.catalog.DlnaConnection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.SourceProfile
import com.jedon.kellikanvas.catalog.SourceProfileKind
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafTreeGrant
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
class SafSetupControllerTest {
    private lateinit var database: KelliKanvasDatabase

    @Before
    fun setUp() {
        database = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `complete persists SAF profile connection collection and selected root`() = runTest {
        val profileId = SourceProfileId("saf-test")
        val grant = SafTreeGrant(
            treeUri = Uri.parse("content://com.example.documents/tree/primary%3APictures"),
            documentId = "primary:Pictures",
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        val controller = SafSetupController(database)

        controller.complete(
            profile = SafProfile(profileId, grant),
            displayName = "Pictures",
            includeDescendants = false,
        )

        val profile = database.sourceProfiles.get(profileId)
        assertThat(profile?.kind).isEqualTo(SourceProfileKind.Known(SourceKind.SAF))
        assertThat(database.safConnections.get(profileId)?.treeUri).isEqualTo(grant.treeUri.toString())
        assertThat(database.collections.get(CatalogIds.DEFAULT_COLLECTION_ID))
            .isEqualTo(CatalogCollection("default", "Pictures"))

        val root = database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID).single()
        assertThat(root.profileId).isEqualTo(profileId)
        assertThat(root.objectId.value).isEqualTo(grant.documentId)
        assertThat(root.includeDescendants).isFalse()
    }

    @Test
    fun `complete appends new saf profile while keeping existing saf profiles`() = runTest {
        val firstId = SourceProfileId("saf-old")
        val secondId = SourceProfileId("saf-new")
        val firstGrant = SafTreeGrant(
            treeUri = Uri.parse("content://com.example.documents/tree/primary%3AOld"),
            documentId = "primary:Old",
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        val secondGrant = SafTreeGrant(
            treeUri = Uri.parse("content://com.example.documents/tree/primary%3ANew"),
            documentId = "primary:New",
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        val controller = SafSetupController(database)

        controller.complete(
            profile = SafProfile(firstId, firstGrant),
            displayName = "Old",
            includeDescendants = true,
        )
        controller.complete(
            profile = SafProfile(secondId, secondGrant),
            displayName = "New",
            includeDescendants = false,
        )

        val roots = database.selectedRoots.list(CatalogIds.DEFAULT_COLLECTION_ID)
        assertThat(roots).hasSize(2)
        assertThat(roots.map { it.profileId }).containsExactly(firstId, secondId)
        assertThat(database.sourceProfiles.get(firstId)).isNotNull()
        assertThat(database.safConnections.get(firstId)).isNotNull()
        assertThat(database.sourceProfiles.get(secondId)).isNotNull()
        assertThat(database.collections.get(CatalogIds.DEFAULT_COLLECTION_ID))
            .isEqualTo(CatalogCollection("default", "Old"))
    }

    @Test
    fun complete_appendsSafWithoutDeletingOtherProfiles() = runTest {
        val dlnaId = SourceProfileId("dlna-nas")
        val safId = SourceProfileId("saf-pictures")
        val collectionId = CatalogIds.DEFAULT_COLLECTION_ID
        database.sourceProfiles.upsert(
            SourceProfile(
                id = dlnaId,
                kind = SourceKind.DLNA,
                displayName = "QNAP",
                createdAtMillis = 1L,
            ),
        )
        database.dlnaConnections.upsert(
            DlnaConnection(
                profileId = dlnaId,
                serverUdn = "uuid:qnap-1",
                descriptionLocation = "http://192.168.1.1:8200/rootDesc.xml",
                controlUrl = "http://192.168.1.1:8200/ctl/ContentDir",
                contentDirectoryVersion = 1,
                displayName = "QNAP",
            ),
        )
        database.collections.upsert(CatalogCollection(collectionId, "Mixed"))
        database.selectedRoots.replaceAllForCollection(
            collectionId,
            listOf(
                SelectedRoot(
                    collectionId = collectionId,
                    profileId = dlnaId,
                    objectId = ProviderObjectId("0"),
                    displayLabel = "Photos",
                    includeDescendants = true,
                ),
            ),
        )
        val grant = SafTreeGrant(
            treeUri = Uri.parse("content://com.example.documents/tree/primary%3APictures"),
            documentId = "primary:Pictures",
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )

        SafSetupController(database).complete(
            profile = SafProfile(safId, grant),
            displayName = "Pictures",
            includeDescendants = false,
        )

        val roots = database.selectedRoots.list(collectionId)
        assertThat(roots).hasSize(2)
        assertThat(roots.map { it.profileId }).containsExactly(dlnaId, safId)
        assertThat(database.sourceProfiles.get(dlnaId)).isNotNull()
        assertThat(database.dlnaConnections.get(dlnaId)).isNotNull()
        assertThat(database.collections.get(collectionId))
            .isEqualTo(CatalogCollection(collectionId, "Mixed"))
    }
}

package com.jedon.kellikanvas.feature.setup

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.KelliKanvasDatabase
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SourceProfileKind
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
        assertThat(database.collections.get(SafSetupController.DEFAULT_COLLECTION_ID))
            .isEqualTo(CatalogCollection("default", "Pictures"))

        val root = database.selectedRoots.list(SafSetupController.DEFAULT_COLLECTION_ID).single()
        assertThat(root.profileId).isEqualTo(profileId)
        assertThat(root.objectId.value).isEqualTo(grant.documentId)
        assertThat(root.includeDescendants).isFalse()
    }
}

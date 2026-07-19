package com.jedon.kellikanvas.catalog

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafConnectionDaoTest {
    @Test
    fun upsertAndReadTreeUriByProfileId() = runBlocking {
        val db = KelliKanvasDatabaseFactory.inMemory(RuntimeEnvironment.getApplication())
        val profileId = SourceProfileId("saf-1")
        db.sourceProfiles.upsert(
            SourceProfile(
                id = profileId,
                kind = SourceKind.SAF,
                displayName = "Photos",
                createdAtMillis = 1L,
            ),
        )
        db.safConnections.upsert(SafConnection(profileId = profileId, treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"))
        assertThat(db.safConnections.get(profileId)?.treeUri)
            .isEqualTo("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        assertThat(db.collections.list()).isEmpty()
        db.collections.upsert(CatalogCollection(id = "c1", label = "Main"))
        assertThat(db.collections.list().map { it.id }).containsExactly("c1")
        db.close()
    }

    @Test
    fun rejectsMultilineTreeUri() {
        val failure = runCatching {
            SafConnection(SourceProfileId("saf-1"), "content://foo\nbar")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}

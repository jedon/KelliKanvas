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

package com.jedon.kellikanvas.source.smb

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Test

class SmbSourceAdapterTest {
    @Test
    fun listChildren_pagesFoldersAndPhotos() = runTest {
        val profile =
            SmbProfile(
                id = SourceProfileId("smb-test"),
                host = "192.168.0.1",
                share = "Kelli",
                username = "fake-user",
            )
        val backend =
            FakeSmbBackend(
                listOf(
                    SmbEntry("Digital Photos", "Digital Photos", true, null, null, null),
                    SmbEntry("readme.txt", "readme.txt", false, 12, 1L, null),
                    SmbEntry("shot.jpg", "shot.jpg", false, 100, 2L, "image/jpeg"),
                ),
            )
        val adapter = SmbSourceAdapter(profile, backend)

        val page =
            adapter.listChildren(
                folder = FolderRef(profile.id, ProviderObjectId(SmbSourceAdapter.ROOT_OBJECT_ID)),
                cursor = null,
                limit = 10,
            )

        assertThat(page.items).hasSize(2)
        assertThat((page.items[0] as SourceEntry.Folder).name).isEqualTo("Digital Photos")
        assertThat((page.items[1] as SourceEntry.Photo).name).isEqualTo("shot.jpg")
        assertThat(adapter.probe().available).isTrue()
    }

    private class FakeSmbBackend(
        private val entries: List<SmbEntry>,
    ) : SmbBackend {
        override suspend fun probe() = Unit

        override suspend fun list(path: String): List<SmbEntry> = entries

        override suspend fun metadata(path: String): SmbEntry = entries.first { it.path == path }

        override suspend fun open(path: String): PhotoByteStream = object : PhotoByteStream(0) {
            override suspend fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = -1

            override fun close() = Unit
        }
    }
}

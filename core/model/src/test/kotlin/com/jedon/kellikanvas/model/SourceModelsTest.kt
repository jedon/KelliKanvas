package com.jedon.kellikanvas.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceModelsTest {
    private val profileId = SourceProfileId("profile")
    private val objectId = ProviderObjectId("object")

    @Test
    fun `asset reference rejects negative byte length`() {
        assertThrows(IllegalArgumentException::class.java) {
            asset(byteLength = -1)
        }
    }

    @Test
    fun `asset reference rejects negative modified time`() {
        assertThrows(IllegalArgumentException::class.java) {
            asset(modifiedAtMillis = -1)
        }
    }

    @Test
    fun `source entries reject blank names`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceEntry.Folder(FolderRef(profileId, objectId), " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceEntry.Photo(asset(), "", width = 1, height = 1)
        }
    }

    @Test
    fun `photo entries reject negative dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceEntry.Photo(asset(), "photo", width = -1, height = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceEntry.Photo(asset(), "photo", width = 1, height = -1)
        }
    }

    @Test
    fun `photo metadata rejects negative dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoMetadata(asset(), width = -1, height = 1)
        }
    }

    @Test
    fun `page carries entries and an opaque next cursor`() {
        val entry = SourceEntry.Folder(FolderRef(profileId, objectId), "Photos")
        val cursor = PageCursor("next-page-secret")

        val page = Page(listOf(entry), cursor)

        assertThat(page.items).containsExactly(entry)
        assertThat(page.nextCursor).isEqualTo(cursor)
    }

    @Test
    fun `source status exposes negotiated security without unsafe details`() {
        val status =
            SourceStatus(
                available = true,
                summary = "Connected",
                negotiatedProtocol = "SMB 3.1.1",
                signingEnabled = true,
                encryptionEnabled = true,
            )

        assertThat(status.negotiatedProtocol).isEqualTo("SMB 3.1.1")
        assertThat(status.signingEnabled).isTrue()
        assertThat(status.encryptionEnabled).isTrue()
        assertThrows(IllegalArgumentException::class.java) {
            status.copy(summary = "Connected to https://private.test/photos")
        }
    }

    @Test
    fun `model toString output never exposes URIs or version tokens`() {
        val uri = "https://private.test/photo.jpg"
        val secret = "credential-secret"
        val ref =
            AssetRef(
                profileId = profileId,
                objectId = ProviderObjectId(uri),
                mimeType = "image/jpeg",
                byteLength = 42,
                modifiedAtMillis = 10,
                eTag = secret,
                versionToken = "password=hidden",
            )
        val photo = SourceEntry.Photo(ref, uri, width = 100, height = 200)

        assertThat(ref.toString()).doesNotContain(uri)
        assertThat(ref.toString()).doesNotContain(secret)
        assertThat(photo.toString()).doesNotContain(uri)
        assertThat(photo.toString()).doesNotContain("hidden")
    }

    @Test
    fun `source kinds cover every approved adapter`() {
        assertThat(SourceKind.entries)
            .containsExactly(SourceKind.DLNA, SourceKind.SMB, SourceKind.SAF, SourceKind.HTTP)
            .inOrder()
    }

    private fun asset(
        byteLength: Long? = null,
        modifiedAtMillis: Long? = null,
    ) = AssetRef(
        profileId = profileId,
        objectId = objectId,
        mimeType = "image/jpeg",
        byteLength = byteLength,
        modifiedAtMillis = modifiedAtMillis,
    )
}

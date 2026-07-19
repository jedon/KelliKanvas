package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Test
import java.io.IOException

class DlnaIoFailureTest {
    @Test
    fun `socket IO failure normalizes without leaking endpoint detail`() = runTest {
        val adapter = failingAdapter(IOException("http://192.168.1.4/private.jpg?token=secret"))

        val failure = runCatching { adapter.probe() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SourceFailure.SourceUnavailable::class.java)
        assertThat(failure.toString()).doesNotContain("192.168.1.4")
        assertThat(failure.toString()).doesNotContain("secret")
    }

    @Test
    fun `cancellation remains unchanged through adapter normalization`() = runTest {
        val cancellation = CancellationException("stop")
        val adapter = failingAdapter(cancellation)

        val failure = runCatching { adapter.probe() }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
    }

    @Test
    fun `OkHttp canceled IOException remaps to CancellationException not SourceUnavailable`() = runTest {
        val adapter = failingAdapter(IOException("Canceled"))

        val failure = runCatching { adapter.probe() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).isNotInstanceOf(SourceFailure::class.java)
    }

    @Test
    fun `Socket closed IOException remaps to CancellationException`() = runTest {
        val adapter = failingAdapter(IOException("Socket closed"))

        val failure = runCatching { adapter.probe() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).isNotInstanceOf(SourceFailure::class.java)
    }

    @Test
    fun `IOException with CancellationException cause remaps to CancellationException`() = runTest {
        val cancel = CancellationException("upstream cancel")
        val adapter = failingAdapter(IOException("stream reset", cancel))

        val failure = runCatching { adapter.probe() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).isNotInstanceOf(SourceFailure::class.java)
    }

    @Test
    fun `photo stream canceled IOException remaps to CancellationException`() = runTest {
        val profileId = SourceProfileId("io-profile")
        val adapter =
            DlnaSourceAdapter(
                DlnaProfile(profileId, "uuid:io"),
                object : DlnaBackend {
                    override val serverUdn = "uuid:io"
                    override suspend fun probe() = Unit
                    override suspend fun browse(objectId: String, start: Int, count: Int): DlnaBrowsePage = error("unused")
                    override suspend fun metadata(objectId: String): DlnaObject = error("unused")
                    override suspend fun open(objectId: String): PhotoByteStream = object : PhotoByteStream(null) {
                        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw IOException("Canceled")

                        override fun close() = Unit
                    }
                },
            )
        val asset =
            com.jedon.kellikanvas.model.AssetRef(
                profileId,
                com.jedon.kellikanvas.model.ProviderObjectId("uuid:io\u0000photo"),
                "image/jpeg",
            )

        val failure =
            adapter.open(asset).use { stream ->
                runCatching { stream.read(Buffer(), 1) }.exceptionOrNull()
            }

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).isNotInstanceOf(SourceFailure::class.java)
    }

    @Test
    fun `photo stream IO failure is privacy safe`() = runTest {
        val profileId = SourceProfileId("io-profile")
        val adapter =
            DlnaSourceAdapter(
                DlnaProfile(profileId, "uuid:io"),
                object : DlnaBackend {
                    override val serverUdn = "uuid:io"
                    override suspend fun probe() = Unit
                    override suspend fun browse(objectId: String, start: Int, count: Int): DlnaBrowsePage = error("unused")
                    override suspend fun metadata(objectId: String): DlnaObject = error("unused")
                    override suspend fun open(objectId: String): PhotoByteStream = object : PhotoByteStream(null) {
                        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw IOException(
                            "http://192.168.1.4/private.jpg?token=secret",
                        )

                        override fun close() = Unit
                    }
                },
            )
        val asset =
            com.jedon.kellikanvas.model.AssetRef(
                profileId,
                com.jedon.kellikanvas.model.ProviderObjectId("uuid:io\u0000photo"),
                "image/jpeg",
            )

        val failure =
            adapter.open(asset).use { stream ->
                runCatching { stream.read(Buffer(), 1) }.exceptionOrNull()
            }

        assertThat(failure).isInstanceOf(SourceFailure.SourceUnavailable::class.java)
        assertThat(failure.toString()).doesNotContain("192.168.1.4")
        assertThat(failure.toString()).doesNotContain("secret")
    }

    private fun failingAdapter(failure: Throwable): DlnaSourceAdapter = DlnaSourceAdapter(
        DlnaProfile(SourceProfileId("io-profile"), "uuid:io"),
        object : DlnaBackend {
            override val serverUdn = "uuid:io"
            override suspend fun probe(): Unit = throw failure
            override suspend fun browse(objectId: String, start: Int, count: Int): DlnaBrowsePage = throw failure
            override suspend fun metadata(objectId: String): DlnaObject = throw failure
            override suspend fun open(objectId: String): PhotoByteStream = throw failure
        },
    )
}

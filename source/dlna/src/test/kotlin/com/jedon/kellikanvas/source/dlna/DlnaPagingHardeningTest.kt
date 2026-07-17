package com.jedon.kellikanvas.source.dlna

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DlnaPagingHardeningTest {
    private val profileId = SourceProfileId("paging-profile")
    private val udn = "uuid:paging"
    private val root = FolderRef(profileId, ProviderObjectId("$udn\u00000"))

    @Test
    fun `unknown zero total continues short pages until an empty page`() = runTest {
        val adapter =
            adapter { _, start, _ ->
                if (start == 0) {
                    page(objects = listOf(folder("a"), folder("b")), returned = 2, total = 0)
                } else if (start == 2) {
                    page(objects = listOf(folder("c")), returned = 1, total = 0)
                } else {
                    page(objects = emptyList(), returned = 0, total = 0)
                }
            }

        val first = adapter.listChildren(root, null, 2)
        val second = adapter.listChildren(root, first.nextCursor, 2)
        val third = adapter.listChildren(root, second.nextCursor, 2)

        assertThat(first.nextCursor).isNotNull()
        assertThat(second.nextCursor).isNotNull()
        assertThat(third.nextCursor).isNull()
        assertThat((first.items + second.items + third.items).map { it.name }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `unknown total continuation treats UPnP 720 as terminal`() = runTest {
        val adapter =
            adapter { _, start, _ ->
                if (start == 0) {
                    page(listOf(folder("first")), returned = 1, total = 0)
                } else {
                    throw DlnaIndexBeyondRangeException()
                }
            }

        val first = adapter.listChildren(root, null, 2)
        val terminal = adapter.listChildren(root, first.nextCursor, 2)

        assertThat(terminal.items).isEmpty()
        assertThat(terminal.nextCursor).isNull()
    }

    @Test
    fun `initial and known total UPnP 720 remain protocol failures`() = runTest {
        val initial = adapter { _, _, _ -> throw DlnaIndexBeyondRangeException() }
        assertProtocolFailure { initial.listChildren(root, null, 2) }

        val known =
            adapter { _, start, _ ->
                if (start == 0) {
                    page(listOf(folder("first")), returned = 1, total = 2)
                } else {
                    throw DlnaIndexBeyondRangeException()
                }
            }
        val first = known.listChildren(root, null, 1)

        assertProtocolFailure { known.listChildren(root, first.nextCursor, 1) }
    }

    @Test
    fun `unknown total terminal fault is scoped to cursor object`() = runTest {
        var browseCalls = 0
        val adapter =
            adapter { _, start, _ ->
                browseCalls++
                if (start == 0) {
                    page(listOf(folder("first")), returned = 1, total = 0)
                } else {
                    throw DlnaIndexBeyondRangeException()
                }
            }
        val first = adapter.listChildren(root, null, 2)
        val other = FolderRef(profileId, ProviderObjectId("$udn\u0000other"))

        assertProtocolFailure { adapter.listChildren(other, first.nextCursor, 2) }
        assertThat(browseCalls).isEqualTo(1)
        val terminal = adapter.listChildren(root, first.nextCursor, 2)

        assertThat(terminal.nextCursor).isNull()
    }

    @Test
    fun `paging rejects inconsistent counts duplicates and no progress`() = runTest {
        val inconsistent = adapter { _, _, _ -> page(listOf(folder("a")), returned = 2, total = 2) }
        val duplicates = adapter { _, _, _ -> page(listOf(folder("a"), folder("a")), returned = 2, total = 2) }
        val noProgress = adapter { _, _, _ -> page(emptyList(), returned = 0, total = 10) }

        assertProtocolFailure { inconsistent.listChildren(root, null, 2) }
        assertProtocolFailure { duplicates.listChildren(root, null, 2) }
        assertProtocolFailure { noProgress.listChildren(root, null, 2) }
    }

    @Test
    fun `paging rejects returned above request negative totals and cursor overflow`() = runTest {
        val aboveRequest = adapter { _, _, _ -> page(listOf(folder("a"), folder("b")), returned = 2, total = 2) }
        val negative = adapter { _, _, _ -> page(emptyList(), returned = -1, total = -1) }
        val overflow = adapter { _, _, _ -> page(listOf(folder("a")), returned = 1, total = 0) }

        assertProtocolFailure { aboveRequest.listChildren(root, null, 1) }
        assertProtocolFailure { negative.listChildren(root, null, 1) }
        assertProtocolFailure { overflow.listChildren(root, PageCursor(Int.MAX_VALUE.toString()), 1) }
    }

    @Test
    fun `paging rejects duplicates across pages and repeated cursors`() = runTest {
        val duplicateAcrossPages =
            adapter { _, _, _ -> page(listOf(folder("same")), returned = 1, total = 0) }
        val firstDuplicatePage = duplicateAcrossPages.listChildren(root, null, 1)
        assertProtocolFailure {
            duplicateAcrossPages.listChildren(root, firstDuplicatePage.nextCursor, 1)
        }

        val uniquePages =
            adapter { _, start, _ ->
                page(listOf(folder("item-$start")), returned = 1, total = 3)
            }
        val first = uniquePages.listChildren(root, null, 1)
        uniquePages.listChildren(root, first.nextCursor, 1)
        assertProtocolFailure {
            uniquePages.listChildren(root, first.nextCursor, 1)
        }
    }

    @Test
    fun `cursor remains retryable after transient failure and cancellation`() = runTest {
        var secondPageAttempts = 0
        val adapter =
            adapter { _, start, _ ->
                if (start == 0) {
                    page(listOf(folder("first")), returned = 1, total = 2)
                } else {
                    secondPageAttempts++
                    when (secondPageAttempts) {
                        1 -> throw java.io.IOException("temporary")
                        2 -> throw CancellationException("cancel")
                        else -> page(listOf(folder("second")), returned = 1, total = 2)
                    }
                }
            }
        val first = adapter.listChildren(root, null, 1)

        assertThat(runCatching { adapter.listChildren(root, first.nextCursor, 1) }.exceptionOrNull())
            .isInstanceOf(SourceFailure.SourceUnavailable::class.java)
        assertThat(runCatching { adapter.listChildren(root, first.nextCursor, 1) }.exceptionOrNull())
            .isInstanceOf(CancellationException::class.java)
        val retried = adapter.listChildren(root, first.nextCursor, 1)

        assertThat(retried.items.single().name).isEqualTo("second")
        assertThat(retried.nextCursor).isNull()
    }

    @Test
    fun `concurrent duplicate cursor use is rejected while owner can retry`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondPageAttempts = 0
        val adapter =
            adapter { _, start, _ ->
                if (start == 0) {
                    page(listOf(folder("first")), returned = 1, total = 2)
                } else {
                    secondPageAttempts++
                    if (secondPageAttempts == 1) {
                        started.complete(Unit)
                        release.await()
                    }
                    page(listOf(folder("second")), returned = 1, total = 2)
                }
            }
        val first = adapter.listChildren(root, null, 1)
        val owner = async { adapter.listChildren(root, first.nextCursor, 1) }
        started.await()

        assertProtocolFailure { adapter.listChildren(root, first.nextCursor, 1) }
        owner.cancel(CancellationException("owner cancelled"))
        release.complete(Unit)
        runCatching { owner.await() }
        val retried = adapter.listChildren(root, first.nextCursor, 1)

        assertThat(retried.items.single().name).isEqualTo("second")
    }

    private fun adapter(browse: suspend (String, Int, Int) -> DlnaBrowsePage): DlnaSourceAdapter = DlnaSourceAdapter(
        DlnaProfile(profileId, udn),
        object : DlnaBackend {
            override val serverUdn = udn
            override suspend fun probe() = Unit
            override suspend fun browse(objectId: String, start: Int, count: Int) = browse(objectId, start, count)
            override suspend fun metadata(objectId: String): DlnaObject = error("unused")
            override suspend fun open(objectId: String): PhotoByteStream = error("unused")
        },
    )

    private fun folder(id: String) = DlnaObject(udn, id, "0", id, true, emptyList())

    private fun page(
        objects: List<DlnaObject>,
        returned: Int,
        total: Int,
    ) = DlnaBrowsePage(objects, returned, total)

    private suspend fun assertProtocolFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SourceFailure.ProtocolFailure::class.java)
    }
}

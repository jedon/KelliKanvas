package com.jedon.kellikanvas.source.testing

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.MAX_PAGE_LIMIT
import com.jedon.kellikanvas.source.PhotoByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.fail
import org.junit.Test

/**
 * JUnit 4 contract suite for every source adapter.
 *
 * Concrete suites subclass this type and return a fresh adapter in each [AdapterHarness] with
 * structurally equivalent deterministic datasets. Nullable scenario capabilities let sources such
 * as SAF omit credential behavior they do not possess while still requiring all declared credential
 * and grant scenarios.
 */
abstract class AdapterContract {
    protected abstract fun createHarness(): AdapterHarness

    @Test
    fun `paging honors limits and cursors neither skip nor duplicate entries`() = runTest {
        val harness = createHarness()
        val expected = harness.dataset.children(harness.root)
        require(expected.size >= 3) {
            "Contract root must contain at least three entries to exercise paging"
        }

        val actual = readAllPages(harness, harness.root, limit = 2)

        assertThat(actual).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `invalid limits are rejected before adapter IO`() = runTest {
        val harness = createHarness()
        val before = harness.ioCount()

        expectFailure<IllegalArgumentException> {
            harness.adapter.listChildren(harness.root, null, 0)
        }
        expectFailure<IllegalArgumentException> {
            harness.adapter.listChildren(harness.root, null, MAX_PAGE_LIMIT + 1)
        }

        assertThat(harness.ioCount()).isEqualTo(before)
    }

    @Test
    fun `provider IDs stay stable and nested folders traverse with cycle protection`() = runTest {
        val firstHarness = createHarness()
        val firstScan = traverse(firstHarness)
        val secondHarness = createHarness()
        val secondScan = traverse(secondHarness)

        assertThat(firstScan.keys).containsExactlyElementsIn(firstHarness.dataset.folders)
        firstHarness.dataset.folders.forEach { folder ->
            assertThat(firstScan.getValue(folder))
                .containsExactlyElementsIn(firstHarness.dataset.children(folder))
                .inOrder()
        }
        assertThat(entryIdentities(firstScan)).isEqualTo(entryIdentities(secondScan))
    }

    @Test
    fun `metadata agrees with listings and stays stable across adapters`() = runTest {
        val harness = createHarness()
        val freshHarness = createHarness()
        assertThat(freshHarness.adapter).isNotSameInstanceAs(harness.adapter)
        assertEquivalentDatasets(harness.dataset, freshHarness.dataset)
        val scan = traverse(harness)
        val listedPhotos = scan.values.flatten().filterIsInstance<SourceEntry.Photo>()

        listedPhotos.forEach { listed ->
            val expected = requireNotNull(harness.dataset.photo(listed.asset))
            val first = harness.adapter.metadata(listed.asset)
            val repeated = harness.adapter.metadata(listed.asset)
            val fromFreshAdapter = freshHarness.adapter.metadata(listed.asset)

            assertThat(first).isEqualTo(expected.metadata)
            assertThat(repeated).isEqualTo(first)
            assertThat(fromFreshAdapter).isEqualTo(first)
            assertThat(first.asset).isEqualTo(listed.asset)
            assertThat(first.width).isEqualTo(listed.width)
            assertThat(first.height).isEqualTo(listed.height)
        }
    }

    @Test
    fun `cross-profile references are rejected before adapter IO`() = runTest {
        val harness = createHarness()
        val otherProfile = SourceProfileId("contract-other-profile")
        val folder = FolderRef(otherProfile, ProviderObjectId("contract-other-folder"))
        val asset =
            AssetRef(
                profileId = otherProfile,
                objectId = ProviderObjectId("contract-other-photo"),
                mimeType = "image/jpeg",
            )
        val before = harness.ioCount()

        expectFailure<IllegalArgumentException> {
            harness.adapter.listChildren(folder, null)
        }
        expectFailure<IllegalArgumentException> {
            harness.adapter.metadata(asset)
        }
        expectFailure<IllegalArgumentException> {
            harness.adapter.open(asset)
        }

        assertThat(harness.ioCount()).isEqualTo(before)
    }

    @Test
    fun `listing cancellation propagates and closes its resource`() = runTest {
        val harness = createHarness()
        val stall = harness.stallNextListing()
        val caught = CompletableDeferred<CancellationException>()
        val job =
            launch {
                try {
                    harness.adapter.listChildren(harness.root, null)
                } catch (failure: CancellationException) {
                    caught.complete(failure)
                    throw failure
                }
            }

        stall.started.await()
        val cancellation = CancellationException("contract-listing-cancelled")
        job.cancel(cancellation)
        job.join()

        assertThat(caught.isCompleted).isTrue()
        assertThat(caught.await()).isSameInstanceAs(cancellation)
        assertThat(stall.closed.isCompleted).isTrue()
    }

    @Test
    fun `read cancellation propagates and closes its resource`() = runTest {
        val harness = createHarness()
        val asset = harness.dataset.photos.first().entry.asset
        val stall = harness.stallNextRead(asset)
        val caught = CompletableDeferred<CancellationException>()
        val opened = CompletableDeferred<Unit>()
        val job =
            launch {
                try {
                    harness.adapter.open(asset).use { stream ->
                        opened.complete(Unit)
                        stream.read(Buffer(), 1)
                    }
                } catch (failure: CancellationException) {
                    caught.complete(failure)
                    throw failure
                }
            }

        stall.started.await()
        assertThat(opened.isCompleted).isTrue()
        val cancellation = CancellationException("contract-read-cancelled")
        job.cancel(cancellation)
        job.join()

        assertThat(caught.isCompleted).isTrue()
        assertThat(caught.await()).isSameInstanceAs(cancellation)
        assertThat(stall.closed.isCompleted).isTrue()
    }

    @Test
    fun `missing items normalize to not found`() = runTest {
        val harness = createHarness()
        val asset = harness.dataset.photos.first().entry.asset
        harness.makeMissing(asset)

        val metadataFailure =
            expectFailure<SourceFailure.NotFound> {
                harness.adapter.metadata(asset)
            }
        val openFailure =
            expectFailure<SourceFailure.NotFound> {
                harness.adapter.open(asset)
            }

        assertThat(metadataFailure.profileId).isEqualTo(harness.adapter.profileId)
        assertThat(openFailure.profileId).isEqualTo(harness.adapter.profileId)
    }

    @Test
    fun `removed sources normalize to source unavailable`() = runTest {
        val harness = createHarness()
        harness.removeSource()

        val probeFailure =
            expectFailure<SourceFailure.SourceUnavailable> {
                harness.adapter.probe()
            }
        val listingFailure =
            expectFailure<SourceFailure.SourceUnavailable> {
                harness.adapter.listChildren(harness.root, null)
            }

        assertThat(probeFailure.profileId).isEqualTo(harness.adapter.profileId)
        assertThat(listingFailure.profileId).isEqualTo(harness.adapter.profileId)
    }

    @Test
    fun `configured invalid credentials normalize to authentication required`() = runTest {
        val harness = createHarness()
        val scenario = harness.scenarios.invalidCredential ?: return@runTest
        scenario.arrange()

        val failure =
            expectFailure<SourceFailure.AuthenticationRequired> {
                scenario.exercise(harness)
            }

        assertThat(failure.profileId).isEqualTo(harness.adapter.profileId)
        scenario.adapterSpecificAssertions(failure)
    }

    @Test
    fun `configured revoked grants normalize to permission revoked`() = runTest {
        val harness = createHarness()
        val scenario = harness.scenarios.revokedGrant ?: return@runTest
        scenario.arrange()

        val failure =
            expectFailure<SourceFailure.PermissionRevoked> {
                scenario.exercise(harness)
            }

        assertThat(failure.profileId).isEqualTo(harness.adapter.profileId)
        scenario.adapterSpecificAssertions(failure)
    }

    @Test
    fun `photo bytes stay stable across bounded fresh streams and adapters`() = runTest {
        val harness = createHarness()
        val freshHarness = createHarness()
        assertThat(freshHarness.adapter).isNotSameInstanceAs(harness.adapter)
        assertEquivalentDatasets(harness.dataset, freshHarness.dataset)

        harness.dataset.photos.forEach { expected ->
            val payload = expected.bytes
            require(payload.size > 1) {
                "Contract photos must contain multiple bytes to exercise bounded streaming"
            }
            val requestedChunkSize = minOf(4, payload.size - 1).toLong()
            val first = readPhoto(harness, expected, requestedChunkSize)
            val repeated = readPhoto(harness, expected, requestedChunkSize)
            val fromFreshAdapter = readPhoto(freshHarness, expected, requestedChunkSize)

            assertThat(first).isEqualTo(payload)
            assertThat(repeated).isEqualTo(first)
            assertThat(fromFreshAdapter).isEqualTo(first)
        }
    }

    @Test
    fun `photo stream invariant enforcement rejects an over-read`() = runTest {
        val invalidStream =
            object : PhotoByteStream(contentLength = null) {
                override suspend fun readAtMostTo(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    sink.write(byteArrayOf(1, 2))
                    return 2
                }

                override fun close() = Unit
            }

        expectFailure<IllegalStateException> {
            invalidStream.read(Buffer(), 1)
        }
    }

    @Test
    fun `diagnostics do not disclose source secrets or sensitive paths`() = runTest {
        val harness = createHarness()
        val diagnostics = mutableListOf<Any?>()
        diagnostics += harness
        diagnostics += harness.adapter
        diagnostics += harness.dataset
        diagnostics += harness.adapter.probe()
        diagnostics.addAll(harness.dataset.folders.flatMap(harness.dataset::children))
        diagnostics.addAll(harness.dataset.photos.map(ContractPhoto::metadata))
        diagnostics.addAll(harness.diagnostics())
        diagnostics += missingFailure()
        diagnostics += removedFailure()
        configuredAccessFailure(
            select = { it.scenarios.invalidCredential },
            expected = SourceFailure.AuthenticationRequired::class.java,
        )?.let(diagnostics::add)
        configuredAccessFailure(
            select = { it.scenarios.revokedGrant },
            expected = SourceFailure.PermissionRevoked::class.java,
        )?.let(diagnostics::add)

        diagnostics.forEach { diagnostic ->
            val rendered = diagnostic.toString()
            harness.sensitiveValues.forEach { sensitive ->
                assertThat(rendered.contains(sensitive)).isFalse()
            }
            FORBIDDEN_DIAGNOSTIC_PATTERNS.forEach { pattern ->
                assertThat(pattern.containsMatchIn(rendered)).isFalse()
            }
        }
    }

    private suspend fun readAllPages(
        harness: AdapterHarness,
        folder: FolderRef,
        limit: Int,
    ): List<SourceEntry> {
        val expected = harness.dataset.children(folder)
        val actual = mutableListOf<SourceEntry>()
        val seenCursors = mutableSetOf<PageCursor?>()
        var cursor: PageCursor? = null

        do {
            check(seenCursors.add(cursor)) { "Adapter repeated a page cursor" }
            val page = harness.adapter.listChildren(folder, cursor, limit)
            val expectedPageSize = minOf(limit, expected.size - actual.size)
            assertThat(page.items).hasSize(expectedPageSize)
            actual += page.items
            cursor = page.nextCursor
        } while (cursor != null)

        assertThat(actual.map(::entryIdentity).distinct()).hasSize(actual.size)
        return actual
    }

    private suspend fun readPhoto(
        harness: AdapterHarness,
        expected: ContractPhoto,
        requestedChunkSize: Long,
    ): ByteArray {
        val payload = expected.bytes
        val sink = Buffer()
        harness.adapter.open(expected.entry.asset).use { stream ->
            stream.contentLength?.let { assertThat(it).isEqualTo(payload.size.toLong()) }
            val firstRead = stream.read(sink, requestedChunkSize)
            assertThat(firstRead).isGreaterThan(0)
            assertThat(firstRead).isAtMost(requestedChunkSize)
            assertThat(firstRead).isLessThan(payload.size.toLong())

            while (true) {
                val read = stream.read(sink, requestedChunkSize)
                if (read == -1L) break
                assertThat(read).isAtMost(requestedChunkSize)
            }
        }
        return sink.readByteArray()
    }

    private fun assertEquivalentDatasets(
        expected: ContractDataset,
        actual: ContractDataset,
    ) {
        assertThat(actual.root).isEqualTo(expected.root)
        assertThat(actual.folders).containsExactlyElementsIn(expected.folders)
        expected.folders.forEach { folder ->
            assertThat(actual.children(folder))
                .containsExactlyElementsIn(expected.children(folder))
                .inOrder()
        }
        assertThat(actual.photos).hasSize(expected.photos.size)
        expected.photos.forEach { expectedPhoto ->
            val actualPhoto = requireNotNull(actual.photo(expectedPhoto.entry.asset))
            assertThat(actualPhoto.entry).isEqualTo(expectedPhoto.entry)
            assertThat(actualPhoto.metadata).isEqualTo(expectedPhoto.metadata)

            val expectedBytes = expectedPhoto.bytes
            val actualBytes = actualPhoto.bytes
            assertThat(actualBytes).isEqualTo(expectedBytes)
            assertThat(expectedPhoto.bytes === expectedBytes).isFalse()
            assertThat(actualPhoto.bytes === actualBytes).isFalse()
        }
    }

    private suspend fun traverse(harness: AdapterHarness): Map<FolderRef, List<SourceEntry>> {
        val pending = ArrayDeque<FolderRef>()
        val visited = linkedSetOf<FolderRef>()
        val entries = linkedMapOf<FolderRef, List<SourceEntry>>()
        pending += harness.root

        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            if (!visited.add(folder)) continue
            val children = readAllPages(harness, folder, limit = 2)
            entries[folder] = children
            children.filterIsInstance<SourceEntry.Folder>().forEach { pending += it.ref }
        }
        return entries
    }

    private fun entryIdentities(
        scan: Map<FolderRef, List<SourceEntry>>,
    ): Map<FolderRef, List<EntryIdentity>> = scan.mapValues { (_, entries) -> entries.map(::entryIdentity) }

    private fun entryIdentity(entry: SourceEntry): EntryIdentity = when (entry) {
        is SourceEntry.Folder -> EntryIdentity(folder = entry.ref)
        is SourceEntry.Photo -> EntryIdentity(asset = entry.asset)
    }

    private suspend fun missingFailure(): SourceFailure.NotFound {
        val harness = createHarness()
        val asset = harness.dataset.photos.first().entry.asset
        harness.makeMissing(asset)
        return expectFailure { harness.adapter.metadata(asset) }
    }

    private suspend fun removedFailure(): SourceFailure.SourceUnavailable {
        val harness = createHarness()
        harness.removeSource()
        return expectFailure { harness.adapter.probe() }
    }

    private suspend fun configuredAccessFailure(
        select: (AdapterHarness) -> AccessFailureScenario?,
        expected: Class<out SourceFailure>,
    ): SourceFailure? {
        val harness = createHarness()
        val scenario = select(harness) ?: return null
        scenario.arrange()
        val failure = expectFailure<SourceFailure> { scenario.exercise(harness) }
        assertThat(failure).isInstanceOf(expected)
        return failure
    }

    private suspend inline fun <reified T : Throwable> expectFailure(
        block: suspend () -> Unit,
    ): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
            return failure
        }
        error("Unreachable")
    }

    private data class EntryIdentity(
        val folder: FolderRef? = null,
        val asset: AssetRef? = null,
    )

    private companion object {
        val FORBIDDEN_DIAGNOSTIC_PATTERNS =
            listOf(
                Regex("""(?i)\bbearer\s+\S+"""),
                Regex(
                    """(?i)\b(?:password|credentials?|tokens?|authorization|secrets?|""" +
                        """api[\s_-]*key)\b\s*[:=]\s*\S+""",
                ),
                Regex(
                    """(?i)\b[a-z][a-z0-9+.-]*://""" +
                        """(?:[^/\s]*@|\S*[?&](?:token|password|authorization)=)""",
                ),
                Regex("""(?i)(?:smb://|\\\\)[^\s]+"""),
                Regex("""(?i)(?:[^\s"'()]+[/\\])?[^\s"'()]+\.(?:xml|json)\b"""),
                Regex("""(?i)\b[^\s/\\]+\.(?:jpe?g|png|gif|webp|heic)\b"""),
            )
    }
}

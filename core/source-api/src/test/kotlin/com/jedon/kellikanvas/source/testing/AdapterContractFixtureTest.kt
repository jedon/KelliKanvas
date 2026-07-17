package com.jedon.kellikanvas.source.testing

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.io.Closeable

class AdapterContractFixtureTest : AdapterContract() {
    private var sharedDataset: ContractDataset? = null

    override fun createHarness(): AdapterHarness {
        val profileId = SourceProfileId("fixture-profile")
        val root = FolderRef(profileId, ProviderObjectId("root-stable-id"))
        val nested = FolderRef(profileId, ProviderObjectId("nested-stable-id"))
        val first =
            photo(
                profileId = profileId,
                id = "first-stable-id",
                name = "private-family-photo.jpg",
                bytes = "first-photo-payload",
                width = 640,
                height = 480,
            )
        val second =
            photo(
                profileId = profileId,
                id = "second-stable-id",
                name = "tax-record-photo.png",
                bytes = "second-photo-payload",
                width = 800,
                height = 600,
            )
        val nestedPhoto =
            photo(
                profileId = profileId,
                id = "nested-photo-stable-id",
                name = "medical-photo.jpeg",
                bytes = "nested-photo-payload",
                width = 1024,
                height = 768,
            )
        val dataset =
            sharedDataset
                ?: ContractDataset(
                    root = root,
                    childrenByFolder =
                    mapOf(
                        root to
                            listOf(
                                first.entry,
                                SourceEntry.Folder(nested, "sensitive-folder"),
                                second.entry,
                            ),
                        nested to
                            listOf(
                                nestedPhoto.entry,
                                SourceEntry.Folder(root, "cycle-to-root"),
                            ),
                    ),
                    photos = listOf(first, second, nestedPhoto),
                ).also {
                    sharedDataset = it
                }
        val sensitiveValues =
            setOf(
                "credential-value-123",
                "bearer-value-456",
                "https://user:pass@private.example/photos?token=secret",
                "private-family-photo.jpg",
                """\\private-server\family\photos""",
                "/private/catalog.xml",
                """C:\private\catalog.json""",
            )
        val adapter = InMemoryAdapter(profileId, dataset, sensitiveValues)

        return AdapterHarness(
            adapter = adapter,
            root = root,
            dataset = dataset,
            ioCount = adapter::ioCount,
            makeMissing = adapter::makeMissing,
            removeSource = adapter::removeSource,
            stallNextListing = adapter::stallNextListing,
            stallNextRead = adapter::stallNextRead,
            scenarios =
            AdapterScenarioCapabilities(
                invalidCredential =
                AccessFailureScenario(
                    arrange = adapter::invalidateCredential,
                    exercise = { it.adapter.probe() },
                    adapterSpecificAssertions = { failure ->
                        assertThat(failure.operation).isEqualTo("probe")
                        assertThat(adapter.credentialWasInvalidated).isTrue()
                    },
                ),
                revokedGrant =
                AccessFailureScenario(
                    arrange = adapter::revokePermission,
                    exercise = { it.adapter.listChildren(it.root, null) },
                    adapterSpecificAssertions = { failure ->
                        assertThat(failure.operation).isEqualTo("list_children")
                        assertThat(adapter.permissionWasRevoked).isTrue()
                    },
                ),
            ),
            diagnostics = {
                listOf(
                    adapter,
                    adapter.probe(),
                    root,
                    dataset,
                    dataset.children(root),
                )
            },
            sensitiveValues = sensitiveValues,
        )
    }

    private fun photo(
        profileId: SourceProfileId,
        id: String,
        name: String,
        bytes: String,
        width: Int,
        height: Int,
    ): ContractPhoto {
        val asset =
            AssetRef(
                profileId = profileId,
                objectId = ProviderObjectId(id),
                mimeType = "image/jpeg",
                byteLength = bytes.length.toLong(),
                modifiedAtMillis = 1_000,
                eTag = "private-etag",
                versionToken = "private-version",
            )
        return ContractPhoto(
            entry = SourceEntry.Photo(asset, name, width, height),
            metadata =
            PhotoMetadata(
                asset = asset,
                width = width,
                height = height,
                captureTimeMillis = 500,
            ),
            bytes = bytes.encodeToByteArray(),
        )
    }
}

private class InMemoryAdapter(
    override val profileId: SourceProfileId,
    private val dataset: ContractDataset,
    private val privateConfiguration: Set<String>,
) : SourceAdapter() {
    override val kind = SourceKind.HTTP
    override val capabilities =
        SourceCapabilities(
            supportsPaging = true,
            supportsReliableModifiedTime = true,
            supportsETag = true,
            supportsVersionToken = true,
        )

    private var state = State.AVAILABLE
    private val missing = mutableSetOf<AssetRef>()
    private var operations = 0
    private var listingStall: TestResourceStall? = null
    private var readStall: TestResourceStall? = null
    var credentialWasInvalidated = false
        private set
    var permissionWasRevoked = false
        private set

    fun ioCount(): Int = operations

    fun makeMissing(asset: AssetRef) {
        missing += asset
    }

    fun removeSource() {
        state = State.REMOVED
    }

    fun invalidateCredential() {
        credentialWasInvalidated = true
        state = State.INVALID_CREDENTIAL
    }

    fun revokePermission() {
        permissionWasRevoked = true
        state = State.REVOKED_PERMISSION
    }

    fun stallNextListing(): ResourceStall {
        check(listingStall == null)
        return TestResourceStall().also { listingStall = it }.probe
    }

    fun stallNextRead(asset: AssetRef): ResourceStall {
        check(dataset.photo(asset) != null)
        check(readStall == null)
        return TestResourceStall().also { readStall = it }.probe
    }

    override suspend fun probe(): SourceStatus {
        operations += 1
        checkAvailable("probe")
        return SourceStatus(available = true, summary = "Connected")
    }

    override suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry> {
        operations += 1
        checkAvailable("list_children")
        listingStall?.also { listingStall = null }?.use {
            it.started.complete(Unit)
            awaitOriginalCancellation()
        }
        val children = dataset.children(folder)
        val offset = cursor?.value?.toInt() ?: 0
        val end = minOf(offset + limit, children.size)
        return Page(
            items = children.subList(offset, end),
            nextCursor = if (end < children.size) PageCursor(end.toString()) else null,
        )
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata {
        operations += 1
        checkAvailable("metadata")
        if (asset in missing) throw SourceFailure.NotFound(profileId, "metadata")
        return dataset.photo(asset)?.metadata
            ?: throw SourceFailure.NotFound(profileId, "metadata")
    }

    override suspend fun openStream(asset: AssetRef): PhotoByteStream {
        operations += 1
        checkAvailable("open")
        if (asset in missing) throw SourceFailure.NotFound(profileId, "open")
        val expected =
            dataset.photo(asset)
                ?: throw SourceFailure.NotFound(profileId, "open")
        val stall = readStall?.also { readStall = null }
        return FakePhotoByteStream(
            bytes = expected.bytes,
            maxChunkSize = 3,
            beforeRead = {
                stall?.started?.complete(Unit)
                if (stall != null) awaitOriginalCancellation()
            },
            onClose = { stall?.close() },
        )
    }

    private fun checkAvailable(operation: String) {
        when (state) {
            State.AVAILABLE -> Unit
            State.INVALID_CREDENTIAL ->
                throw SourceFailure.AuthenticationRequired(profileId, operation)
            State.REMOVED -> throw SourceFailure.SourceUnavailable(profileId, operation)
            State.REVOKED_PERMISSION ->
                throw SourceFailure.PermissionRevoked(profileId, operation)
        }
    }

    override fun toString(): String = "InMemoryAdapter(profile=<redacted>, state=$state, privateValues=${privateConfiguration.size})"

    private enum class State {
        AVAILABLE,
        INVALID_CREDENTIAL,
        REMOVED,
        REVOKED_PERMISSION,
    }
}

private suspend fun awaitOriginalCancellation(): Nothing {
    try {
        awaitCancellation()
    } catch (failure: CancellationException) {
        // Coroutine stack-trace recovery links its copy to the cancellation supplied to Job.cancel.
        throw failure.originalCancellation()
    }
}

private tailrec fun CancellationException.originalCancellation(): CancellationException {
    val wrapped = cause as? CancellationException
    return if (wrapped == null || wrapped === this) this else wrapped.originalCancellation()
}

private class TestResourceStall : Closeable {
    val started = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    val probe = ResourceStall(started = started, closed = closed)

    override fun close() {
        closed.complete(Unit)
    }
}

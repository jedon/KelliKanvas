package com.jedon.kellikanvas.source.dlna

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.testing.AccessFailureScenario
import com.jedon.kellikanvas.source.testing.AdapterContract
import com.jedon.kellikanvas.source.testing.AdapterHarness
import com.jedon.kellikanvas.source.testing.AdapterScenarioCapabilities
import com.jedon.kellikanvas.source.testing.ContractDataset
import com.jedon.kellikanvas.source.testing.ContractPhoto
import com.jedon.kellikanvas.source.testing.CredentialApplicability
import com.jedon.kellikanvas.source.testing.ResourceStall
import com.jedon.kellikanvas.source.testing.ScenarioDeclaration
import com.jedon.kellikanvas.source.testing.StreamResourceObservation
import kotlinx.coroutines.CompletableDeferred
import okio.Buffer
import java.net.SocketTimeoutException

class DlnaSourceAdapterContractTest : AdapterContract() {
    override fun createHarness(): AdapterHarness {
        val profileId = SourceProfileId("dlna-contract-profile")
        val udn = "uuid:contract-nas"
        fun stable(id: String) = ProviderObjectId("$udn\u0000$id")
        val root = FolderRef(profileId, stable("0"))
        val album = FolderRef(profileId, stable("album"))
        fun photo(id: String, name: String, bytes: ByteArray): ContractPhoto {
            val asset = AssetRef(profileId, stable(id), "image/jpeg", bytes.size.toLong())
            val entry = SourceEntry.Photo(asset, name, 3840, 2160)
            return ContractPhoto(entry, PhotoMetadata(asset, 3840, 2160), bytes)
        }
        val photos =
            listOf(
                photo("p1", "contract-one.jpg", byteArrayOf(1, 2, 3)),
                photo("p2", "contract-two.jpg", byteArrayOf(4, 5, 6)),
                photo("p3", "contract-three.jpg", byteArrayOf(7, 8, 9)),
            )
        val dataset =
            ContractDataset(
                root,
                mapOf(
                    root to listOf(SourceEntry.Folder(album, "contract-album"), photos[0].entry, photos[1].entry),
                    album to listOf(photos[2].entry, SourceEntry.Folder(root, "contract-cycle")),
                ),
                photos,
            )
        val backend = FakeBackend(udn, dataset)
        val adapter =
            DlnaSourceAdapter(
                profile = DlnaProfile(profileId, udn, "0"),
                backend = backend,
            )
        return AdapterHarness(
            adapter = adapter,
            root = root,
            dataset = dataset,
            scenarios = AdapterScenarioCapabilities(
                credentialApplicability = CredentialApplicability.NOT_USED,
                invalidCredential = ScenarioDeclaration.NotApplicable("DLNA source sends no credentials"),
                revokedGrant = ScenarioDeclaration.NotApplicable("DLNA source has no persisted grant"),
                timeout = supportedScenario(backend) { backend.failure = SocketTimeoutException() },
                protocolFailure = supportedScenario(backend) { backend.failure = DlnaProtocolException("bad SOAP") },
            ),
            ioCount = { backend.ioCount },
            makeMissing = { backend.missing += it.objectId.value.substringAfter('\u0000') },
            removeSource = { backend.available = false },
            stallNextListing = {
                newStall().also { backend.listStall = it }.resource
            },
            stallNextRead = {
                newStall().also { backend.readStall = it }.resource
            },
            streamObservation = { backend.observations[it.objectId.value.substringAfter('\u0000')] ?: StreamResourceObservation(0, 0, 0) },
            sensitiveValues = setOf("http://192.168.55.4:8200/private"),
        )
    }

    private fun supportedScenario(
        backend: FakeBackend,
        arrange: suspend () -> Unit,
    ) = ScenarioDeclaration.Supported(
        AccessFailureScenario(
            arrange = arrange,
            exercise = { it.adapter.probe() },
            adapterSpecificAssertions = { backend.failure = null },
        ),
    )

    private fun newStall(): FakeStall {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        return FakeStall(started, closed, ResourceStall(started, closed))
    }

    private data class FakeStall(
        val started: CompletableDeferred<Unit>,
        val closed: CompletableDeferred<Unit>,
        val resource: ResourceStall,
    )

    private class FakeBackend(
        override val serverUdn: String,
        private val dataset: ContractDataset,
    ) : DlnaBackend {
        var ioCount = 0
        var available = true
        var failure: Throwable? = null
        val missing = mutableSetOf<String>()
        var listStall: FakeStall? = null
        var readStall: FakeStall? = null
        val observations = mutableMapOf<String, StreamResourceObservation>()

        override suspend fun probe() {
            io()
        }

        override suspend fun browse(objectId: String, start: Int, count: Int): DlnaBrowsePage {
            io()
            listStall?.also { stall ->
                listStall = null
                stall.started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    stall.closed.complete(Unit)
                }
            }
            val folder = dataset.folders.single { it.objectId.value.substringAfter('\u0000') == objectId }
            val all = dataset.children(folder)
            return DlnaBrowsePage(all.drop(start).take(count).map(::toObject), minOf(count, all.size - start), all.size)
        }

        override suspend fun metadata(objectId: String): DlnaObject {
            io()
            if (objectId in missing) throw DlnaObjectMissingException()
            val photo = dataset.photos.single { it.entry.asset.objectId.value.substringAfter('\u0000') == objectId }
            return toObject(photo.entry)
        }

        override suspend fun open(objectId: String): PhotoByteStream {
            io()
            if (objectId in missing) throw DlnaObjectMissingException()
            val bytes = dataset.photos.single { it.entry.asset.objectId.value.substringAfter('\u0000') == objectId }.bytes
            val before = observations[objectId] ?: StreamResourceObservation(0, 0, 0)
            observations[objectId] = before.copy(openedStreams = before.openedStreams + 1)
            return object : PhotoByteStream(bytes.size.toLong()) {
                var offset = 0
                override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                    readStall?.also { stall ->
                        readStall = null
                        stall.started.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } finally {
                            stall.closed.complete(Unit)
                        }
                    }
                    if (offset == bytes.size) return -1
                    val count = minOf(byteCount.toInt(), bytes.size - offset)
                    sink.write(bytes, offset, count)
                    offset += count
                    val old = observations.getValue(objectId)
                    observations[objectId] = old.copy(bytesRead = old.bytesRead + count)
                    return count.toLong()
                }
                override fun close() {
                    val old = observations.getValue(objectId)
                    observations[objectId] = old.copy(closedStreams = old.closedStreams + 1)
                    readStall?.closed?.complete(Unit)
                }
            }
        }

        private fun io() {
            ioCount++
            if (!available) throw DlnaSourceUnavailableException()
            failure?.let { throw it }
        }

        private fun toObject(entry: SourceEntry): DlnaObject = when (entry) {
            is SourceEntry.Folder ->
                DlnaObject(
                    serverUdn,
                    entry.ref.objectId.value.substringAfter('\u0000'),
                    null,
                    entry.name,
                    true,
                    emptyList(),
                )
            is SourceEntry.Photo ->
                DlnaObject(
                    serverUdn,
                    entry.asset.objectId.value.substringAfter('\u0000'),
                    null,
                    entry.name,
                    false,
                    listOf(
                        DlnaResource(
                            "http://192.168.55.4/photo",
                            entry.asset.mimeType,
                            entry.width,
                            entry.height,
                            entry.asset.byteLength,
                        ),
                    ),
                )
        }
    }
}

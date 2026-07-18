package com.jedon.kellikanvas.source.dlna

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
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.LinkedHashMap
import java.util.UUID

data class DlnaProfile(
    val id: SourceProfileId,
    val serverUdn: String,
    val rootObjectId: String = "0",
    val descriptionLocation: URI? = null,
    val controlUrl: URI? = null,
    val contentDirectoryVersion: Int = 1,
) {
    init {
        require(serverUdn.startsWith("uuid:", ignoreCase = true)) { "DLNA server UDN must start with uuid:" }
        require(rootObjectId.isNotBlank()) { "DLNA root object ID must not be blank" }
        require(contentDirectoryVersion in 1..2) { "Only ContentDirectory v1/v2 are supported" }
    }

    /** Provider object IDs are UDN-prefixed; accepts raw ContentDirectory IDs or already-stable values. */
    fun stableObjectId(objectId: String): ProviderObjectId {
        require(objectId.isNotBlank()) { "DLNA object ID must not be blank" }
        val prefix = "$serverUdn\u0000"
        val value = if (objectId.startsWith(prefix)) objectId else "$prefix$objectId"
        return ProviderObjectId(value)
    }

    val rootFolder: FolderRef get() = FolderRef(id, stableObjectId(rootObjectId))

    override fun toString(): String = "DlnaProfile(id=$id, server=<redacted>)"
}

interface DlnaBackend {
    val serverUdn: String
    suspend fun probe()
    suspend fun browse(objectId: String, start: Int, count: Int): DlnaBrowsePage
    suspend fun metadata(objectId: String): DlnaObject
    suspend fun open(objectId: String): PhotoByteStream
}

class DlnaObjectMissingException : Exception("DLNA object missing")

class DlnaSourceUnavailableException(cause: Throwable? = null) : Exception("DLNA source unavailable", cause)

class DlnaSourceAdapter(
    private val profile: DlnaProfile,
    private val backend: DlnaBackend,
    private val selector: DlnaResourceSelector =
        DlnaResourceSelector(setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic"), 3840, 2160),
) : SourceAdapter() {
    private val cursorStates = LinkedHashMap<String, CursorRecord>(16, 0.75f, true)

    override val profileId: SourceProfileId = profile.id
    override val kind: SourceKind = SourceKind.DLNA
    override val capabilities =
        SourceCapabilities(
            supportsPaging = true,
            supportsReliableModifiedTime = false,
            supportsETag = false,
            supportsVersionToken = false,
        )

    init {
        require(backend.serverUdn.equals(profile.serverUdn, ignoreCase = true)) {
            "Backend server must match profile UDN"
        }
    }

    override suspend fun probe(): SourceStatus = normalize("probe") {
        backend.probe()
        SourceStatus(true, "DLNA source available", "UPnP ContentDirectory v${profile.contentDirectoryVersion}")
    }

    override suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry> = normalize("list") {
        val objectId = decodeStableId(folder.objectId)
        val lease = acquireCursor(cursor, objectId)
        var committed = false
        try {
            val start = lease.state.startingIndex
            val page =
                try {
                    backend.browse(objectId, start, limit)
                } catch (failure: DlnaIndexBeyondRangeException) {
                    if (!lease.isUnknownTotalContinuationFor(objectId)) throw failure
                    val terminal = Page<SourceEntry>(items = emptyList(), nextCursor = transitionCursor(lease, null))
                    committed = true
                    return@normalize terminal
                }
            val nextIndex = page.validateForRequest(start, limit)
            val pageIds = page.objects.map(DlnaObject::stableId)
            if (pageIds.any(lease.state.seenObjectIds::contains)) {
                throw DlnaProtocolException("ContentDirectory repeated an object across pages")
            }
            val seenObjectIds = lease.state.seenObjectIds + pageIds
            if (seenObjectIds.size > MAX_TRACKED_PAGING_OBJECTS) {
                throw DlnaProtocolException("ContentDirectory paging identity limit exceeded")
            }
            val entries = page.objects.mapNotNull(::toEntry)
            val hasNext =
                if (page.totalMatches == 0) {
                    page.numberReturned > 0
                } else {
                    nextIndex < page.totalMatches
                }
            val nextCursor =
                transitionCursor(
                    lease,
                    if (hasNext) {
                        CursorState(
                            startingIndex = nextIndex,
                            seenObjectIds = seenObjectIds,
                            objectId = objectId,
                            unknownTotal = page.totalMatches == 0,
                        )
                    } else {
                        null
                    },
                )
            committed = true
            Page(items = entries, nextCursor = nextCursor)
        } finally {
            if (!committed) releaseCursor(lease)
        }
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = normalize("metadata") {
        val item = backend.metadata(decodeStableId(asset.objectId))
        val resource = selector.select(item.resources) ?: throw DlnaProtocolException("No supported image resource")
        PhotoMetadata(asset, resource.width, resource.height)
    }

    override suspend fun openStream(asset: AssetRef): PhotoByteStream = normalize("open") {
        NormalizingDlnaPhotoByteStream(
            backend.open(decodeStableId(asset.objectId)),
            profileId,
        )
    }

    private fun toEntry(item: DlnaObject): SourceEntry? {
        val stableId = ProviderObjectId(item.stableId)
        if (item.isContainer) return SourceEntry.Folder(FolderRef(profileId, stableId), item.title)
        val resource = selector.select(item.resources) ?: return null
        return SourceEntry.Photo(
            asset = AssetRef(
                profileId = profileId,
                objectId = stableId,
                mimeType = requireNotNull(resource.mimeType),
                byteLength = resource.byteLength,
            ),
            name = item.title,
            width = resource.width,
            height = resource.height,
        )
    }

    private fun decodeStableId(id: ProviderObjectId): String {
        val prefix = "${profile.serverUdn}\u0000"
        if (!id.value.startsWith(prefix) || id.value.length == prefix.length) {
            throw IllegalArgumentException("Object does not belong to configured DLNA server")
        }
        return id.value.substring(prefix.length)
    }

    private fun acquireCursor(
        cursor: PageCursor?,
        objectId: String,
    ): CursorLease {
        if (cursor == null) {
            return CursorLease(
                token = null,
                state = CursorState(0, emptySet(), objectId, unknownTotal = false),
                record = null,
            )
        }
        return synchronized(cursorStates) {
            val record =
                cursorStates[cursor.value]
                    ?: throw DlnaProtocolException("Invalid or repeated DLNA page cursor")
            if (record.state.objectId != objectId) {
                throw DlnaProtocolException("DLNA page cursor belongs to a different object")
            }
            if (record.inUse) {
                throw DlnaProtocolException("DLNA page cursor is already in use")
            }
            record.inUse = true
            CursorLease(cursor.value, record.state, record)
        }
    }

    private fun transitionCursor(
        lease: CursorLease,
        nextState: CursorState?,
    ): PageCursor? = synchronized(cursorStates) {
        if (lease.token != null) {
            if (cursorStates[lease.token] !== lease.record || lease.record?.inUse != true) {
                throw DlnaProtocolException("DLNA page cursor state changed")
            }
            cursorStates.remove(lease.token)
        }
        if (nextState == null) return@synchronized null
        while (cursorStates.size >= MAX_ACTIVE_CURSORS) {
            val evictable = cursorStates.entries.firstOrNull { !it.value.inUse }
                ?: throw DlnaProtocolException("Too many concurrent DLNA page cursors")
            cursorStates.remove(evictable.key)
        }
        var token: String
        do {
            token = UUID.randomUUID().toString()
        } while (cursorStates.containsKey(token))
        cursorStates[token] = CursorRecord(nextState)
        PageCursor(token)
    }

    private fun releaseCursor(lease: CursorLease) {
        val token = lease.token ?: return
        synchronized(cursorStates) {
            if (cursorStates[token] === lease.record) {
                lease.record?.inUse = false
            }
        }
    }

    private suspend fun <T> normalize(
        operation: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: SourceFailure) {
        throw failure
    } catch (_: DlnaObjectMissingException) {
        throw SourceFailure.NotFound(profileId, operation, "DLNA object changed; refresh catalog")
    } catch (_: DlnaSourceUnavailableException) {
        throw SourceFailure.SourceUnavailable(profileId, operation, "DLNA source unavailable")
    } catch (_: SocketTimeoutException) {
        throw SourceFailure.Timeout(profileId, operation, "DLNA operation timed out")
    } catch (_: DlnaProtocolException) {
        throw SourceFailure.ProtocolFailure(profileId, operation, "DLNA protocol response invalid")
    } catch (_: DlnaSecurityException) {
        throw SourceFailure.ProtocolFailure(profileId, operation, "DLNA endpoint policy rejected request")
    } catch (failure: IllegalArgumentException) {
        throw SourceFailure.ProtocolFailure(
            profileId,
            operation,
            failure.message?.takeIf { it.isNotBlank() }?.take(120) ?: "Invalid DLNA object id",
        )
    } catch (_: IOException) {
        throw SourceFailure.SourceUnavailable(profileId, operation, "DLNA network unavailable")
    }

    companion object {
        private const val MAX_ACTIVE_CURSORS = 128
        private const val MAX_TRACKED_PAGING_OBJECTS = 100_000

        fun network(
            profile: DlnaProfile,
            httpClient: OkHttpClient,
        ): DlnaSourceAdapter {
            val descriptionLocation = requireNotNull(profile.descriptionLocation) {
                "Network DLNA profile requires description location"
            }
            val controlUrl = requireNotNull(profile.controlUrl) {
                "Network DLNA profile requires ContentDirectory control URL"
            }
            val endpointPolicy = DlnaEndpointPolicy(descriptionLocation)
            endpointPolicy.validateInitial(controlUrl)
            return DlnaSourceAdapter(
                profile,
                NetworkDlnaBackend(
                    serverUdn = profile.serverUdn,
                    contentDirectory = ContentDirectoryClient(
                        httpClient,
                        controlUrl,
                        profile.serverUdn,
                        profile.contentDirectoryVersion,
                        endpointPolicy,
                    ),
                    photoLoader = DlnaPhotoLoader(httpClient, endpointPolicy),
                ),
            )
        }
    }

    private data class CursorState(
        val startingIndex: Int,
        val seenObjectIds: Set<String>,
        val objectId: String,
        val unknownTotal: Boolean,
    )

    private class CursorRecord(
        val state: CursorState,
        var inUse: Boolean = false,
    )

    private data class CursorLease(
        val token: String?,
        val state: CursorState,
        val record: CursorRecord?,
    ) {
        fun isUnknownTotalContinuationFor(objectId: String): Boolean = token != null &&
            state.unknownTotal &&
            state.startingIndex > 0 &&
            state.seenObjectIds.isNotEmpty() &&
            state.objectId == objectId
    }
}

private class NormalizingDlnaPhotoByteStream(
    private val delegate: PhotoByteStream,
    private val profileId: SourceProfileId,
) : PhotoByteStream(delegate.contentLength) {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = try {
        delegate.read(sink, byteCount)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: SourceFailure) {
        throw failure
    } catch (_: IOException) {
        throw SourceFailure.SourceUnavailable(profileId, "read", "DLNA network unavailable")
    }

    override fun close() = delegate.close()
}

internal fun DlnaBrowsePage.validateForRequest(
    startingIndex: Int,
    requestedCount: Int,
): Int {
    if (numberReturned < 0 || totalMatches < 0) {
        throw DlnaProtocolException("Negative ContentDirectory paging count")
    }
    if (numberReturned > requestedCount || numberReturned != objects.size) {
        throw DlnaProtocolException("Inconsistent ContentDirectory returned count")
    }
    if (objects.distinctBy(DlnaObject::stableId).size != objects.size) {
        throw DlnaProtocolException("Duplicate ContentDirectory objects")
    }
    val nextIndex =
        try {
            Math.addExact(startingIndex, numberReturned)
        } catch (_: ArithmeticException) {
            throw DlnaProtocolException("ContentDirectory cursor overflow")
        }
    if (totalMatches > 0) {
        if (nextIndex > totalMatches || startingIndex > totalMatches) {
            throw DlnaProtocolException("Inconsistent ContentDirectory total count")
        }
        if (numberReturned == 0 && startingIndex < totalMatches) {
            throw DlnaProtocolException("ContentDirectory paging made no progress")
        }
    }
    return nextIndex
}

class NetworkDlnaBackend(
    override val serverUdn: String,
    private val contentDirectory: ContentDirectoryClient,
    private val photoLoader: DlnaPhotoLoader,
    private val selector: DlnaResourceSelector =
        DlnaResourceSelector(setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic"), 3840, 2160),
) : DlnaBackend {
    override suspend fun probe() {
        contentDirectory.browseDirectChildren("0", 0, 1)
    }

    override suspend fun browse(objectId: String, start: Int, count: Int): DlnaBrowsePage = contentDirectory
        .browseDirectChildren(objectId, start, count)

    override suspend fun metadata(objectId: String): DlnaObject = contentDirectory.browseMetadata(objectId)

    override suspend fun open(objectId: String): PhotoByteStream {
        val item = metadata(objectId)
        val resource = selector.select(item.resources) ?: throw DlnaProtocolException("No supported image resource")
        return photoLoader.open(URI(resource.uri))
    }
}

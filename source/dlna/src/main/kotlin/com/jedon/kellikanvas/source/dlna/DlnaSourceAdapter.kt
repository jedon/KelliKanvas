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
import java.net.SocketTimeoutException
import java.net.URI

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

class DlnaSourceUnavailableException : Exception("DLNA source unavailable")

class DlnaSourceAdapter(
    private val profile: DlnaProfile,
    private val backend: DlnaBackend,
    private val selector: DlnaResourceSelector =
        DlnaResourceSelector(setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic"), 3840, 2160),
) : SourceAdapter() {
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
        val start = cursor?.value?.toIntOrNull()
            ?: if (cursor == null) 0 else throw DlnaProtocolException("Invalid DLNA page cursor")
        require(start >= 0) { "Page cursor must be nonnegative" }
        val page = backend.browse(objectId, start, limit)
        val entries = page.objects.mapNotNull(::toEntry)
        val nextIndex = start + page.numberReturned
        Page(
            items = entries,
            nextCursor = if (nextIndex < page.totalMatches) PageCursor(nextIndex.toString()) else null,
        )
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata = normalize("metadata") {
        val item = backend.metadata(decodeStableId(asset.objectId))
        val resource = selector.select(item.resources) ?: throw DlnaProtocolException("No supported image resource")
        PhotoMetadata(asset, resource.width, resource.height)
    }

    override suspend fun openStream(asset: AssetRef): PhotoByteStream = normalize("open") {
        backend.open(decodeStableId(asset.objectId))
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
    }

    companion object {
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

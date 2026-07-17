package com.jedon.kellikanvas.model

enum class SourceKind {
    DLNA,
    SMB,
    SAF,
    HTTP,
}

data class FolderRef(
    val profileId: SourceProfileId,
    val objectId: ProviderObjectId,
) {
    override fun toString(): String = "FolderRef(<redacted>)"
}

data class AssetRef(
    val profileId: SourceProfileId,
    val objectId: ProviderObjectId,
    val mimeType: String,
    val byteLength: Long? = null,
    val modifiedAtMillis: Long? = null,
    val eTag: String? = null,
    val versionToken: String? = null,
) {
    init {
        requirePrivacySafeText(mimeType, "MIME type")
        require(byteLength == null || byteLength >= 0) { "Byte length must be nonnegative" }
        require(modifiedAtMillis == null || modifiedAtMillis >= 0) {
            "Modified time must be nonnegative"
        }
        require(eTag == null || eTag.isNotBlank()) { "ETag must not be blank" }
        require(versionToken == null || versionToken.isNotBlank()) {
            "Version token must not be blank"
        }
    }

    val key: AssetKey = AssetKey(profileId, objectId)

    override fun toString(): String = "AssetRef(mimeType=$mimeType, byteLength=$byteLength, " +
        "modifiedAtMillis=$modifiedAtMillis, eTag=<redacted>, versionToken=<redacted>)"
}

sealed interface SourceEntry {
    val name: String

    data class Folder(
        val ref: FolderRef,
        override val name: String,
    ) : SourceEntry {
        init {
            require(name.isNotBlank()) { "Folder name must not be blank" }
        }

        override fun toString(): String = "SourceEntry.Folder(ref=$ref, name=<redacted>)"
    }

    data class Photo(
        val asset: AssetRef,
        override val name: String,
        val width: Int? = null,
        val height: Int? = null,
    ) : SourceEntry {
        init {
            require(name.isNotBlank()) { "Photo name must not be blank" }
            requireValidDimension(width, "Photo width")
            requireValidDimension(height, "Photo height")
        }

        override fun toString(): String = "SourceEntry.Photo(asset=$asset, name=<redacted>, width=$width, height=$height)"
    }
}

data class Page<T>(
    val items: List<T>,
    val nextCursor: PageCursor? = null,
)

data class PhotoMetadata(
    val asset: AssetRef,
    val width: Int? = null,
    val height: Int? = null,
    val captureTimeMillis: Long? = null,
) {
    init {
        requireValidDimension(width, "Photo width")
        requireValidDimension(height, "Photo height")
    }
}

data class SourceCapabilities(
    val supportsPaging: Boolean = false,
    val supportsReliableModifiedTime: Boolean = false,
    val supportsETag: Boolean = false,
    val supportsVersionToken: Boolean = false,
)

data class SourceStatus(
    val available: Boolean,
    val summary: String,
    val negotiatedProtocol: String? = null,
    val signingEnabled: Boolean? = null,
    val encryptionEnabled: Boolean? = null,
) {
    init {
        requirePrivacySafeText(summary, "Source status summary")
        negotiatedProtocol?.let {
            requirePrivacySafeText(it, "Negotiated protocol")
        }
    }
}

private fun requireValidDimension(
    value: Int?,
    label: String,
) {
    require(value == null || value > 0) { "$label must be positive when known" }
}

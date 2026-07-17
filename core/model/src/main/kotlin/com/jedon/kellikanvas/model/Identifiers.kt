package com.jedon.kellikanvas.model

private val uriPattern = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S*""")
private val secretAssignmentPattern =
    Regex("""(?i)\b(?:token|password|credential|authorization)\s*[:=]\s*\S+""")

@JvmInline
value class SourceProfileId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Source profile ID must not be blank" }
    }

    override fun toString(): String = "SourceProfileId(<redacted>)"
}

@JvmInline
value class ProviderObjectId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Provider object ID must not be blank" }
    }

    override fun toString(): String = "ProviderObjectId(<redacted>)"
}

data class AssetKey(
    val profileId: SourceProfileId,
    val objectId: ProviderObjectId,
) {
    override fun toString(): String = "AssetKey(<redacted>)"
}

@JvmInline
value class PageCursor(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Page cursor must not be blank" }
    }

    override fun toString(): String = "PageCursor(<redacted>)"
}

internal fun requirePrivacySafeText(
    value: String,
    label: String,
): String {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(!uriPattern.containsMatchIn(value)) { "$label must not contain a URI" }
    require(!secretAssignmentPattern.containsMatchIn(value)) {
        "$label must not contain credential material"
    }
    return value
}

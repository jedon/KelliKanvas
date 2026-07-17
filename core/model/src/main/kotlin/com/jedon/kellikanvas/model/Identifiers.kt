package com.jedon.kellikanvas.model

private val uriPattern = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S*""")
private val sensitiveDiagnosticTermPattern =
    Regex(
        """(?i)(?<![a-z0-9])""" +
            """(?:bearer|password|tokens?|credentials?|authorization|secrets?|api[\s_-]*key)""" +
            """(?![a-z0-9])""",
    )

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

/**
 * Rejects common high-risk disclosure forms as a defensive guard.
 *
 * This does not prove arbitrary text is safe. Callers must still provide curated diagnostics and
 * must never pass endpoint or credential material.
 */
internal fun requirePrivacySafeText(
    value: String,
    label: String,
): String {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(!uriPattern.containsMatchIn(value)) { "$label must not contain a URI" }
    require(!sensitiveDiagnosticTermPattern.containsMatchIn(value)) {
        "$label must not contain credential material"
    }
    return value
}

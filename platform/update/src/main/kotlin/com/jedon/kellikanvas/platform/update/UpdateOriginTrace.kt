package com.jedon.kellikanvas.platform.update

import java.net.URI

/** Which control URI last served the update envelope, and when. */
data class UpdateOriginUsed(
    val uri: URI,
    val timestampMillis: Long,
)

/**
 * Holds the control URI that most recently served the update envelope so the
 * Diagnostics screen can show whether the hostname or a fallback IP was used.
 * Follows the BootstrapTrace singleton pattern from core:logging.
 */
object UpdateOriginTrace {
    @Volatile
    private var lastUsed: UpdateOriginUsed? = null

    fun record(
        uri: URI,
        timestampMillis: Long,
    ) {
        lastUsed = UpdateOriginUsed(uri, timestampMillis)
    }

    fun last(): UpdateOriginUsed? = lastUsed

    fun clear() {
        lastUsed = null
    }
}

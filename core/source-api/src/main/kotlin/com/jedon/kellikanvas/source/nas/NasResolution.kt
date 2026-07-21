package com.jedon.kellikanvas.source.nas

/** Which candidate source produced the working NAS host. */
enum class NasResolutionPath {
    HOSTNAME,
    CACHED_IP,
    STATIC_DEFAULT,
    DISCOVERY,
}

/** A NAS host that passed the reachability probe, with how and when it was found. */
data class NasResolution(
    val host: String,
    val path: NasResolutionPath,
    val timestampMillis: Long,
)

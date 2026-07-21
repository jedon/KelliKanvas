package com.jedon.kellikanvas.source.nas

/**
 * Persists the last known-good NAS LAN IP across launches.
 * Implementations live in the app layer (e.g. SharedPreferences).
 */
interface NasHostCache {
    fun get(): String?

    fun set(ip: String)
}

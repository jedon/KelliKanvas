package com.jedon.kellikanvas.nas

import android.content.Context
import com.jedon.kellikanvas.source.nas.NasHostCache

/** Persists the last known-good NAS LAN IP in app-private SharedPreferences. */
class SharedPreferencesNasHostCache(context: Context) : NasHostCache {
    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun get(): String? = preferences.getString(KEY_LAST_KNOWN_GOOD_IP, null)?.takeIf(String::isNotBlank)

    override fun set(ip: String) {
        preferences.edit().putString(KEY_LAST_KNOWN_GOOD_IP, ip).apply()
    }

    private companion object {
        const val FILE_NAME = "kellikanvas_nas_host"
        const val KEY_LAST_KNOWN_GOOD_IP = "last_known_good_ip"
    }
}

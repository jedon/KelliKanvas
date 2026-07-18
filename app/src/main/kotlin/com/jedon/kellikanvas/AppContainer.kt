package com.jedon.kellikanvas

import android.content.Context
import android.net.wifi.WifiManager
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.preferences.DataStoreAppPreferencesRepository
import com.jedon.kellikanvas.source.dlna.AndroidMulticastLock
import com.jedon.kellikanvas.source.dlna.DlnaManualResolver
import com.jedon.kellikanvas.source.dlna.DlnaProfile
import com.jedon.kellikanvas.source.dlna.DlnaProfileDiscovery
import com.jedon.kellikanvas.source.dlna.DlnaSourceAdapter
import com.jedon.kellikanvas.source.dlna.SsdpDiscoverer
import com.jedon.kellikanvas.source.saf.ContentResolverSafDocuments
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafSourceAdapter
import okhttp3.OkHttpClient

class AppContainer(appContext: Context) {
    val database = KelliKanvasDatabaseFactory.create(appContext)
    val preferences = DataStoreAppPreferencesRepository.create(appContext)

    val contentResolver = appContext.contentResolver
    val httpClient = OkHttpClient()
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    fun safAdapter(profile: SafProfile): SafSourceAdapter = SafSourceAdapter(
        profile = profile,
        documents = ContentResolverSafDocuments(contentResolver),
    )

    fun dlnaAdapter(profile: DlnaProfile): DlnaSourceAdapter = DlnaSourceAdapter.network(profile, httpClient)

    fun dlnaDiscovery(): DlnaProfileDiscovery {
        val wifi = wifiManager ?: throw IllegalStateException("Wi-Fi is required for DLNA discovery")
        return DlnaProfileDiscovery(
            discoverer = SsdpDiscoverer(multicastLock = AndroidMulticastLock(wifi)),
            httpClient = httpClient,
        )
    }

    fun dlnaManualResolver(): DlnaManualResolver = DlnaManualResolver(httpClient)
}

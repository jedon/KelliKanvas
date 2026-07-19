package com.jedon.kellikanvas

import android.content.Context
import android.net.wifi.WifiManager
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.preferences.DataStoreAppPreferencesRepository
import com.jedon.kellikanvas.feature.settings.UpdateCheckController
import com.jedon.kellikanvas.security.AndroidCredentialVault
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.dlna.AndroidMulticastLock
import com.jedon.kellikanvas.source.dlna.DlnaManualResolver
import com.jedon.kellikanvas.source.dlna.DlnaProfile
import com.jedon.kellikanvas.source.dlna.DlnaProfileDiscovery
import com.jedon.kellikanvas.source.dlna.DlnaSourceAdapter
import com.jedon.kellikanvas.source.dlna.SsdpDiscoverer
import com.jedon.kellikanvas.source.saf.ContentResolverSafDocuments
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafSourceAdapter
import com.jedon.kellikanvas.source.smb.SmbCredentials
import com.jedon.kellikanvas.source.smb.SmbProfile
import com.jedon.kellikanvas.source.smb.SmbSourceAdapter
import com.jedon.kellikanvas.update.createUpdateCheckController
import okhttp3.OkHttpClient

class AppContainer(appContext: Context) {
    val database = KelliKanvasDatabaseFactory.create(appContext)
    val preferences = DataStoreAppPreferencesRepository.create(appContext)
    val contentResolver = appContext.contentResolver
    val httpClient = OkHttpClient()
    val credentialVault: CredentialVault = AndroidCredentialVault(appContext)
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(WifiManager::class.java)
    val updateCheckController: UpdateCheckController? =
        runCatching { createUpdateCheckController(appContext, httpClient) }.getOrNull()

    fun safAdapter(profile: SafProfile): SafSourceAdapter = SafSourceAdapter(
        profile = profile,
        documents = ContentResolverSafDocuments(contentResolver),
    )

    fun dlnaAdapter(profile: DlnaProfile): DlnaSourceAdapter = DlnaSourceAdapter.network(profile, httpClient)

    fun smbAdapter(
        profile: SmbProfile,
        credentials: SmbCredentials,
    ): SmbSourceAdapter = SmbSourceAdapter.network(profile, credentials)

    fun householdSmbUsername(): String = BuildConfig.HOUSEHOLD_SMB_USERNAME

    fun householdSmbPassword(): CharArray = BuildConfig.HOUSEHOLD_SMB_PASSWORD.toCharArray()

    fun dlnaDiscovery(): DlnaProfileDiscovery {
        val wifi = wifiManager ?: throw IllegalStateException("Wi-Fi is required for DLNA discovery")
        return DlnaProfileDiscovery(
            discoverer = SsdpDiscoverer(multicastLock = AndroidMulticastLock(wifi)),
            httpClient = httpClient,
        )
    }

    fun dlnaManualResolver(): DlnaManualResolver = DlnaManualResolver(httpClient)
}

package com.jedon.kellikanvas

import android.content.Context
import android.net.wifi.WifiManager
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.preferences.DataStoreAppPreferencesRepository
import com.jedon.kellikanvas.feature.settings.UpdateCheckController
import com.jedon.kellikanvas.nas.SharedPreferencesNasHostCache
import com.jedon.kellikanvas.nas.isTcpReachable
import com.jedon.kellikanvas.security.AndroidCredentialVault
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.dlna.AndroidMulticastLock
import com.jedon.kellikanvas.source.dlna.DlnaManualResolver
import com.jedon.kellikanvas.source.dlna.DlnaProfile
import com.jedon.kellikanvas.source.dlna.DlnaProfileDiscovery
import com.jedon.kellikanvas.source.dlna.DlnaSourceAdapter
import com.jedon.kellikanvas.source.dlna.SsdpDiscoverer
import com.jedon.kellikanvas.source.nas.NasHostCache
import com.jedon.kellikanvas.source.nas.NasHostResolver
import com.jedon.kellikanvas.source.saf.ContentResolverSafDocuments
import com.jedon.kellikanvas.source.saf.SafProfile
import com.jedon.kellikanvas.source.saf.SafSourceAdapter
import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults
import com.jedon.kellikanvas.source.smb.SmbCredentials
import com.jedon.kellikanvas.source.smb.SmbProfile
import com.jedon.kellikanvas.source.smb.SmbSourceAdapter
import com.jedon.kellikanvas.update.createUpdateCheckController
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

class AppContainer(appContext: Context) {
    val database = KelliKanvasDatabaseFactory.create(appContext)
    val preferences = DataStoreAppPreferencesRepository.create(appContext)
    val contentResolver = appContext.contentResolver
    val httpClient = OkHttpClient()
    val credentialVault: CredentialVault = AndroidCredentialVault(appContext)
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(WifiManager::class.java)
    val nasHostCache: NasHostCache = SharedPreferencesNasHostCache(appContext)
    val nasHostResolver =
        NasHostResolver(
            hostname = HouseholdNasDefaults.HOSTNAME,
            staticDefaultIp = HouseholdNasDefaults.PRIMARY_HOST,
            cache = nasHostCache,
            probe = { host -> isTcpReachable(host, ports = listOf(HouseholdNasDefaults.PORT, NAS_DLNA_PORT)) },
            discover = ::discoverNasHost,
        )
    val updateCheckController: UpdateCheckController? =
        runCatching {
            createUpdateCheckController(appContext, httpClient, cachedNasIp = nasHostCache::get)
        }.getOrNull()

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

    /** SSDP last-resort discovery: the household NAS found by its DLNA friendly name. */
    private suspend fun discoverNasHost(): String? {
        if (wifiManager == null) return null
        return try {
            dlnaDiscovery().setupNamed()
                .firstOrNull { server ->
                    server.friendlyName.contains(HouseholdNasDefaults.DISPLAY_NAME, ignoreCase = true)
                }
                ?.profile
                ?.descriptionLocation
                ?.host
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }
}

private const val NAS_DLNA_PORT = 8200

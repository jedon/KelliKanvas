package com.jedon.kellikanvas.nas

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.net.toUri
import com.jedon.kellikanvas.logging.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.UnknownHostException

/**
 * Reads the Tailscale VPN on this device and resolves the household NAS through it.
 *
 * Tailscale does not expose its peer list to other apps. When the VPN is up, MagicDNS
 * on that network answers `darklingnas.<tailnet>.ts.net` with the NAS's current
 * 100.x address. Android routes that address through the tunnel, so SMB can use it
 * directly. When the VPN is down, [peerHosts] returns nothing and the UI can open
 * the Tailscale app.
 */
class TailscaleNasLookup(context: Context) {
    private val appContext = context.applicationContext

    fun isConnected(): Boolean = tailscaleNetwork() != null

    suspend fun peerHosts(hostname: String): List<String> = runInterruptible(Dispatchers.IO) {
        val network = tailscaleNetwork()
        if (network == null) {
            DiagLog.d(TAG, "Tailscale VPN is not connected")
            return@runInterruptible emptyList()
        }
        val link = connectivityManager().getLinkProperties(network)
        val domains = link?.domains
            ?.split(',', ' ')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val names = TailscaleHosts.lookupNames(hostname, domains)
        val resolved = names.flatMap { name -> resolve(network, name) }
        val hosts = TailscaleHosts.candidateHosts(resolved)
        if (hosts.isEmpty()) {
            DiagLog.w(TAG, "Tailscale is connected but $hostname did not resolve (searched $names)")
        } else {
            DiagLog.i(TAG, "Tailscale resolved $hostname to $hosts")
        }
        hosts
    }

    private fun resolve(network: Network, name: String): List<String> = try {
        network.getAllByName(name).mapNotNull { it.hostAddress }
    } catch (_: UnknownHostException) {
        emptyList()
    }

    private fun tailscaleNetwork(): Network? {
        val connectivity = connectivityManager()
        return connectivity.allNetworks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstOrNull false
            if (ownedByTailscale(capabilities)) return@firstOrNull true
            val link = connectivity.getLinkProperties(network)
            TailscaleHosts.linkLooksLikeTailscale(
                dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
                interfaceAddresses = link?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty(),
            )
        }
    }

    private fun ownedByTailscale(capabilities: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val packages = appContext.packageManager.getPackagesForUid(capabilities.ownerUid)
            packages?.any { it == TailscaleHosts.PACKAGE } == true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun connectivityManager(): ConnectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: error("ConnectivityManager unavailable")
}

/** Opens the Tailscale app, or the Play Store listing when it is not installed. */
fun openTailscale(context: Context) {
    val launch = tailscaleLaunchIntent(context.packageManager)
    if (launch != null && startActivity(context, launch)) return
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=${TailscaleHosts.PACKAGE}".toUri())
    if (startActivity(context, market)) return
    val web = Intent(
        Intent.ACTION_VIEW,
        "https://play.google.com/store/apps/details?id=${TailscaleHosts.PACKAGE}".toUri(),
    )
    if (!startActivity(context, web)) {
        DiagLog.w(TAG, "Could not open Tailscale or its Play Store listing")
    }
}

internal fun tailscaleLaunchIntent(packageManager: PackageManager): Intent? {
    packageManager.getLaunchIntentForPackage(TailscaleHosts.PACKAGE)?.let { return it }
    val leanback = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        setPackage(TailscaleHosts.PACKAGE)
    }

    @Suppress("DEPRECATION")
    val match = packageManager.queryIntentActivities(leanback, 0).firstOrNull()?.activityInfo
        ?: return null
    return Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        setClassName(match.packageName, match.name)
    }
}

private fun startActivity(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent)
}.onFailure { failure ->
    DiagLog.w(TAG, "Could not start $intent", failure)
}.isSuccess

private const val TAG = "TailscaleNas"

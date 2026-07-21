package com.jedon.kellikanvas.diagnostics

import com.jedon.kellikanvas.AppContainer
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.nas.DEFAULT_TCP_PROBE_TIMEOUT_MILLIS
import com.jedon.kellikanvas.nas.isTcpReachable
import com.jedon.kellikanvas.source.DEFAULT_PAGE_LIMIT
import com.jedon.kellikanvas.source.SourceAdapter
import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults
import com.jedon.kellikanvas.source.smb.SmbSourceAdapter

/** QNAP web/update port; SMB reachability uses [HouseholdNasDefaults.PORT]. */
private const val NAS_HTTP_PORT: Int = 8088

/**
 * The Diagnostics connectivity check list: NAS TCP reachability (445 + 8088),
 * SMB auth + share listing, DLNA SSDP probe, then a `listChildren` sample per
 * configured root. Pure assembly — all I/O is injected so the list is testable.
 */
fun buildConnectivityChecks(
    nasHost: String,
    tcpProbe: suspend (host: String, port: Int) -> Boolean,
    smbAdapter: SourceAdapter?,
    smbUnavailableReason: String,
    discoverDlnaServerNames: suspend () -> List<String>,
    roots: List<SelectedRoot>,
    adapters: Map<SourceProfileId, SourceAdapter>,
): List<ConnectivityCheck> {
    val checks = mutableListOf<ConnectivityCheck>()
    for (port in listOf(HouseholdNasDefaults.PORT, NAS_HTTP_PORT)) {
        checks += ConnectivityCheck(name = "TCP $nasHost:$port") {
            if (tcpProbe(nasHost, port)) {
                ConnectivityCheckOutcome(ok = true, detail = "connected")
            } else {
                ConnectivityCheckOutcome(
                    ok = false,
                    detail = "no connection within ${DEFAULT_TCP_PROBE_TIMEOUT_MILLIS}ms",
                )
            }
        }
    }
    checks += ConnectivityCheck(name = "SMB auth + share listing") {
        if (smbAdapter == null) {
            ConnectivityCheckOutcome(ok = false, detail = smbUnavailableReason)
        } else {
            val status = smbAdapter.probe()
            if (!status.available) {
                ConnectivityCheckOutcome(ok = false, detail = status.summary)
            } else {
                val page = smbAdapter.listChildren(
                    folder = FolderRef(
                        smbAdapter.profileId,
                        ProviderObjectId(SmbSourceAdapter.ROOT_OBJECT_ID),
                    ),
                    cursor = null,
                    limit = DEFAULT_PAGE_LIMIT,
                )
                ConnectivityCheckOutcome(
                    ok = true,
                    detail = "auth OK; share root has ${page.items.size} entries",
                )
            }
        }
    }
    checks += ConnectivityCheck(name = "DLNA SSDP discovery") {
        val names = discoverDlnaServerNames()
        if (names.isEmpty()) {
            ConnectivityCheckOutcome(ok = false, detail = "no DLNA MediaServer responded")
        } else {
            ConnectivityCheckOutcome(ok = true, detail = names.joinToString())
        }
    }
    for (root in roots) {
        checks += ConnectivityCheck(name = "Root \"${root.displayLabel}\"") {
            val adapter = adapters[root.profileId]
            if (adapter == null) {
                ConnectivityCheckOutcome(ok = false, detail = "adapter not restored")
            } else {
                val page = adapter.listChildren(
                    folder = FolderRef(root.profileId, root.objectId),
                    cursor = null,
                    limit = DEFAULT_PAGE_LIMIT,
                )
                ConnectivityCheckOutcome(ok = true, detail = "sampled ${page.items.size} entries")
            }
        }
    }
    return checks
}

/** Wires [buildConnectivityChecks] to the real container: resolver, TCP probe, adapters, SSDP. */
suspend fun appConnectivityChecks(
    container: AppContainer,
    roots: List<SelectedRoot>,
    adapters: Map<SourceProfileId, SourceAdapter>,
    restoreStatuses: List<RootRestoreStatus>,
): List<ConnectivityCheck> {
    val nasHost = container.nasHostResolver.resolve()?.host ?: HouseholdNasDefaults.PRIMARY_HOST
    val smbAdapter = adapters.values.firstOrNull { it.kind == SourceKind.SMB }
    val smbUnavailableReason =
        restoreStatuses.firstOrNull { it.kind == SourceKind.SMB && !it.restored }?.reason
            ?: "no SMB source configured"
    return buildConnectivityChecks(
        nasHost = nasHost,
        tcpProbe = { host, port -> isTcpReachable(host, ports = listOf(port)) },
        smbAdapter = smbAdapter,
        smbUnavailableReason = smbUnavailableReason,
        discoverDlnaServerNames = {
            container.dlnaDiscovery().setupNamed().map { it.friendlyName }
        },
        roots = roots,
        adapters = adapters,
    )
}

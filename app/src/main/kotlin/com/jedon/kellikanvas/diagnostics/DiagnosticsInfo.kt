package com.jedon.kellikanvas.diagnostics

import com.jedon.kellikanvas.logging.BootstrapTraceStep
import com.jedon.kellikanvas.logging.DiagLogEntry
import com.jedon.kellikanvas.logging.DiagLogLevel
import com.jedon.kellikanvas.source.nas.NasHostResolver
import com.jedon.kellikanvas.source.nas.NasResolution
import com.jedon.kellikanvas.source.nas.NasResolutionPath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
private val TIME_OF_DAY_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

/** `"1d 2h 3m 4s"`; leading zero units are dropped, seconds are always shown. */
fun formatUptime(uptimeMillis: Long): String {
    val totalSeconds = (uptimeMillis / 1_000).coerceAtLeast(0)
    val days = totalSeconds / 86_400
    val hours = totalSeconds % 86_400 / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        if (days > 0 || hours > 0 || minutes > 0) append("${minutes}m ")
        append("${seconds}s")
    }
}

fun formatTimestamp(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochMilli(epochMillis).atZone(zone).format(TIMESTAMP_FORMAT)

fun formatTimeOfDay(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochMilli(epochMillis).atZone(zone).format(TIME_OF_DAY_FORMAT)

fun nasResolutionPathLabel(path: NasResolutionPath): String = when (path) {
    NasResolutionPath.HOSTNAME -> "hostname (DNS)"
    NasResolutionPath.CACHED_IP -> "cached IP"
    NasResolutionPath.STATIC_DEFAULT -> "static default IP"
    NasResolutionPath.DISCOVERY -> "SSDP discovery"
}

fun nasResolutionStatusLabel(
    resolution: NasResolution?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (resolution == null) return "No successful resolution yet this session"
    return "${resolution.host} via ${nasResolutionPathLabel(resolution.path)}" +
        " at ${formatTimestamp(resolution.timestampMillis, zone)}"
}

/** Hostname vs fallback-IP label for the update origin host; null means no check succeeded yet. */
fun updateOriginLabel(originHost: String?): String = when {
    originHost == null -> "Not recorded yet this session"
    NasHostResolver.extractIpv4Literal(originHost) != null -> "Fallback IP ($originHost)"
    else -> "Hostname ($originHost)"
}

fun lastUpdateCheckLabel(
    lastCheckMillis: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
): String = lastCheckMillis?.let { formatTimestamp(it, zone) } ?: "Never"

fun restoreStatusValue(status: RootRestoreStatus): String = if (status.restored) {
    "Restored"
} else {
    "Skipped — ${status.reason ?: "unknown reason"}"
}

fun bootstrapStepValue(step: BootstrapTraceStep): String = buildString {
    append(if (step.ok) "OK" else "Failed")
    step.detail?.let { append(" — $it") }
}

fun diagLogLevelLabel(level: DiagLogLevel): String = when (level) {
    DiagLogLevel.DEBUG -> "D"
    DiagLogLevel.INFO -> "I"
    DiagLogLevel.WARN -> "W"
    DiagLogLevel.ERROR -> "E"
}

fun diagLogRowTitle(
    entry: DiagLogEntry,
    zone: ZoneId = ZoneId.systemDefault(),
): String = "${formatTimeOfDay(entry.timestampMillis, zone)} ${diagLogLevelLabel(entry.level)} ${entry.tag}"

fun diagLogRowValue(entry: DiagLogEntry): String = buildString {
    append(entry.message)
    entry.throwableSummary?.let { append(" ($it)") }
}

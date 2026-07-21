package com.jedon.kellikanvas.diagnostics

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.logging.BootstrapTraceStep
import com.jedon.kellikanvas.logging.DiagLogEntry
import com.jedon.kellikanvas.logging.DiagLogLevel
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.nas.NasResolution
import com.jedon.kellikanvas.source.nas.NasResolutionPath
import org.junit.Test
import java.time.ZoneOffset

class DiagnosticsInfoTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun `formatUptime drops leading zero units`() {
        assertThat(formatUptime(5_000)).isEqualTo("5s")
        assertThat(formatUptime(65_000)).isEqualTo("1m 5s")
        assertThat(formatUptime(3_600_000 + 60_000 + 5_000)).isEqualTo("1h 1m 5s")
        assertThat(formatUptime(86_400_000L + 5_000)).isEqualTo("1d 0h 0m 5s")
        assertThat(formatUptime(0)).isEqualTo("0s")
        assertThat(formatUptime(-100)).isEqualTo("0s")
    }

    @Test
    fun `formatTimestamp renders local date and time`() {
        assertThat(formatTimestamp(0, utc)).isEqualTo("1970-01-01 00:00:00")
    }

    @Test
    fun `nasResolutionPathLabel covers every path`() {
        assertThat(nasResolutionPathLabel(NasResolutionPath.HOSTNAME)).isEqualTo("hostname (DNS)")
        assertThat(nasResolutionPathLabel(NasResolutionPath.CACHED_IP)).isEqualTo("cached IP")
        assertThat(nasResolutionPathLabel(NasResolutionPath.STATIC_DEFAULT)).isEqualTo("static default IP")
        assertThat(nasResolutionPathLabel(NasResolutionPath.DISCOVERY)).isEqualTo("SSDP discovery")
    }

    @Test
    fun `nasResolutionStatusLabel handles missing and present resolutions`() {
        assertThat(nasResolutionStatusLabel(null))
            .isEqualTo("No successful resolution yet this session")
        assertThat(
            nasResolutionStatusLabel(
                NasResolution("192.168.68.90", NasResolutionPath.CACHED_IP, 0),
                utc,
            ),
        ).isEqualTo("192.168.68.90 via cached IP at 1970-01-01 00:00:00")
    }

    @Test
    fun `updateOriginLabel distinguishes hostname from fallback ip`() {
        assertThat(updateOriginLabel(null)).isEqualTo("Not recorded yet this session")
        assertThat(updateOriginLabel("darklingnas")).isEqualTo("Hostname (darklingnas)")
        assertThat(updateOriginLabel("192.168.68.81")).isEqualTo("Fallback IP (192.168.68.81)")
    }

    @Test
    fun `lastUpdateCheckLabel shows never when no check recorded`() {
        assertThat(lastUpdateCheckLabel(null)).isEqualTo("Never")
        assertThat(lastUpdateCheckLabel(0, utc)).isEqualTo("1970-01-01 00:00:00")
    }

    @Test
    fun `restoreStatusValue maps restored and skipped statuses`() {
        val profileId = SourceProfileId("smb-1")
        assertThat(
            restoreStatusValue(RootRestoreStatus(profileId, "NAS", SourceKind.SMB, restored = true)),
        ).isEqualTo("Restored")
        assertThat(
            restoreStatusValue(
                RootRestoreStatus(profileId, "NAS", SourceKind.SMB, restored = false, reason = "password missing"),
            ),
        ).isEqualTo("Skipped — password missing")
        assertThat(
            restoreStatusValue(RootRestoreStatus(profileId, "NAS", null, restored = false)),
        ).isEqualTo("Skipped — unknown reason")
    }

    @Test
    fun `bootstrapStepValue includes detail when present`() {
        assertThat(bootstrapStepValue(BootstrapTraceStep("SMB connect", ok = true)))
            .isEqualTo("OK")
        assertThat(bootstrapStepValue(BootstrapTraceStep("SMB connect", ok = false, detail = "timeout")))
            .isEqualTo("Failed — timeout")
    }

    @Test
    fun `diagLog rows combine time level tag and message`() {
        val entry = DiagLogEntry(
            timestampMillis = 61_000,
            level = DiagLogLevel.WARN,
            tag = "NasHostResolver",
            message = "probe failed",
            throwableSummary = "IOException: refused",
        )
        assertThat(diagLogRowTitle(entry, utc)).isEqualTo("00:01:01 W NasHostResolver")
        assertThat(diagLogRowValue(entry)).isEqualTo("probe failed (IOException: refused)")
        assertThat(diagLogRowValue(entry.copy(throwableSummary = null))).isEqualTo("probe failed")
    }
}

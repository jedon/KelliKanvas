package com.jedon.kellikanvas.platform.ambient

data class CapabilityReport(
    val declared: Boolean,
    val settingAvailable: Boolean,
    val deviceStatus: CapabilityStatus,
)

data class AmbientDiagnostics(
    val light: CapabilityReport,
    val presence: CapabilityReport,
    val dream: CapabilityReport,
)

package com.jedon.kellikanvas.platform.ambient

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

data class SensorDescriptor(
    val type: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val resolution: Float,
    val maximumRange: Float,
    val powerMilliamp: Float,
    val minDelayMicros: Int,
    val maxDelayMicros: Int,
    val reportingMode: Int,
    val isWakeUp: Boolean,
    val stringType: String,
) {
    val fingerprint: String
        get() =
            listOf(
                type,
                name,
                vendor,
                version,
                resolution,
                maximumRange,
                powerMilliamp,
                minDelayMicros,
                maxDelayMicros,
                reportingMode,
                isWakeUp,
                stringType,
            )
                .joinToString("|")
}

object SensorTypes {
    const val ACCELEROMETER = 1
    const val GYROSCOPE = 4
    const val LIGHT = 5
    const val PROXIMITY = 8
    const val GRAVITY = 9
    const val ROTATION_VECTOR = 11
    const val SIGNIFICANT_MOTION = 17
    const val DEVICE_MOTION = 27
    const val LOW_LATENCY_OFFBODY_DETECT = 34
}

enum class CapabilityStatus {
    UNAVAILABLE,
    AVAILABLE,
    CANDIDATE_UNVERIFIED,
}

data class AmbientCapabilities(
    val light: CapabilityStatus,
    val vendorRgb: CapabilityStatus,
    val presence: CapabilityStatus,
) {
    companion object {
        fun unavailable() = AmbientCapabilities(
            light = CapabilityStatus.UNAVAILABLE,
            vendorRgb = CapabilityStatus.UNAVAILABLE,
            presence = CapabilityStatus.UNAVAILABLE,
        )
    }
}

class Inventory(
    val sensors: List<SensorDescriptor>,
) {
    val presenceCandidates: List<SensorDescriptor> = sensors.filter(::isPresenceCandidate)

    val capabilities =
        AmbientCapabilities(
            light =
            if (sensors.any { it.type == SensorTypes.LIGHT }) {
                CapabilityStatus.AVAILABLE
            } else {
                CapabilityStatus.UNAVAILABLE
            },
            vendorRgb =
            if (sensors.any(::isVendorRgb)) {
                CapabilityStatus.CANDIDATE_UNVERIFIED
            } else {
                CapabilityStatus.UNAVAILABLE
            },
            presence =
            if (presenceCandidates.isNotEmpty()) {
                CapabilityStatus.CANDIDATE_UNVERIFIED
            } else {
                CapabilityStatus.UNAVAILABLE
            },
        )

    private fun isVendorRgb(sensor: SensorDescriptor): Boolean {
        if (sensor.type == SensorTypes.LIGHT) return false
        val identity = "${sensor.name} ${sensor.stringType}".lowercase()
        return "rgb" in identity || "ambient color" in identity || "ambient colour" in identity
    }

    private fun isPresenceCandidate(sensor: SensorDescriptor): Boolean {
        if (sensor.type in NEVER_PRESENCE_TYPES) return false
        if (sensor.type in STANDARD_PRESENCE_CANDIDATE_TYPES) return true
        val identity = "${sensor.name} ${sensor.stringType}".lowercase()
        return PRESENCE_HINTS.any(identity::contains)
    }

    private companion object {
        val NEVER_PRESENCE_TYPES =
            setOf(
                SensorTypes.ACCELEROMETER,
                SensorTypes.GYROSCOPE,
                SensorTypes.GRAVITY,
                SensorTypes.ROTATION_VECTOR,
                SensorTypes.DEVICE_MOTION,
            )
        val STANDARD_PRESENCE_CANDIDATE_TYPES =
            setOf(
                SensorTypes.PROXIMITY,
                SensorTypes.LOW_LATENCY_OFFBODY_DETECT,
                SensorTypes.SIGNIFICANT_MOTION,
            )
        val PRESENCE_HINTS =
            listOf(
                "presence",
                "occupancy",
                "occupied",
                "person",
                "human",
                "proximity",
                "off body",
                "off-body",
            )
    }
}

class AndroidSensorInventory(
    context: Context,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)

    fun inventory(): Inventory = Inventory(
        sensorManager
            ?.getSensorList(Sensor.TYPE_ALL)
            .orEmpty()
            .map { it.toDescriptor() },
    )

    private fun Sensor.toDescriptor() = SensorDescriptor(
        type = type,
        name = name,
        vendor = vendor,
        version = version,
        resolution = resolution,
        maximumRange = maximumRange,
        powerMilliamp = power,
        minDelayMicros = minDelay,
        maxDelayMicros = maxDelay,
        reportingMode = reportingMode,
        isWakeUp = isWakeUpSensor,
        stringType = stringType,
    )
}

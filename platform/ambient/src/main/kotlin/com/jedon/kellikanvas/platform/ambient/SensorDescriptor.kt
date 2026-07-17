package com.jedon.kellikanvas.platform.ambient

import android.Manifest
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
    val requiredPermission: String? = null,
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
                requiredPermission,
            )
                .joinToString("|")
}

enum class SensorEligibility {
    LIGHT,
    VENDOR_RGB_UNVERIFIED,
    PRESENCE_CANDIDATE_UNVERIFIED,
    UNSUPPORTED_DEVICE_MOTION,
    OTHER,
}

data class SensorDiagnostic(
    val type: Int,
    val stringType: String,
    val name: String,
    val vendor: String,
    val version: Int,
    val wakeUp: Boolean,
    val reportingMode: Int,
    val resolution: Float,
    val maximumRange: Float,
    val powerMilliamp: Float,
    val minDelayMicros: Int,
    val maxDelayMicros: Int,
    val requiredPermission: String?,
    val eligibility: SensorEligibility,
) {
    val metadata: String
        get() =
            "type=$type,stringType=$stringType,name=$name,vendor=$vendor,version=$version," +
                "wakeUp=$wakeUp,reportingMode=$reportingMode,resolution=$resolution," +
                "maximumRange=$maximumRange,powerMilliamp=$powerMilliamp," +
                "minDelayMicros=$minDelayMicros,maxDelayMicros=$maxDelayMicros," +
                "permission=${requiredPermission ?: "none"}"
}

object SensorTypes {
    const val ACCELEROMETER = 1
    const val GYROSCOPE = 4
    const val LIGHT = 5
    const val PROXIMITY = 8
    const val GRAVITY = 9
    const val ROTATION_VECTOR = 11
    const val SIGNIFICANT_MOTION = 17
    const val STEP_DETECTOR = 18
    const val STEP_COUNTER = 19
    const val HEART_RATE = 21
    const val DEVICE_MOTION = 27
    const val HEART_BEAT = 31
    const val LOW_LATENCY_OFFBODY_DETECT = 34
}

fun interface SensorPermissionResolver {
    fun requiredPermission(sensorType: Int, stringType: String): String?

    companion object {
        val Standard =
            SensorPermissionResolver { sensorType, _ ->
                when (sensorType) {
                    SensorTypes.SIGNIFICANT_MOTION,
                    SensorTypes.STEP_DETECTOR,
                    SensorTypes.STEP_COUNTER,
                    -> ACTIVITY_RECOGNITION_PERMISSION
                    SensorTypes.HEART_RATE,
                    SensorTypes.HEART_BEAT,
                    -> Manifest.permission.BODY_SENSORS
                    else -> null
                }
            }

        private const val ACTIVITY_RECOGNITION_PERMISSION =
            "android.permission.ACTIVITY_RECOGNITION"
    }
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
    val unsupportedPresenceSensors: List<SensorDescriptor> =
        sensors.filter { it.type in UNSUPPORTED_DEVICE_MOTION_TYPES }
    val diagnostics: List<SensorDiagnostic> = sensors.map(::toDiagnostic)

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
        if (sensor.type in NEVER_PRESENCE_TYPES || sensor.type in UNSUPPORTED_DEVICE_MOTION_TYPES) {
            return false
        }
        if (sensor.type in STANDARD_PRESENCE_CANDIDATE_TYPES) return true
        val identity = "${sensor.name} ${sensor.stringType}".lowercase()
        return PRESENCE_HINTS.any(identity::contains)
    }

    fun containsPresenceCandidate(sensor: SensorDescriptor): Boolean = presenceCandidates.any {
        it.fingerprint == sensor.fingerprint
    }

    private fun toDiagnostic(sensor: SensorDescriptor) = SensorDiagnostic(
        type = sensor.type,
        stringType = sensor.stringType,
        name = sensor.name,
        vendor = sensor.vendor,
        version = sensor.version,
        wakeUp = sensor.isWakeUp,
        reportingMode = sensor.reportingMode,
        resolution = sensor.resolution,
        maximumRange = sensor.maximumRange,
        powerMilliamp = sensor.powerMilliamp,
        minDelayMicros = sensor.minDelayMicros,
        maxDelayMicros = sensor.maxDelayMicros,
        requiredPermission = sensor.requiredPermission,
        eligibility = when {
            sensor.type == SensorTypes.LIGHT -> SensorEligibility.LIGHT
            isVendorRgb(sensor) -> SensorEligibility.VENDOR_RGB_UNVERIFIED
            isPresenceCandidate(sensor) -> SensorEligibility.PRESENCE_CANDIDATE_UNVERIFIED
            sensor.type in UNSUPPORTED_DEVICE_MOTION_TYPES ->
                SensorEligibility.UNSUPPORTED_DEVICE_MOTION
            else -> SensorEligibility.OTHER
        },
    )

    private companion object {
        val NEVER_PRESENCE_TYPES =
            setOf(
                SensorTypes.ACCELEROMETER,
                SensorTypes.GYROSCOPE,
                SensorTypes.GRAVITY,
                SensorTypes.ROTATION_VECTOR,
                SensorTypes.DEVICE_MOTION,
            )
        val UNSUPPORTED_DEVICE_MOTION_TYPES = setOf(SensorTypes.SIGNIFICANT_MOTION)
        val STANDARD_PRESENCE_CANDIDATE_TYPES =
            setOf(
                SensorTypes.PROXIMITY,
                SensorTypes.LOW_LATENCY_OFFBODY_DETECT,
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
    private val permissionResolver: SensorPermissionResolver = SensorPermissionResolver.Standard,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)

    fun inventory(): Inventory = Inventory(
        sensorManager
            ?.getSensorList(Sensor.TYPE_ALL)
            .orEmpty()
            .map { it.toDescriptor(permissionResolver) },
    )
}

internal fun Sensor.toDescriptor(permissionResolver: SensorPermissionResolver) = SensorDescriptor(
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
    requiredPermission = permissionResolver.requiredPermission(type, stringType),
)

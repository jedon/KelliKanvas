package com.jedon.kellikanvas.platform.ambient

import java.time.Clock
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.Executor

fun interface AmbientRegistration {
    fun unregister()
}

interface AmbientSensorSource {
    val inventory: Inventory

    fun register(
        sensor: SensorDescriptor,
        onValue: (Float) -> Unit,
    ): AmbientRegistration?
}

fun interface AmbientPermissionChecker {
    fun hasPermission(permission: String): Boolean
}

fun interface ElapsedTimeSource {
    fun nowNanos(): Long
}

fun interface TimezoneChangeSource {
    fun register(onChanged: () -> Unit): AmbientRegistration
}

fun interface BrightnessSink {
    fun apply(decision: BrightnessDecision)
}

interface AmbientPlaybackHost {
    fun playbackState(): PlaybackState

    fun pauseForPresence()

    fun resumeFromPresence()
}

enum class RegistrationStatus {
    NOT_REQUESTED,
    REGISTERED,
    PERMISSION_DENIED,
    SENSOR_UNAVAILABLE,
    QUALIFICATION_INVALID,
    REGISTRATION_FAILED,
}

data class AmbientRuntimeDiagnostics(
    val sensors: List<SensorDiagnostic>,
    val lightRegistration: RegistrationStatus = RegistrationStatus.NOT_REQUESTED,
    val presenceRegistration: RegistrationStatus = RegistrationStatus.NOT_REQUESTED,
)

class AmbientLifecycleController(
    private val configRepository: AmbientConfigRepository,
    private val sensorSource: AmbientSensorSource,
    private val permissionChecker: AmbientPermissionChecker,
    private val elapsedTimeSource: ElapsedTimeSource,
    private val timezoneChangeSource: TimezoneChangeSource,
    private val brightnessSink: BrightnessSink,
    private val playbackHost: AmbientPlaybackHost,
    eventExecutor: Executor,
    private val wallClock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    private val buildFingerprintProvider: () -> String,
) {
    private val dispatcher = SerialExecutor(eventExecutor)
    private val registrations = mutableListOf<AmbientRegistration>()
    private var generation = 0L
    private var started = false
    private var sensorBrightnessActive = false
    private var config = AmbientConfig()
    private var luxPolicy = LuxPolicy()
    private var schedulePolicy = SchedulePolicy(clock = wallClock, zoneIdProvider = zoneIdProvider)
    private var presenceGate: Gate? = null

    var diagnostics = AmbientRuntimeDiagnostics(sensorSource.inventory.diagnostics)
        private set

    @Synchronized
    fun start() {
        if (started) return
        started = true
        generation++
        val activeGeneration = generation
        config = configRepository.currentConfig()
        luxPolicy = LuxPolicy(calibration = config.luxCalibration)
        schedulePolicy =
            SchedulePolicy(
                schedule = config.dayNightSchedule,
                clock = wallClock,
                zoneIdProvider = zoneIdProvider,
            )
        sensorBrightnessActive = false
        diagnostics = AmbientRuntimeDiagnostics(sensorSource.inventory.diagnostics)

        registerBrightness(activeGeneration)
        registerPresence(activeGeneration)
        registerTimezoneChanges(activeGeneration)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        generation++
        registrations.toList().forEach(AmbientRegistration::unregister)
        registrations.clear()
        presenceGate = null
        sensorBrightnessActive = false
        brightnessSink.apply(BrightnessDecision.FollowTv)
    }

    private fun registerBrightness(activeGeneration: Long) {
        when (config.brightnessMode) {
            BrightnessMode.FOLLOW_TV -> brightnessSink.apply(BrightnessDecision.FollowTv)
            BrightnessMode.SCHEDULE -> applyScheduleFallback()
            BrightnessMode.SENSOR -> {
                applyScheduleFallback()
                val sensor =
                    sensorSource.inventory.sensors.firstOrNull { it.type == SensorTypes.LIGHT }
                if (sensor == null) {
                    updateLightStatus(RegistrationStatus.SENSOR_UNAVAILABLE)
                    return
                }
                if (!hasRequiredPermission(sensor)) {
                    updateLightStatus(RegistrationStatus.PERMISSION_DENIED)
                    return
                }
                val registration =
                    sensorSource.register(sensor) { lux ->
                        val elapsedNanos = elapsedTimeSource.nowNanos()
                        dispatcher.execute {
                            if (!isActive(activeGeneration)) return@execute
                            val brightness =
                                luxPolicy.onLux(
                                    lux = lux,
                                    elapsedRealtimeNanos = elapsedNanos,
                                    mode = config.brightnessMode,
                                )
                            if (brightness != null) {
                                sensorBrightnessActive = true
                                brightnessSink.apply(BrightnessDecision.Sensor(brightness))
                            }
                        }
                    }
                if (registration == null) {
                    updateLightStatus(RegistrationStatus.REGISTRATION_FAILED)
                } else {
                    registrations += registration
                    updateLightStatus(RegistrationStatus.REGISTERED)
                }
            }
        }
    }

    private fun registerPresence(activeGeneration: Long) {
        if (!config.presenceEnabled) return
        val qualification = config.presenceQualification
        val sensor =
            qualification?.let { qualified ->
                sensorSource.inventory.presenceCandidates.firstOrNull {
                    it.fingerprint == qualified.sensorFingerprint
                }
            }
        if (qualification == null || sensor == null) {
            updatePresenceStatus(RegistrationStatus.QUALIFICATION_INVALID)
            return
        }
        if (!hasRequiredPermission(sensor)) {
            updatePresenceStatus(RegistrationStatus.PERMISSION_DENIED)
            return
        }
        val gate =
            Gate.create(
                qualification = qualification,
                sensor = sensor,
                currentBuildFingerprint = buildFingerprintProvider(),
                vacancyTimeout = config.vacancyTimeout,
                resumeOnReturn = config.resumeOnPresenceReturn,
            )
        if (gate == null) {
            updatePresenceStatus(RegistrationStatus.QUALIFICATION_INVALID)
            return
        }
        presenceGate = gate
        val registration =
            sensorSource.register(sensor) { value ->
                val elapsedNanos = elapsedTimeSource.nowNanos()
                dispatcher.execute {
                    if (!isActive(activeGeneration)) return@execute
                    when (
                        gate.onSensorValue(
                            value = value,
                            playbackState = playbackHost.playbackState(),
                            elapsedRealtimeNanos = elapsedNanos,
                        )
                    ) {
                        AmbientAction.PAUSE -> playbackHost.pauseForPresence()
                        AmbientAction.RESUME -> playbackHost.resumeFromPresence()
                        AmbientAction.NONE -> Unit
                    }
                }
            }
        if (registration == null) {
            presenceGate = null
            updatePresenceStatus(RegistrationStatus.REGISTRATION_FAILED)
        } else {
            registrations += registration
            updatePresenceStatus(RegistrationStatus.REGISTERED)
        }
    }

    private fun registerTimezoneChanges(activeGeneration: Long) {
        if (!config.scheduleEnabled || config.brightnessMode == BrightnessMode.FOLLOW_TV) return
        registrations +=
            timezoneChangeSource.register {
                dispatcher.execute {
                    if (isActive(activeGeneration) && !sensorBrightnessActive) {
                        applyScheduleFallback()
                    }
                }
            }
    }

    private fun applyScheduleFallback() {
        val decision =
            if (config.scheduleEnabled) {
                BrightnessDecision.Schedule(schedulePolicy.brightness())
            } else {
                BrightnessDecision.FollowTv
            }
        brightnessSink.apply(decision)
    }

    private fun hasRequiredPermission(sensor: SensorDescriptor): Boolean = sensor.requiredPermission
        ?.let(permissionChecker::hasPermission) ?: true

    @Synchronized
    private fun isActive(activeGeneration: Long): Boolean = started && generation == activeGeneration

    private fun updateLightStatus(status: RegistrationStatus) {
        diagnostics = diagnostics.copy(lightRegistration = status)
    }

    private fun updatePresenceStatus(status: RegistrationStatus) {
        diagnostics = diagnostics.copy(presenceRegistration = status)
    }
}

private class SerialExecutor(
    private val backend: Executor,
) : Executor {
    private val tasks = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    override fun execute(command: Runnable) {
        synchronized(this) {
            tasks +=
                Runnable {
                    try {
                        command.run()
                    } finally {
                        scheduleNext()
                    }
                }
            if (active == null) scheduleNext()
        }
    }

    private fun scheduleNext() {
        synchronized(this) {
            active = if (tasks.isEmpty()) null else tasks.removeFirst()
            active?.let(backend::execute)
        }
    }
}

package com.jedon.kellikanvas.platform.ambient

import java.time.Clock
import java.time.Duration
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

fun interface AmbientScheduler {
    fun schedule(
        delay: Duration,
        callback: () -> Unit,
    ): AmbientRegistration
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
    private val scheduler: AmbientScheduler,
    private val wallClock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    private val buildFingerprintProvider: () -> String,
) {
    private val dispatcher = SerialExecutor(eventExecutor)
    private val runtimeRegistrations = mutableListOf<AmbientRegistration>()
    private var configRegistration: AmbientRegistration? = null
    private var presenceTimeout: AmbientRegistration? = null
    private var presenceTimeoutToken = 0L
    private var scheduleTimeout: AmbientRegistration? = null
    private var scheduleTimeoutToken = 0L
    private var generation = 0L
    private var runtimeGeneration = 0L
    private var started = false
    private var sensorBrightnessActive = false
    private var config = AmbientConfig()
    private var luxPolicy = LuxPolicy()
    private var schedulePolicy = SchedulePolicy(clock = wallClock, zoneIdProvider = zoneIdProvider)

    var diagnostics = AmbientRuntimeDiagnostics(sensorSource.inventory.diagnostics)
        private set

    @Synchronized
    fun start() {
        if (started) return
        started = true
        generation++
        val activeGeneration = generation
        configure(activeGeneration)
        configRegistration =
            configRepository.registerListener {
                dispatcher.execute {
                    if (!isActive(activeGeneration)) return@execute
                    cancelRuntime()
                    configure(activeGeneration)
                }
            }
    }

    @Synchronized
    private fun configure(activeGeneration: Long) {
        val activeRuntimeGeneration = ++runtimeGeneration
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

        registerBrightness(activeGeneration, activeRuntimeGeneration)
        registerPresence(activeGeneration, activeRuntimeGeneration)
        registerTimezoneChanges(activeGeneration, activeRuntimeGeneration)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        generation++
        configRegistration?.unregister()
        configRegistration = null
        cancelRuntime()
        brightnessSink.apply(BrightnessDecision.FollowTv)
    }

    @Synchronized
    private fun cancelRuntime() {
        runtimeGeneration++
        runtimeRegistrations.toList().forEach(AmbientRegistration::unregister)
        runtimeRegistrations.clear()
        cancelPresenceTimeout()
        cancelScheduleTimeout()
        sensorBrightnessActive = false
    }

    private fun registerBrightness(
        activeGeneration: Long,
        activeRuntimeGeneration: Long,
    ) {
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
                            if (!isRuntimeActive(activeGeneration, activeRuntimeGeneration)) {
                                return@execute
                            }
                            val brightness =
                                luxPolicy.onLux(
                                    lux = lux,
                                    elapsedRealtimeNanos = elapsedNanos,
                                    mode = config.brightnessMode,
                                )
                            if (brightness != null) {
                                sensorBrightnessActive = true
                                cancelScheduleTimeout()
                                brightnessSink.apply(BrightnessDecision.Sensor(brightness))
                            }
                        }
                    }
                if (registration == null) {
                    updateLightStatus(RegistrationStatus.REGISTRATION_FAILED)
                } else {
                    runtimeRegistrations += registration
                    updateLightStatus(RegistrationStatus.REGISTERED)
                }
            }
        }
    }

    private fun registerPresence(
        activeGeneration: Long,
        activeRuntimeGeneration: Long,
    ) {
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
        val registration =
            sensorSource.register(sensor) { value ->
                val elapsedNanos = elapsedTimeSource.nowNanos()
                dispatcher.execute {
                    if (!isRuntimeActive(activeGeneration, activeRuntimeGeneration)) {
                        return@execute
                    }
                    val action =
                        gate.onSensorValue(
                            value = value,
                            playbackState = playbackHost.playbackState(),
                            elapsedRealtimeNanos = elapsedNanos,
                        )
                    handlePresenceAction(action)
                    schedulePresenceTimeout(
                        gate,
                        activeGeneration,
                        activeRuntimeGeneration,
                        elapsedNanos,
                    )
                }
            }
        if (registration == null) {
            updatePresenceStatus(RegistrationStatus.REGISTRATION_FAILED)
        } else {
            runtimeRegistrations += registration
            updatePresenceStatus(RegistrationStatus.REGISTERED)
        }
    }

    private fun schedulePresenceTimeout(
        gate: Gate,
        activeGeneration: Long,
        activeRuntimeGeneration: Long,
        elapsedNanos: Long,
    ) {
        cancelPresenceTimeout()
        val deadline = gate.vacancyDeadlineNanos ?: return
        val delayNanos = (deadline - elapsedNanos).coerceAtLeast(0L)
        val timerToken = presenceTimeoutToken
        presenceTimeout =
            scheduler.schedule(java.time.Duration.ofNanos(delayNanos)) {
                val firedAtNanos = elapsedTimeSource.nowNanos()
                dispatcher.execute {
                    if (
                        !isRuntimeActive(activeGeneration, activeRuntimeGeneration) ||
                        timerToken != presenceTimeoutToken
                    ) {
                        return@execute
                    }
                    presenceTimeout = null
                    handlePresenceAction(
                        gate.onVacancyTimeout(
                            playbackState = playbackHost.playbackState(),
                            elapsedRealtimeNanos = firedAtNanos,
                        ),
                    )
                    schedulePresenceTimeout(
                        gate,
                        activeGeneration,
                        activeRuntimeGeneration,
                        firedAtNanos,
                    )
                }
            }
    }

    private fun cancelPresenceTimeout() {
        presenceTimeoutToken++
        presenceTimeout?.unregister()
        presenceTimeout = null
    }

    private fun handlePresenceAction(action: AmbientAction) {
        when (action) {
            AmbientAction.PAUSE -> playbackHost.pauseForPresence()
            AmbientAction.RESUME -> playbackHost.resumeFromPresence()
            AmbientAction.NONE -> Unit
        }
    }

    private fun cancelScheduleTimeout() {
        scheduleTimeoutToken++
        scheduleTimeout?.unregister()
        scheduleTimeout = null
    }

    private fun registerTimezoneChanges(
        activeGeneration: Long,
        activeRuntimeGeneration: Long,
    ) {
        if (!config.scheduleEnabled || config.brightnessMode == BrightnessMode.FOLLOW_TV) return
        runtimeRegistrations +=
            timezoneChangeSource.register {
                dispatcher.execute {
                    if (!isRuntimeActive(activeGeneration, activeRuntimeGeneration)) return@execute
                    cancelScheduleTimeout()
                    if (!sensorBrightnessActive) applyScheduleFallback()
                    scheduleNextBoundary(activeGeneration, activeRuntimeGeneration)
                }
            }
        scheduleNextBoundary(activeGeneration, activeRuntimeGeneration)
    }

    private fun scheduleNextBoundary(
        activeGeneration: Long,
        activeRuntimeGeneration: Long,
    ) {
        cancelScheduleTimeout()
        if (
            !config.scheduleEnabled ||
            config.brightnessMode == BrightnessMode.FOLLOW_TV ||
            sensorBrightnessActive
        ) {
            return
        }
        val delay = Duration.between(wallClock.instant(), schedulePolicy.nextBoundaryInstant())
        val safeDelay = if (delay.isNegative) Duration.ZERO else delay
        val timerToken = scheduleTimeoutToken
        scheduleTimeout =
            scheduler.schedule(safeDelay) {
                dispatcher.execute {
                    if (
                        !isRuntimeActive(activeGeneration, activeRuntimeGeneration) ||
                        timerToken != scheduleTimeoutToken
                    ) {
                        return@execute
                    }
                    scheduleTimeout = null
                    if (!sensorBrightnessActive) applyScheduleFallback()
                    scheduleNextBoundary(activeGeneration, activeRuntimeGeneration)
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

    @Synchronized
    private fun isRuntimeActive(
        activeGeneration: Long,
        activeRuntimeGeneration: Long,
    ): Boolean = isActive(activeGeneration) && runtimeGeneration == activeRuntimeGeneration

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

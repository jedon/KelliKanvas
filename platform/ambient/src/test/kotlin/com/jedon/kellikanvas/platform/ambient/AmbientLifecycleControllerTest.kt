package com.jedon.kellikanvas.platform.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.Executor

class AmbientLifecycleControllerTest {
    private val light = descriptor(SensorTypes.LIGHT, "Light")
    private val presence = descriptor(SensorTypes.PROXIMITY, "Presence")
    private val inventory = Inventory(listOf(light, presence))

    @Test
    fun `start registers configured sensors and stop unregisters every listener`() {
        val source = FakeSensorSource(inventory)
        val timezone = FakeTimezoneChangeSource()
        val sink = RecordingBrightnessSink()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                source = source,
                timezone = timezone,
                config = sensorAndPresenceConfig(scheduleEnabled = true),
                sink = sink,
            )

        controller.start()
        controller.start()

        assertThat(source.activeSensors).containsExactly(light, presence)
        assertThat(timezone.activeCount).isEqualTo(1)

        controller.stop()
        controller.stop()

        assertThat(source.activeSensors).isEmpty()
        assertThat(source.unregisterCount).isEqualTo(2)
        assertThat(timezone.activeCount).isEqualTo(0)
        assertThat(sink.decisions.last()).isEqualTo(BrightnessDecision.FollowTv)
    }

    @Test
    fun `missing required permission prevents listener registration and is diagnosed`() {
        val protectedLight = light.copy(requiredPermission = "permission.LIGHT")
        val source = FakeSensorSource(Inventory(listOf(protectedLight)))
        val controller =
            controller(
                source = source,
                config = AmbientConfig(brightnessMode = BrightnessMode.SENSOR),
                permissions = AmbientPermissionChecker { false },
            )

        controller.start()

        assertThat(source.activeSensors).isEmpty()
        assertThat(controller.diagnostics.lightRegistration)
            .isEqualTo(RegistrationStatus.PERMISSION_DENIED)
    }

    @Test
    fun `sensor callbacks are serialized and ignored after stop`() {
        val executor = QueueExecutor()
        val sink = RecordingBrightnessSink()
        val elapsed = FakeElapsedTimeSource(0L)
        val source = FakeSensorSource(Inventory(listOf(light)))
        val controller =
            controller(
                source = source,
                config = AmbientConfig(brightnessMode = BrightnessMode.SENSOR),
                elapsed = elapsed,
                sink = sink,
                executor = executor,
            )
        controller.start()
        sink.decisions.clear()

        elapsed.now = 1_000_000_000L
        source.emit(light, 5f)
        elapsed.now = 3_000_000_000L
        source.emit(light, 500f)
        assertThat(sink.decisions).isEmpty()

        executor.runAll()
        assertThat(sink.decisions.filterIsInstance<BrightnessDecision.Sensor>()).hasSize(2)

        source.emit(light, 100f)
        controller.stop()
        executor.runAll()
        assertThat(sink.decisions.filterIsInstance<BrightnessDecision.Sensor>()).hasSize(2)
    }

    @Test
    fun `presence callback pauses once while playback propagation lags`() {
        val source = FakeSensorSource(inventory)
        val playback = RecordingPlaybackHost()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                source = source,
                config = sensorAndPresenceConfig(timeout = Duration.ZERO),
                elapsed = elapsed,
                playback = playback,
                scheduler = scheduler,
            )
        controller.start()

        source.emit(presence, 0f)
        scheduler.advanceBy(Duration.ZERO)
        source.emit(presence, 0f)

        assertThat(playback.pauseCount).isEqualTo(1)
        assertThat(playback.resumeCount).isEqualTo(0)
    }

    @Test
    fun `vacancy timeout pauses without another sensor event`() {
        val source = FakeSensorSource(inventory)
        val playback = RecordingPlaybackHost()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                source = source,
                config = sensorAndPresenceConfig(timeout = Duration.ofSeconds(30)),
                elapsed = elapsed,
                playback = playback,
                scheduler = scheduler,
            )
        controller.start()

        source.emit(presence, 0f)
        scheduler.advanceBy(Duration.ofSeconds(29))
        assertThat(playback.pauseCount).isEqualTo(0)

        scheduler.advanceBy(Duration.ofSeconds(1))
        assertThat(playback.pauseCount).isEqualTo(1)
        assertThat(scheduler.pendingCount).isEqualTo(0)
    }

    @Test
    fun `dispatcher backlog does not extend vacancy deadline`() {
        val executor = QueueExecutor()
        val source = FakeSensorSource(inventory)
        val playback = RecordingPlaybackHost()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                source = source,
                config = sensorAndPresenceConfig(timeout = Duration.ofSeconds(30)),
                elapsed = elapsed,
                playback = playback,
                scheduler = scheduler,
                executor = executor,
            )
        controller.start()

        source.emit(presence, 0f)
        elapsed.now = Duration.ofSeconds(20).toNanos()
        executor.runAll()
        scheduler.advanceBy(Duration.ofSeconds(9))
        assertThat(playback.pauseCount).isEqualTo(0)

        scheduler.advanceBy(Duration.ofSeconds(1))
        executor.runAll()
        assertThat(playback.pauseCount).isEqualTo(1)
    }

    @Test
    fun `occupied event cancels pending vacancy timeout`() {
        val source = FakeSensorSource(inventory)
        val playback = RecordingPlaybackHost()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                source = source,
                config = sensorAndPresenceConfig(timeout = Duration.ofSeconds(30)),
                elapsed = elapsed,
                playback = playback,
                scheduler = scheduler,
            )
        controller.start()

        source.emit(presence, 0f)
        assertThat(scheduler.pendingCount).isEqualTo(1)
        source.emit(presence, 10f)

        assertThat(scheduler.pendingCount).isEqualTo(0)
        scheduler.advanceBy(Duration.ofMinutes(1))
        assertThat(playback.pauseCount).isEqualTo(0)
    }

    @Test
    fun `stop cancels timeout and rejects already queued callback`() {
        val executor = QueueExecutor()
        val source = FakeSensorSource(inventory)
        val playback = RecordingPlaybackHost()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                source = source,
                config = sensorAndPresenceConfig(timeout = Duration.ofSeconds(30)),
                elapsed = elapsed,
                playback = playback,
                scheduler = scheduler,
                executor = executor,
            )
        controller.start()
        source.emit(presence, 0f)
        executor.runAll()

        scheduler.advanceBy(Duration.ofSeconds(30))
        controller.stop()
        executor.runAll()

        assertThat(playback.pauseCount).isEqualTo(0)
        assertThat(scheduler.pendingCount).isEqualTo(0)
    }

    @Test
    fun `configuration change cancels vacancy timeout`() {
        val source = FakeSensorSource(inventory)
        val playback = RecordingPlaybackHost()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val repository = FakeConfigRepository(sensorAndPresenceConfig(timeout = Duration.ofSeconds(30)))
        val controller =
            controller(
                source = source,
                configRepository = repository,
                elapsed = elapsed,
                playback = playback,
                scheduler = scheduler,
            )
        controller.start()
        source.emit(presence, 0f)
        assertThat(scheduler.pendingCount).isEqualTo(1)

        repository.update(AmbientConfig())

        assertThat(scheduler.pendingCount).isEqualTo(0)
        scheduler.advanceBy(Duration.ofMinutes(1))
        assertThat(playback.pauseCount).isEqualTo(0)
    }

    @Test
    fun `configuration generation rejects old sensor event queued after change`() {
        val executor = QueueExecutor()
        val source = FakeSensorSource(inventory)
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val repository = FakeConfigRepository(sensorAndPresenceConfig(timeout = Duration.ofSeconds(30)))
        val controller =
            controller(
                source = source,
                configRepository = repository,
                elapsed = elapsed,
                scheduler = scheduler,
                executor = executor,
            )
        controller.start()

        repository.update(AmbientConfig())
        source.emit(presence, 0f)
        executor.runAll()

        assertThat(scheduler.pendingCount).isEqualTo(0)
    }

    @Test
    fun `subscription cannot lose update between listener registration and snapshot`() {
        val initial = AmbientConfig()
        val updated =
            AmbientConfig(
                brightnessMode = BrightnessMode.SCHEDULE,
                scheduleEnabled = true,
            )
        val repository = SnapshotRaceConfigRepository(initial, updated)
        val sink = RecordingBrightnessSink()
        val controller =
            controller(
                configRepository = repository,
                sink = sink,
            )

        controller.start()

        assertThat(sink.decisions.last()).isEqualTo(BrightnessDecision.Schedule(0.70f))
    }

    @Test
    fun `occupied event queued before timer wins serialized race`() {
        val executor = QueueExecutor()
        val source = FakeSensorSource(inventory)
        val playback = RecordingPlaybackHost()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                source = source,
                config = sensorAndPresenceConfig(timeout = Duration.ofSeconds(30)),
                elapsed = elapsed,
                playback = playback,
                scheduler = scheduler,
                executor = executor,
            )
        controller.start()
        source.emit(presence, 0f)
        executor.runAll()

        source.emit(presence, 10f)
        scheduler.advanceBy(Duration.ofSeconds(30))
        executor.runAll()

        assertThat(playback.pauseCount).isEqualTo(0)
    }

    @Test
    fun `timezone changes recompute enabled schedule on existing controller`() {
        var zone = ZoneId.of("America/Los_Angeles")
        val timezone = FakeTimezoneChangeSource()
        val sink = RecordingBrightnessSink()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                config = AmbientConfig(
                    brightnessMode = BrightnessMode.SCHEDULE,
                    scheduleEnabled = true,
                ),
                timezone = timezone,
                sink = sink,
                elapsed = elapsed,
                scheduler = scheduler,
                wallClock = Clock.fixed(Instant.parse("2026-03-08T11:30:00Z"), ZoneId.of("UTC")),
                zoneIdProvider = { zone },
            )
        controller.start()
        assertThat(sink.decisions.last()).isEqualTo(BrightnessDecision.Schedule(0.15f))
        assertThat(scheduler.pendingCount).isEqualTo(1)

        zone = ZoneId.of("America/New_York")
        timezone.fire()

        assertThat(sink.decisions.last()).isEqualTo(BrightnessDecision.Schedule(0.70f))
        assertThat(scheduler.pendingCount).isEqualTo(1)
    }

    @Test
    fun `schedule timer reevaluates brightness at next boundary`() {
        val wallClock = MutableClock(Instant.parse("2026-07-17T06:59:00Z"))
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val sink = RecordingBrightnessSink()
        val controller =
            controller(
                config = AmbientConfig(
                    brightnessMode = BrightnessMode.SCHEDULE,
                    scheduleEnabled = true,
                ),
                elapsed = elapsed,
                scheduler = scheduler,
                sink = sink,
                wallClock = wallClock,
            )
        controller.start()
        assertThat(sink.decisions.last()).isEqualTo(BrightnessDecision.Schedule(0.15f))
        assertThat(scheduler.pendingCount).isEqualTo(1)

        wallClock.now = Instant.parse("2026-07-17T07:00:00Z")
        scheduler.advanceBy(Duration.ofMinutes(1))

        assertThat(sink.decisions.last()).isEqualTo(BrightnessDecision.Schedule(0.70f))
        assertThat(scheduler.pendingCount).isEqualTo(1)
    }

    @Test
    fun `disabled schedule applies follow TV and registers no timezone observer`() {
        val timezone = FakeTimezoneChangeSource()
        val sink = RecordingBrightnessSink()
        val elapsed = FakeElapsedTimeSource(0L)
        val scheduler = FakeAmbientScheduler(elapsed)
        val controller =
            controller(
                config = AmbientConfig(
                    brightnessMode = BrightnessMode.SCHEDULE,
                    scheduleEnabled = false,
                ),
                timezone = timezone,
                sink = sink,
                elapsed = elapsed,
                scheduler = scheduler,
            )

        controller.start()

        assertThat(sink.decisions).containsExactly(BrightnessDecision.FollowTv)
        assertThat(timezone.activeCount).isEqualTo(0)
        assertThat(scheduler.pendingCount).isEqualTo(0)
        assertThat(controller.diagnostics.lightRegistration)
            .isEqualTo(RegistrationStatus.NOT_REQUESTED)
    }

    private fun controller(
        source: FakeSensorSource = FakeSensorSource(Inventory(emptyList())),
        config: AmbientConfig = AmbientConfig(),
        configRepository: AmbientConfigRepository = AmbientConfigRepository { config },
        permissions: AmbientPermissionChecker = AmbientPermissionChecker { true },
        elapsed: FakeElapsedTimeSource = FakeElapsedTimeSource(0L),
        scheduler: AmbientScheduler = FakeAmbientScheduler(elapsed),
        timezone: FakeTimezoneChangeSource = FakeTimezoneChangeSource(),
        sink: RecordingBrightnessSink = RecordingBrightnessSink(),
        playback: RecordingPlaybackHost = RecordingPlaybackHost(),
        executor: Executor = Executor(Runnable::run),
        wallClock: Clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneId.of("UTC")),
        zoneIdProvider: () -> ZoneId = { ZoneId.of("UTC") },
    ) = AmbientLifecycleController(
        configRepository = configRepository,
        sensorSource = source,
        permissionChecker = permissions,
        elapsedTimeSource = elapsed,
        timezoneChangeSource = timezone,
        brightnessSink = sink,
        playbackHost = playback,
        eventExecutor = executor,
        scheduler = scheduler,
        wallClock = wallClock,
        zoneIdProvider = zoneIdProvider,
        buildFingerprintProvider = { "firmware" },
    )

    private fun sensorAndPresenceConfig(
        timeout: Duration = Duration.ofMinutes(1),
        scheduleEnabled: Boolean = false,
    ) = AmbientConfig(
        brightnessMode = BrightnessMode.SENSOR,
        scheduleEnabled = scheduleEnabled,
        presenceEnabled = true,
        presenceQualification = Qualification.qualify(
            inventory,
            presence,
            "firmware",
            PresenceProfile(listOf(0f, 1f)),
            PresenceProfile(listOf(8f, 9f)),
        ),
        vacancyTimeout = timeout,
        resumeOnPresenceReturn = true,
    )

    private fun descriptor(
        type: Int,
        name: String,
    ) = SensorDescriptor(
        type = type,
        name = name,
        vendor = "test",
        version = 1,
        resolution = 1f,
        maximumRange = 10f,
        powerMilliamp = 0f,
        minDelayMicros = 0,
        maxDelayMicros = 0,
        reportingMode = 0,
        isWakeUp = false,
        stringType = "test.$type",
    )

    private class FakeSensorSource(
        override val inventory: Inventory,
    ) : AmbientSensorSource {
        private val listeners = linkedMapOf<SensorDescriptor, (Float) -> Unit>()
        var unregisterCount = 0
        val activeSensors: Set<SensorDescriptor> get() = listeners.keys

        override fun register(
            sensor: SensorDescriptor,
            onValue: (Float) -> Unit,
        ): AmbientRegistration {
            listeners[sensor] = onValue
            return AmbientRegistration {
                if (listeners.remove(sensor) != null) unregisterCount++
            }
        }

        fun emit(
            sensor: SensorDescriptor,
            value: Float,
        ) {
            listeners.getValue(sensor)(value)
        }
    }

    private class FakeTimezoneChangeSource : TimezoneChangeSource {
        private val listeners = mutableSetOf<() -> Unit>()
        val activeCount get() = listeners.size

        override fun register(onChanged: () -> Unit): AmbientRegistration {
            listeners += onChanged
            return AmbientRegistration { listeners -= onChanged }
        }

        fun fire() = listeners.toList().forEach { it() }
    }

    private class FakeElapsedTimeSource(
        var now: Long,
    ) : ElapsedTimeSource {
        override fun nowNanos() = now
    }

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private class FakeAmbientScheduler(
        private val elapsed: FakeElapsedTimeSource,
    ) : AmbientScheduler {
        private data class Task(
            val dueNanos: Long,
            val callback: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()
        val pendingCount get() = tasks.count { !it.cancelled }

        override fun schedule(
            delay: Duration,
            callback: () -> Unit,
        ): AmbientRegistration {
            val task = Task(elapsed.now + delay.toNanos(), callback)
            tasks += task
            return AmbientRegistration { task.cancelled = true }
        }

        fun advanceBy(duration: Duration) {
            elapsed.now += duration.toNanos()
            val ready =
                tasks
                    .filter { !it.cancelled && it.dueNanos <= elapsed.now }
                    .sortedBy(Task::dueNanos)
            ready.forEach {
                it.cancelled = true
                it.callback()
            }
        }
    }

    private class FakeConfigRepository(
        private var config: AmbientConfig,
    ) : AmbientConfigRepository {
        private val listeners = mutableSetOf<() -> Unit>()

        override fun currentConfig() = config

        override fun registerListener(onChanged: () -> Unit): AmbientRegistration {
            listeners += onChanged
            return AmbientRegistration { listeners -= onChanged }
        }

        fun update(config: AmbientConfig) {
            this.config = config
            listeners.toList().forEach { it() }
        }
    }

    private class SnapshotRaceConfigRepository(
        initial: AmbientConfig,
        private val updated: AmbientConfig,
    ) : AmbientConfigRepository {
        private var config = initial
        private var racePending = true
        private val listeners = mutableSetOf<() -> Unit>()

        override fun currentConfig(): AmbientConfig {
            val snapshot = config
            if (racePending) {
                racePending = false
                config = updated
                listeners.toList().forEach { it() }
            }
            return snapshot
        }

        override fun registerListener(onChanged: () -> Unit): AmbientRegistration {
            listeners += onChanged
            return AmbientRegistration { listeners -= onChanged }
        }
    }

    private class RecordingBrightnessSink : BrightnessSink {
        val decisions = mutableListOf<BrightnessDecision>()

        override fun apply(decision: BrightnessDecision) {
            decisions += decision
        }
    }

    private class RecordingPlaybackHost : AmbientPlaybackHost {
        var state = PlaybackState.PLAYING
        var pauseCount = 0
        var resumeCount = 0

        override fun playbackState() = state

        override fun pauseForPresence() {
            pauseCount++
        }

        override fun resumeFromPresence() {
            resumeCount++
        }
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}

package com.jedon.kellikanvas.platform.ambient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Window
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.Executor

class AndroidAmbientSensorSource(
    context: Context,
    private val permissionResolver: SensorPermissionResolver = SensorPermissionResolver.Standard,
) : AmbientSensorSource {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensors: List<Sensor> = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
    override val inventory = Inventory(sensors.map { it.toDescriptor(permissionResolver) })

    override fun register(
        sensor: SensorDescriptor,
        onValue: (Float) -> Unit,
    ): AmbientRegistration? {
        val androidSensor =
            sensors.firstOrNull {
                it.toDescriptor(permissionResolver).fingerprint == sensor.fingerprint
            } ?: return null
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    event.values.firstOrNull()?.let(onValue)
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        if (!sensorManager.registerListener(listener, androidSensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            return null
        }
        var registered = true
        return AmbientRegistration {
            if (registered) {
                registered = false
                sensorManager.unregisterListener(listener, androidSensor)
            }
        }
    }
}

class AndroidPermissionChecker(
    private val context: Context,
) : AmbientPermissionChecker {
    override fun hasPermission(permission: String): Boolean = context.checkSelfPermission(permission) ==
        PackageManager.PERMISSION_GRANTED
}

data object AndroidElapsedTimeSource : ElapsedTimeSource {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

class AndroidTimezoneChangeSource(
    context: Context,
) : TimezoneChangeSource {
    private val applicationContext = context.applicationContext

    override fun register(onChanged: () -> Unit): AmbientRegistration {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) onChanged()
                }
            }
        if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            )
        }
        var registered = true
        return AmbientRegistration {
            if (registered) {
                registered = false
                applicationContext.unregisterReceiver(receiver)
            }
        }
    }
}

class AndroidAmbientLifecycle(
    context: Context,
    window: Window,
    configRepository: AmbientConfigRepository,
    playbackHost: AmbientPlaybackHost,
    eventExecutor: Executor = context.mainExecutor,
    wallClock: Clock = Clock.systemUTC(),
    zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    sensorSource: AmbientSensorSource = AndroidAmbientSensorSource(context),
) {
    private val controller =
        AmbientLifecycleController(
            configRepository = configRepository,
            sensorSource = sensorSource,
            permissionChecker = AndroidPermissionChecker(context),
            elapsedTimeSource = AndroidElapsedTimeSource,
            timezoneChangeSource = AndroidTimezoneChangeSource(context),
            brightnessSink = WindowBrightnessSink(window),
            playbackHost = playbackHost,
            eventExecutor = eventExecutor,
            wallClock = wallClock,
            zoneIdProvider = zoneIdProvider,
            buildFingerprintProvider = { Build.FINGERPRINT },
        )

    val diagnostics: AmbientRuntimeDiagnostics
        get() = controller.diagnostics

    fun start() = controller.start()

    fun stop() = controller.stop()
}

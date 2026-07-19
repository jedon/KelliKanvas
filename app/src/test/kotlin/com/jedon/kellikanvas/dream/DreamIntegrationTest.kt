@file:Suppress("DEPRECATION")

package com.jedon.kellikanvas.dream

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.dreams.DreamService
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.platform.ambient.CapabilityStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DreamIntegrationTest {
    @Test
    fun `manifest declares exported dream service with binding permission and metadata`() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, KelliKanvasDreamService::class.java)
        val serviceInfo =
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )

        assertThat(serviceInfo.exported).isTrue()
        assertThat(serviceInfo.permission).isEqualTo("android.permission.BIND_DREAM_SERVICE")
        assertThat(serviceInfo.metaData.getInt(DreamService.DREAM_META_DATA)).isNotEqualTo(0)
        assertThat(
            context.packageManager
                .queryIntentServices(
                    Intent(DreamService.SERVICE_INTERFACE),
                    PackageManager.ResolveInfoFlags.of(0),
                ).map { it.serviceInfo.name },
        ).contains(KelliKanvasDreamService::class.java.name)
    }

    @Test
    fun `probe reports declaration settings and verification separately`() {
        val context = RuntimeEnvironment.getApplication()
        val report =
            DreamCapabilityProbe(context).probe(
                ComponentName(context, KelliKanvasDreamService::class.java),
            )

        assertThat(report.declared).isTrue()
        assertThat(report.settingAvailable).isFalse()
        assertThat(report.deviceStatus).isEqualTo(CapabilityStatus.CANDIDATE_UNVERIFIED)
    }

    @Test
    fun `session finishes without attaching when no collection is playable`() {
        val host = RecordingDreamHost(hasCollection = false)
        var finished = false
        val root = FrameLayout(RuntimeEnvironment.getApplication())

        val started = DreamSession(host).start(root) { finished = true }

        assertThat(started).isFalse()
        assertThat(finished).isTrue()
        assertThat(host.attachCount).isEqualTo(0)
    }

    @Test
    fun `session attaches and detaches injected slideshow host`() {
        val host = RecordingDreamHost(hasCollection = true)
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        val session = DreamSession(host)

        assertThat(session.start(root) {}).isTrue()
        session.stop()

        assertThat(host.attachCount).isEqualTo(1)
        assertThat(host.detachCount).isEqualTo(1)
    }

    @Test
    fun `production host resolver uses host installed by application graph`() {
        val host = RecordingDreamHost(hasCollection = true)
        val application = HostApplication(host)

        assertThat(resolveDreamSlideshowHost(application)).isSameInstanceAs(host)
    }

    @Test
    fun `dream lifecycle starts installed host only while dreaming`() {
        val host = RecordingDreamHost(hasCollection = true)
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        var finished = false
        val lifecycle =
            DreamLifecycle(
                host = host,
                containerProvider = { root },
                finish = { finished = true },
            )

        assertThat(host.attachCount).isEqualTo(0)
        lifecycle.onDreamingStarted()
        assertThat(host.attachCount).isEqualTo(1)
        assertThat(finished).isFalse()

        lifecycle.onDreamingStopped()
        assertThat(host.detachCount).isEqualTo(1)
    }

    @Test
    fun `dream lifecycle gracefully finishes when application host is unavailable`() {
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        var finished = false
        val lifecycle =
            DreamLifecycle(
                host = resolveDreamSlideshowHost(Application()),
                containerProvider = { root },
                finish = { finished = true },
            )

        lifecycle.onDreamingStarted()

        assertThat(finished).isTrue()
    }

    @Test
    fun `double onDreamingStarted stops previous lifecycle before replace`() {
        val firstHost = RecordingDreamHost(hasCollection = true)
        val secondHost = RecordingDreamHost(hasCollection = true)
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        val service =
            InjectableDreamService(
                hosts = ArrayDeque(listOf(firstHost, secondHost)),
                container = root,
            )

        service.beginDreamSession()
        assertThat(firstHost.attachCount).isEqualTo(1)
        assertThat(firstHost.detachCount).isEqualTo(0)

        service.beginDreamSession()
        assertThat(firstHost.detachCount).isEqualTo(1)
        assertThat(secondHost.attachCount).isEqualTo(1)

        service.endDreamSession()
        assertThat(firstHost.detachCount).isEqualTo(1)
        assertThat(secondHost.detachCount).isEqualTo(1)
    }

    @Test
    fun `dream service finishes immediately when slideshow host is unavailable`() {
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        var finished = false
        val service =
            InjectableDreamService(
                hosts = ArrayDeque(listOf(DreamSlideshowHost.Unavailable)),
                container = root,
                onFinish = { finished = true },
            )

        service.beginDreamSession()

        assertThat(finished).isTrue()
    }

    @Test
    fun `dream window configuration is fullscreen without wake flags`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )

        configureDreamWindow(activity.window)

        val flags = activity.window.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_FULLSCREEN).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON).isEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON).isEqualTo(0)
    }

    private class RecordingDreamHost(
        private val hasCollection: Boolean,
    ) : DreamSlideshowHost {
        var attachCount = 0
        var detachCount = 0

        override fun hasPlayableCollection() = hasCollection

        override fun attach(container: ViewGroup) {
            attachCount++
        }

        override fun detach() {
            detachCount++
        }
    }

    private class HostApplication(
        private val host: DreamSlideshowHost,
    ) : Application(),
        DreamSlideshowHostProvider {
        override fun dreamSlideshowHost() = host
    }

    /**
     * Exercises [KelliKanvasDreamService] lifecycle replacement without full DreamService window setup.
     */
    private class InjectableDreamService(
        private val hosts: ArrayDeque<DreamSlideshowHost>,
        private val container: ViewGroup,
        private val onFinish: () -> Unit = {},
    ) : KelliKanvasDreamService() {
        override fun createSlideshowHost(): DreamSlideshowHost = hosts.removeFirst()

        override fun dreamContainer(): ViewGroup = container

        override fun finishDream() {
            onFinish()
        }
    }
}

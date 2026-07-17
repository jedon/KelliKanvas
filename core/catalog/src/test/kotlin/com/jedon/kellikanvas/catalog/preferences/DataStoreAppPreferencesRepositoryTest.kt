package com.jedon.kellikanvas.catalog.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.model.BlurStrength
import com.jedon.kellikanvas.model.BrightnessMode
import com.jedon.kellikanvas.model.LayoutMode
import com.jedon.kellikanvas.model.NewPhotosPolicy
import com.jedon.kellikanvas.model.PlaybackOrder
import com.jedon.kellikanvas.model.PortraitFit
import com.jedon.kellikanvas.model.PortraitPairingMode
import com.jedon.kellikanvas.model.TransitionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAppPreferencesRepositoryTest {
    @Test
    fun `defaults match approved app preferences and home state`() = runTest {
        val fixture = fixture()

        val state = fixture.repository.preferences.first()

        assertThat(state.appPreferences).isEqualTo(AppPreferences())
        assertThat(state.reducedMotion).isFalse()
        assertThat(state.lastHomeDestination).isEqualTo(HomeDestination.COLLECTION)
        fixture.close()
    }

    @Test
    fun `all approved non-secret values round trip`() = runTest {
        val fixture = fixture()
        val expected =
            AppPreferencesState(
                appPreferences =
                AppPreferences(
                    landscapeLayout = LayoutMode.SOLID_BACKGROUND,
                    singlePortraitLayout = LayoutMode.FULL_PHOTO,
                    singlePortraitFit = PortraitFit.FILL_SCREEN,
                    portraitPairingMode = PortraitPairingMode.ALWAYS,
                    portraitLookAhead = 2,
                    pairGutterDp = 8,
                    blurStrength = BlurStrength.HIGH,
                    blurDimAmount = 0.6,
                    slideDurationMillis = 30_000,
                    transitionType = TransitionType.FADE_THROUGH_BLACK,
                    transitionDurationMillis = 1_200,
                    playbackOrder = PlaybackOrder.CAPTURE_DATE_DESC,
                    loopEnabled = false,
                    resumeEnabled = false,
                    newPhotosPolicy = NewPhotosPolicy.NEXT_CYCLE,
                    metadataOverlayEnabled = true,
                    clockOverlayEnabled = true,
                    captureDateOverlayEnabled = true,
                    filenameOverlayEnabled = true,
                    presenceEnabled = true,
                    brightnessMode = BrightnessMode.SCHEDULE,
                ),
                reducedMotion = true,
                lastHomeDestination = HomeDestination.SETTINGS,
            )

        fixture.repository.update { expected }

        assertThat(fixture.repository.preferences.first()).isEqualTo(expected)
        fixture.close()
    }

    @Test
    fun `invalid slide timing is rejected before persistence`() = runTest {
        val fixture = fixture()

        val failure =
            runCatching {
                fixture.repository.setSlideTiming(
                    slideDurationMillis = 1_000,
                    transitionDurationMillis = 1_000,
                )
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fixture.repository.preferences.first().appPreferences)
            .isEqualTo(AppPreferences())
        fixture.close()
    }

    @Test
    fun `reduced motion and last home focus route persist across repository instances`() = runTest {
        val folder = TemporaryFolder().apply { create() }
        val file = File(folder.root, "preferences.preferences_pb")
        val first = fixture(file)
        first.repository.update {
            it.copy(
                reducedMotion = true,
                lastHomeDestination = HomeDestination.SLIDESHOW,
            )
        }
        first.close()

        val second = fixture(file)
        val restored = second.repository.preferences.first()

        assertThat(restored.reducedMotion).isTrue()
        assertThat(restored.lastHomeDestination).isEqualTo(HomeDestination.SLIDESHOW)
        second.close()
        folder.delete()
    }

    @Test
    fun `preference key names contain no credential terminology`() {
        val forbidden = listOf("password", "token", "secret", "credential")

        assertThat(
            PreferenceKeys.names.none { name ->
                forbidden.any(name.lowercase()::contains)
            },
        ).isTrue()
    }

    private fun TestScope.fixture(file: File? = null): Fixture {
        val folder = TemporaryFolder().apply { create() }
        val preferenceFile = file ?: File(folder.root, "preferences.preferences_pb")
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                preferenceFile
            }
        return Fixture(
            repository = DataStoreAppPreferencesRepository(dataStore),
            scope = scope,
            temporaryFolder = if (file == null) folder else null,
        )
    }

    private data class Fixture(
        val repository: DataStoreAppPreferencesRepository,
        val scope: CoroutineScope,
        val temporaryFolder: TemporaryFolder?,
    ) {
        fun close() {
            scope.cancel()
            temporaryFolder?.delete()
        }
    }
}

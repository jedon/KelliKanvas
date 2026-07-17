package com.jedon.kellikanvas.catalog.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAppPreferencesRepositoryTest {
    @Test
    fun `defaults match approved app preferences and home state`() = runTest {
        val fixture = fixture()

        val state = fixture.repository.preferences.first()

        assertThat(state.appPreferences).isEqualTo(AppPreferences())
        assertThat(state.reducedMotion).isFalse()
        assertThat(state.lastHomeControl).isEqualTo(HomeControl.START_OR_RESUME)
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
                lastHomeControl = HomeControl.AMBIENT_AND_SYSTEM,
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
    fun `reduced motion and exact last used home control persist across repository instances`() = runTest {
        val folder = TemporaryFolder().apply { create() }
        val file = File(folder.root, "preferences.preferences_pb")
        val first = fixture(file)
        first.repository.update {
            it.copy(
                reducedMotion = true,
                lastHomeControl = HomeControl.PLAYBACK,
            )
        }
        first.close()

        val second = fixture(file)
        val restored = second.repository.preferences.first()

        assertThat(restored.reducedMotion).isTrue()
        assertThat(restored.lastHomeControl).isEqualTo(HomeControl.PLAYBACK)
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

    @Test
    fun `actual persisted preference keys contain no credential terminology`() = runTest {
        val fixture = fixture()
        fixture.repository.update {
            it.copy(reducedMotion = true, lastHomeControl = HomeControl.APPEARANCE)
        }
        val forbidden = listOf("password", "token", "secret", "credential")

        val persistedNames = fixture.dataStore.data.first().asMap().keys.map { it.name }

        assertThat(persistedNames).isNotEmpty()
        assertThat(
            persistedNames.none { name ->
                forbidden.any(name.lowercase()::contains)
            },
        ).isTrue()
        fixture.close()
    }

    @Test
    fun `concurrent independent transforms are atomic`() = runTest {
        val repository = DataStoreAppPreferencesRepository(DelayedSnapshotDataStore())

        listOf(
            async {
                repository.update { current ->
                    current.copy(reducedMotion = true)
                }
            },
            async {
                repository.update { current ->
                    current.copy(lastHomeControl = HomeControl.PLAYBACK)
                }
            },
        ).awaitAll()

        val restored = repository.preferences.first()
        assertThat(restored.reducedMotion).isTrue()
        assertThat(restored.lastHomeControl).isEqualTo(HomeControl.PLAYBACK)
    }

    @Test
    fun `invalid individual values are sanitized without resetting valid fields`() = runTest {
        val fixture = fixture()
        fixture.dataStore.edit {
            it[intPreferencesKey("portrait_look_ahead_v1")] = 99
            it[booleanPreferencesKey("clock_overlay_enabled_v1")] = true
        }

        val restored = fixture.repository.preferences.first()

        assertThat(restored.appPreferences.portraitLookAhead)
            .isEqualTo(AppPreferences().portraitLookAhead)
        assertThat(restored.appPreferences.clockOverlayEnabled).isTrue()
        fixture.close()
    }

    @Test
    fun `factory replaces a corrupt preference file with defaults`() = runTest {
        val folder = TemporaryFolder().apply { create() }
        val file = File(folder.root, "corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x13, 0x37, 0x00))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val repository = DataStoreAppPreferencesRepositoryFactory.create(file, scope)

        assertThat(repository.preferences.first()).isEqualTo(AppPreferencesState())

        scope.cancel()
        folder.delete()
    }

    @Test
    fun `preference reads recover from io failure and writes still report it`() = runTest {
        val ioFailureStore =
            object : DataStore<Preferences> {
                override val data = flow<Preferences> { throw IOException("disk unavailable") }

                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences,
                ): Preferences = throw IOException("disk unavailable")
            }
        val repository = DataStoreAppPreferencesRepository(ioFailureStore)

        assertThat(repository.preferences.first()).isEqualTo(AppPreferencesState())
        assertThat(
            runCatching {
                repository.update { it.copy(reducedMotion = true) }
            }.exceptionOrNull(),
        ).isInstanceOf(IOException::class.java)
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
            dataStore = dataStore,
            scope = scope,
            temporaryFolder = if (file == null) folder else null,
        )
    }

    private data class Fixture(
        val repository: DataStoreAppPreferencesRepository,
        val dataStore: DataStore<Preferences>,
        val scope: CoroutineScope,
        val temporaryFolder: TemporaryFolder?,
    ) {
        fun close() {
            scope.cancel()
            temporaryFolder?.delete()
        }
    }

    private class DelayedSnapshotDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private var current: Preferences = emptyPreferences()

        override val data =
            flow {
                val snapshot = current
                delay(1)
                emit(snapshot)
            }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            transform(current).also { current = it }
        }
    }
}

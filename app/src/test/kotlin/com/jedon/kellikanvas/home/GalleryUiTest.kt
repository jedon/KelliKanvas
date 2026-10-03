package com.jedon.kellikanvas.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.feature.settings.AppearanceSettingsScreen
import com.jedon.kellikanvas.feature.settings.PlaybackSettingsScreen
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.ui.tv.KanvasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profile = SourceProfileId("test-gallery")
    private val roots = listOf(SelectedRoot("default", profile, ProviderObjectId("photos"), "Family photographs", true))

    @Test
    fun connectedHomeStartsWithPlayFocusedAndRemoteOpensCollection() {
        var plays = 0
        home(ready = true, onPlay = { plays++ })
        compose.onNodeWithText("Start slideshow").assertIsDisplayed().assertIsEnabled().assertIsFocused()
        capture("home-connected")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertThat(plays).isEqualTo(1) }
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.onNodeWithText("Your collection").assertIsDisplayed()
        compose.onNodeWithText("Family photographs").assertIsDisplayed()
        capture("collection")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Start slideshow").assertIsDisplayed()
    }

    @Test
    fun emptyHomeFocusesConnectionActionAndKeepsSourcesReachable() {
        home(ready = false)
        compose.onNodeWithText("Connect your photos").assertIsDisplayed().assertIsEnabled().assertIsFocused()
        capture("home-empty")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Choose folder").assertIsDisplayed().assertIsEnabled()
        capture("collection-empty")
    }

    @Test
    fun folderRemovalRequiresConfirmationAndCancelKeepsFolder() {
        var removals = 0
        home(ready = true, onRemove = { removals++ })
        compose.onNodeWithText("Manage collection").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove this folder?").assertIsDisplayed()
        compose.onNodeWithText("Keep folder").performClick()
        compose.runOnIdle { assertThat(removals).isEqualTo(0) }
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove folder").performClick()
        compose.runOnIdle { assertThat(removals).isEqualTo(1) }
    }

    @Test
    fun playbackSettingRespondsToRemoteArrows() {
        var value = AppPreferencesState().appPreferences
        compose.setContent {
            KanvasTheme {
                PlaybackSettingsScreen(AppPreferencesState(), { change -> value = change(value) }, {})
            }
        }
        compose.onNodeWithText("Slide duration").assertIsDisplayed()
        capture("playback")
        compose.onNodeWithText("Slide duration").performClick()
        compose.onNodeWithText("Slide duration").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Slide duration").assertIsFocused()
        val before = value.slideDurationMillis
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertThat(value.slideDurationMillis).isEqualTo(before + 1_000) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertThat(value.slideDurationMillis).isEqualTo(before) }
    }

    @Test
    fun appearanceUsesSharedSettingsLayout() {
        compose.setContent { KanvasTheme { AppearanceSettingsScreen(AppPreferencesState(), {}, {}) } }
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Landscape layout").assertIsDisplayed()
        capture("appearance")
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun phoneHomeKeepsCollectionAndSettingsAccessible() {
        compose.setContent {
            HomeScreen("Your photos", false, emptyList(), emptyMap(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("Connect your photos").assertIsDisplayed().assertIsEnabled()
        capture("phone-home")
        compose.onNodeWithText("Photos").performClick()
        compose.onNodeWithText("Your collection").assertIsDisplayed()
        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        capture("phone-collection")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Playback").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun phoneSettingsHaveWorkingDecrementAndIncrementButtons() {
        var value = AppPreferencesState().appPreferences
        compose.setContent { KanvasTheme { PlaybackSettingsScreen(AppPreferencesState(), { change -> value = change(value) }, {}) } }
        compose.onNodeWithText("Back").assertIsDisplayed().assertIsEnabled()
        capture("phone-playback")
        val before = value.slideDurationMillis
        compose.onAllNodes(androidx.compose.ui.test.hasText("−"))[0].performClick()
        compose.runOnIdle { assertThat(value.slideDurationMillis).isEqualTo(before - 1_000) }
        compose.onAllNodes(androidx.compose.ui.test.hasText("+"))[0].performClick()
        compose.runOnIdle { assertThat(value.slideDurationMillis).isEqualTo(before) }
    }

    private fun home(ready: Boolean, onPlay: () -> Unit = {}, onRemove: (SelectedRoot) -> Unit = {}) {
        compose.setContent {
            TvHomeShell(
                "Family photographs", ready, if (ready) roots else emptyList(), mapOf(profile to "Household NAS"),
                onPlay, {}, {}, {}, {}, {}, {}, {}, {}, onRemove,
            )
        }
    }

    private fun capture(name: String) {
        val file = File("build/reports/ui-redesign/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { stream ->
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
            }
        }
    }
}

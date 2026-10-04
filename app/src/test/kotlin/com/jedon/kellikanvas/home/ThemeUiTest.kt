package com.jedon.kellikanvas.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.preferences.AppPreferencesState
import com.jedon.kellikanvas.feature.settings.AppearanceSettingsScreen
import com.jedon.kellikanvas.model.AppPreferences
import com.jedon.kellikanvas.model.AppTheme
import com.jedon.kellikanvas.ui.PhoneMaterialTheme
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasTheme
import com.jedon.kellikanvas.ui.tv.kanvasPalette
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
class ThemeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvRemoteSelectsThemeAndUpdatesNestedSettingsImmediately() {
        var preferences by mutableStateOf(AppPreferences())
        compose.setContent {
            KanvasTheme(theme = preferences.theme) {
                AppearanceSettingsScreen(AppPreferencesState(preferences), { change -> preferences = change(preferences) }, {})
            }
        }
        compose.onNodeWithText("Kelli").assertIsSelected()
        capture("theme-picker-kelli-tv")
        compose.onNodeWithText("Paper").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertThat(preferences.theme).isEqualTo(AppTheme.PAPER) }
        compose.onNodeWithText("Paper").assertIsSelected()
        capture("theme-picker-paper-tv")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Landscape layout"))
        compose.onNodeWithText("Landscape layout").assertIsDisplayed()
    }

    @Test fun nestedWrappersInheritLiveSelectionInsteadOfResettingThePalette() {
        var selection by mutableStateOf(AppTheme.PAPER)
        var observed = kanvasPalette(AppTheme.KELLI).background
        compose.setContent {
            KanvasTheme(selection) {
                PhoneMaterialTheme {
                    KanvasTheme {
                        observed = KanvasColors.Background
                        Text("Theme probe")
                    }
                }
            }
        }
        compose.runOnIdle {
            assertThat(observed).isEqualTo(kanvasPalette(AppTheme.PAPER).background)
            selection = AppTheme.MIDNIGHT
        }
        compose.runOnIdle { assertThat(observed).isEqualTo(kanvasPalette(AppTheme.MIDNIGHT).background) }
    }

    @Test fun kelliHomeUsesOriginalLogoAndPresetsKeepMainActionsReachable() {
        var selection by mutableStateOf(AppTheme.KELLI)
        compose.setContent {
            KanvasTheme(selection) {
                TvHomeShell("Kelli's photographs", false, emptyList(), emptyMap(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("KelliKanvas logo").assertIsDisplayed()
        compose.onNodeWithText("Connect your photos").assertIsDisplayed()
        capture("home-kelli-tv")
        listOf(AppTheme.GALLERY, AppTheme.MIDNIGHT, AppTheme.PAPER, AppTheme.SYSTEM).forEach { theme ->
            compose.runOnIdle { selection = theme }
            compose.onNodeWithText("Connect your photos").assertIsDisplayed()
            capture("home-${theme.name.lowercase()}-tv")
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun phoneCanChooseAThemeAndKeepBackAccessible() {
        var preferences by mutableStateOf(AppPreferences())
        compose.setContent {
            KanvasTheme(preferences.theme) {
                AppearanceSettingsScreen(AppPreferencesState(preferences), { change -> preferences = change(preferences) }, {})
            }
        }
        compose.onNodeWithText("Kelli").assertIsSelected()
        capture("theme-picker-kelli-phone")
        compose.onNodeWithText("Midnight").performScrollTo().performClick()
        compose.runOnIdle { assertThat(preferences.theme).isEqualTo(AppTheme.MIDNIGHT) }
        compose.onNodeWithText("Midnight").assertIsSelected()
        compose.onNodeWithText("Back").assertDoesNotExist()
        capture("theme-picker-midnight-phone")
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun phoneHomeShowsOriginalLogoAndSupportsDeviceColors() {
        var selection by mutableStateOf(AppTheme.KELLI)
        compose.setContent {
            KanvasTheme(selection) {
                HomeScreen("Kelli's photographs", false, emptyList(), emptyMap(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("KelliKanvas logo").assertIsDisplayed()
        compose.onNodeWithText("Connect your photos").assertIsDisplayed()
        capture("home-kelli-phone")
        compose.runOnIdle { selection = AppTheme.SYSTEM }
        compose.onNodeWithContentDescription("KelliKanvas logo").assertIsDisplayed()
        compose.onNodeWithText("Connect your photos").assertIsDisplayed()
        capture("home-system-phone")
    }

    private fun capture(name: String) {
        val file = File("build/reports/themes/$name.png")
        file.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

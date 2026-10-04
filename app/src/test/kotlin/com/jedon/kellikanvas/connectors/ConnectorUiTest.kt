package com.jedon.kellikanvas.connectors

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.source.connected.PhotoConnector
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
class ConnectorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `connector catalog can search a service and open the selected setup`() {
        var selected: PhotoConnector? = null
        compose.setContent { KanvasTheme { ConnectorCatalogScreen({ selected = it }, {}) } }
        compose.onNodeWithText("Connect Jellyfin").assertIsDisplayed()
        capture("connector-catalog-tv")
        compose.onNodeWithText("Find a service").performTextInput("Immich")
        compose.onNodeWithText("Connect Jellyfin").assertDoesNotExist()
        compose.onNodeWithText("Connect Immich").assertIsDisplayed()
        capture("connector-search-tv")
        compose.onNodeWithText("Connect Immich").performClick()
        compose.runOnIdle { assertThat(selected).isEqualTo(PhotoConnector.IMMICH) }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/connectors/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

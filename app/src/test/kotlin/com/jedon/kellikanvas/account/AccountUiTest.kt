package com.jedon.kellikanvas.account

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jedon.kellikanvas.ui.tv.KanvasSetupScaffold
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
class AccountUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `TV displays a scannable public HTTPS pairing link and cancellation`() = renderAndCheck("account-pairing-tv")

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun `phone sized account screen keeps code readable`() = renderAndCheck("account-pairing-phone")
    private fun renderAndCheck(name: String) {
        val link = "https://kanvas.kelli.photo/pair?code=ABCDEFGH"
        var cancels = 0
        compose.setContent {
            KanvasTheme {
                KanvasSetupScaffold("Your KelliKanvas account", "Your gallery settings and connections, saved for your screens.", {}) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        HostedPhonePrompt(link, "ABCDEFGH", "https://kanvas.kelli.photo") { cancels++ }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Account phone sign-in QR code").assertIsDisplayed()
        compose.onNodeWithText("TV code: ABCD EFGH").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/account/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertThat(MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text).isEqualTo(link)
            bitmap.recycle()
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertThat(cancels).isEqualTo(1) }
    }
}

package com.jedon.kellikanvas.nas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import com.jedon.kellikanvas.feature.collection.NasPhonePairingPrompt
import com.jedon.kellikanvas.feature.collection.NasPhoneReviewPrompt
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
class PhoneNasUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvShowsScannablePhoneLinkAndCancelAction() {
        val link = "http://192.168.1.42:41234/pair#" + "a".repeat(64)
        var cancels = 0
        compose.setContent {
            KanvasTheme {
                KanvasSetupScaffold("Connect your NAS", "Bring your household photo library to your TV.", {}) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        NasPhonePairingPrompt(link, System.currentTimeMillis() + 600_000) { cancels++ }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Scan to enter your NAS login on your phone").assertIsDisplayed()
        compose.onNodeWithText("Enter your NAS login on your phone").assertIsDisplayed()
        val file = capture("nas-phone-pairing-tv")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val scanned = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
            assertThat(scanned.text).isEqualTo(link)
        } finally {
            bitmap.recycle()
        }
        compose.onNodeWithText("Cancel phone setup").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(cancels).isEqualTo(1) }
    }

    @Test fun tvRequiresConfirmationAndCanDiscardAReceivedLogin() {
        var connects = 0
        var discards = 0
        compose.setContent {
            KanvasTheme {
                KanvasSetupScaffold("Connect your NAS", "Bring your household photo library to your TV.", {}) { padding ->
                    Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        NasPhoneReviewPrompt("Frame user", { connects++ }, { discards++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Login received from your phone").assertIsDisplayed()
        compose.runOnIdle { assertThat(connects).isEqualTo(0) }
        capture("nas-phone-confirmation-tv")
        compose.onNodeWithText("Connect NAS").assertIsEnabled().performClick()
        compose.onNodeWithText("Discard login").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertThat(connects).isEqualTo(1)
            assertThat(discards).isEqualTo(1)
        }
    }

    private fun capture(name: String): File {
        val file = File("build/reports/nas-phone/$name.png")
        file.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return file
    }
}

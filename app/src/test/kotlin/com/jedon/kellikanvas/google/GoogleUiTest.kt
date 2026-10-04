package com.jedon.kellikanvas.google

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jedon.kellikanvas.catalog.KelliKanvasDatabaseFactory
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.feature.collection.CollectionHubScreen
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.security.CredentialReadResult
import com.jedon.kellikanvas.security.CredentialVault
import com.jedon.kellikanvas.source.google.GoogleAmbientDevice
import com.jedon.kellikanvas.source.google.GoogleDeviceChallenge
import com.jedon.kellikanvas.source.google.GoogleTvConfiguration
import com.jedon.kellikanvas.ui.tv.KanvasColors
import com.jedon.kellikanvas.ui.tv.KanvasSetupScaffold
import com.jedon.kellikanvas.ui.tv.KanvasTheme
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoogleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cloudSourcesAreReachableAndInvokeSeparateSetupActions() {
        var drive = 0
        var photos = 0
        compose.setContent {
            KanvasTheme {
                Surface(color = KanvasColors.Background) {
                    CollectionHubScreen(emptyList(), emptyMap(), {}, {}, {}, {}, {}, onAddGoogleDrive = { drive++ }, onAddGooglePhotos = { photos++ })
                }
            }
        }
        compose.onNodeWithText("Connect Drive").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Connect Photos").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertThat(drive).isEqualTo(1)
            assertThat(photos).isEqualTo(1)
        }
        capture("collection-google-sources")
    }

    @Test fun pairingShowsACodeThatCanBeScannedFromTheRenderedTVScreen() {
        val url = "https://www.google.com/device"
        var restarts = 0
        compose.setContent {
            KanvasTheme {
                KanvasSetupScaffold("Connect Google Photos", "Sign in from your phone.", {}) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GooglePairingPrompt(GoogleDeviceChallenge("mock-device-code", "ABCD-EFGH", url, 5, System.currentTimeMillis() + 600_000), true, { restarts++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Enter this code: ABCD-EFGH").assertIsDisplayed()
        compose.onNodeWithText("Sign in using your phone").assertIsDisplayed()
        assertScannable(capture("google-photos-pairing-tv"), url)
        compose.onNodeWithText("Start again").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(restarts).isEqualTo(1) }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun phonePairingShowsScannableLinkAndFallbackCode() {
        val url = "https://www.google.com/device?user_code=ABCD-EFGH"
        compose.setContent {
            KanvasTheme {
                KanvasSetupScaffold("Connect Google Photos", "Sign in from your phone.", {}) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GooglePairingPrompt(GoogleDeviceChallenge("mock-device-code", "ABCD-EFGH", "https://www.google.com/device", 5, System.currentTimeMillis() + 600_000, verificationUrlComplete = url), true, {})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Scan to open Google on your phone").assertIsDisplayed()
        compose.onNodeWithText("Enter this code: ABCD-EFGH").assertIsDisplayed()
        assertScannable(capture("google-photos-pairing-phone"), url)
        compose.onNodeWithText("Start again").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test fun expiredPhoneSignInCanRestartWithoutShowingAStaleQR() {
        var restarts = 0
        compose.setContent {
            KanvasTheme {
                KanvasSetupScaffold("Connect Google Photos", "Sign in from your phone.", {}) { padding ->
                    Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GooglePairingPrompt(GoogleDeviceChallenge("expired", "OLD-CODE", "https://www.google.com/device", 5, System.currentTimeMillis() - 1_000), false, { restarts++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Sign-in code expired").assertIsDisplayed()
        compose.onNodeWithContentDescription("Scan to open Google on your phone").assertDoesNotExist()
        capture("google-photos-pairing-expired-tv")
        compose.onNodeWithText("Start again").assertIsEnabled().performClick()
        compose.runOnIdle { assertThat(restarts).isEqualTo(1) }
    }

    private fun assertScannable(file: File, url: String) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val scanned = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
            assertThat(scanned.text).isEqualTo(url)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun albumsMustBeChosenBeforeAddingGooglePhotos() {
        compose.setContent {
            KanvasTheme {
                KanvasSetupScaffold("Connect Google Photos", "Choose albums from your phone.", {}) { padding ->
                    Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GoogleAlbumsPrompt(GoogleAmbientDevice("mock-device", "https://photos.google.com/settings", false, 5), false, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Add Google Photos").assertIsDisplayed().assertIsNotEnabled()
        capture("google-photos-albums-tv")
    }

    @Test fun savedGoogleFolderReconnectsItsOwnProfile() {
        val profile = SourceProfileId("google-account")
        var reconnect: SourceProfileId? = null
        compose.setContent {
            KanvasTheme {
                CollectionHubScreen(
                    listOf(SelectedRoot("default", profile, ProviderObjectId("album"), "Family album", true)),
                    mapOf(profile to "Google Drive"),
                    {},
                    {},
                    {},
                    {},
                    {},
                    onReconnectGoogle = { reconnect = it },
                )
            }
        }
        compose.onNodeWithText("Reconnect").assertIsDisplayed().performClick()
        compose.runOnIdle { assertThat(reconnect).isEqualTo(profile) }
    }

    @Test fun photosExplainsMissingConfigurationAndDisablesSignIn() {
        setup(SourceKind.GOOGLE_PHOTOS) {
            compose.onNodeWithText("Connect Google Photos").assertIsDisplayed()
            compose.onNodeWithText("Sign in using your phone").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Google Photos has not been enabled in this build. The app's Google connection needs to be configured before sign-in.").assertIsDisplayed()
            capture("google-photos-setup-tv")
        }
    }

    @Test fun configuredPhotosOffersPhoneSignIn() {
        setup(SourceKind.GOOGLE_PHOTOS, configuration = GoogleTvConfiguration("client", "client-secret")) {
            compose.onNodeWithText("Sign in using your phone").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Back").assertIsDisplayed().assertIsEnabled()
            capture("google-photos-configured-tv")
        }
    }

    @Test fun driveOffersNativeSignInAndBackCancelsSetup() {
        var backs = 0
        setup(SourceKind.GOOGLE_DRIVE, onBack = { backs++ }) {
            compose.onNodeWithText("Continue with Google").assertIsDisplayed().assertIsEnabled()
            capture("google-drive-setup-tv")
            compose.onNodeWithText("Back").performClick()
            compose.waitUntil(5_000) { backs == 1 }
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun phoneShowsPhotosSetupAndBackWithoutClipping() {
        setup(SourceKind.GOOGLE_PHOTOS) {
            compose.onNodeWithText("Connect Google Photos").assertIsDisplayed()
            compose.onNodeWithText("Back").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Sign in using your phone").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            capture("google-photos-setup-phone")
        }
    }

    private fun setup(kind: SourceKind, onBack: () -> Unit = {}, configuration: GoogleTvConfiguration = GoogleTvConfiguration("", ""), verify: () -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val database = KelliKanvasDatabaseFactory.inMemory(context)
        val vault = object : CredentialVault {
            override fun write(profileId: SourceProfileId, secret: ByteArray) = error("Sign-in must not run in this layout test")
            override fun write(profileId: SourceProfileId, secret: CharArray) = error("Sign-in must not run in this layout test")
            override fun read(profileId: SourceProfileId) = CredentialReadResult.Missing
            override fun remove(profileId: SourceProfileId) = Unit
        }
        val sources = GoogleSources(context, OkHttpClient(), vault, configuration)
        try {
            compose.setContent { KanvasTheme { GoogleSetupScreen(sources, database, kind, null, {}, onBack) } }
            verify()
            compose.runOnIdle {
                (compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ComposeView).disposeComposition()
            }
        } finally {
            database.close()
        }
    }

    private fun capture(name: String): File {
        val file = File("build/reports/google-sources/$name.png")
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

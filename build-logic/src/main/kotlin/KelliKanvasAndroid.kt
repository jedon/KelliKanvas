import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion

internal const val KELLIKANVAS_COMPILE_SDK = 37
internal const val KELLIKANVAS_MIN_SDK = 29
internal const val KELLIKANVAS_TARGET_SDK = 37

internal fun ApplicationExtension.configureKelliKanvasApplication() {
    compileSdk {
        version =
            release(KELLIKANVAS_COMPILE_SDK) {
                minorApiLevel = 0
            }
    }

    defaultConfig {
        minSdk = KELLIKANVAS_MIN_SDK
        targetSdk = KELLIKANVAS_TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        baseline = null
    }
}

internal fun LibraryExtension.configureKelliKanvasLibrary() {
    compileSdk {
        version =
            release(KELLIKANVAS_COMPILE_SDK) {
                minorApiLevel = 0
            }
    }

    defaultConfig {
        minSdk = KELLIKANVAS_MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        baseline = null
    }
}

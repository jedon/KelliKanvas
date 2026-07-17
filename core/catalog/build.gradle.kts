plugins {
    id("com.jedon.kellikanvas.android.library")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.jedon.kellikanvas.catalog"

    defaultConfig {
        ksp {
            arg("room.schemaLocation", file("schemas").path)
            arg("room.generateKotlin", "true")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:source-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

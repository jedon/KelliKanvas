plugins {
    id("com.jedon.kellikanvas.android.library")
    id("com.jedon.kellikanvas.android.compose")
}

android {
    namespace = "com.jedon.kellikanvas.ui.tv"
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
}

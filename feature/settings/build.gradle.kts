plugins {
    id("com.jedon.kellikanvas.android.library")
    id("com.jedon.kellikanvas.android.compose")
}

android {
    namespace = "com.jedon.kellikanvas.feature.settings"
}

dependencies {
    implementation(project(":core:catalog"))
    implementation(project(":core:model"))
    implementation(project(":core:ui-tv"))
    implementation(project(":platform:ambient"))
    implementation(project(":platform:update"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
}

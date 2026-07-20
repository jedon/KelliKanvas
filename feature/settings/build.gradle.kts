plugins {
    id("com.jedon.kellikanvas.android.library")
    id("com.jedon.kellikanvas.android.compose")
}

android {
    namespace = "com.jedon.kellikanvas.feature.settings"
}

dependencies {
    implementation(project(":core:catalog"))
    implementation(project(":core:logging"))
    implementation(project(":core:model"))
    implementation(project(":core:ui-tv"))
    implementation(project(":platform:ambient"))
    implementation(project(":platform:update"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.tv.material)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}

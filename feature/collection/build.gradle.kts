plugins {
    id("com.jedon.kellikanvas.android.library")
    id("com.jedon.kellikanvas.android.compose")
}

android {
    namespace = "com.jedon.kellikanvas.feature.collection"
}

dependencies {
    implementation(project(":core:catalog"))
    implementation(project(":core:model"))
    implementation(project(":core:source-api"))
    implementation(project(":core:ui-tv"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
}

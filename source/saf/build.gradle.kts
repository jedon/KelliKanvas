plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.source.saf"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:source-api"))
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":core:source-api")))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
}

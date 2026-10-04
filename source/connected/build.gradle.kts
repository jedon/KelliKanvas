plugins { id("com.jedon.kellikanvas.android.library") }

android { namespace = "com.jedon.kellikanvas.source.connected" }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:source-api"))
    implementation(project(":core:security"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

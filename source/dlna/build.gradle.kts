plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.source.dlna"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:source-api"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation("net.sf.kxml:kxml2:2.3.0")

    testImplementation(testFixtures(project(":core:source-api")))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

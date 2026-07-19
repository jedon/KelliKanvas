plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.source.smb"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":core:source-api"))
    implementation(libs.smbj)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

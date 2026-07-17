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
}

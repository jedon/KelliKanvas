plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.source.http"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":core:source-api"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}

plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.source.api"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}

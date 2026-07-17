plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.platform.ambient"
}

dependencies {
    implementation(project(":core:model"))
}

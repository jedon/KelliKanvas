plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.renderer.surface"
}

dependencies {
    implementation(project(":core:image"))
    implementation(project(":core:model"))
}

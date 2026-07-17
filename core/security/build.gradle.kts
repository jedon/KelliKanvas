plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.security"
}

dependencies {
    implementation(project(":core:model"))
}

plugins {
    id("com.jedon.kellikanvas.android.library")
}

android {
    namespace = "com.jedon.kellikanvas.source.saf"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:source-api"))
}

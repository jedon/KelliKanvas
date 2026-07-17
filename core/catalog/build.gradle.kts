plugins {
    id("com.jedon.kellikanvas.android.library")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.jedon.kellikanvas.catalog"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:source-api"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
}

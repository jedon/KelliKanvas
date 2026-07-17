plugins {
    id("com.jedon.kellikanvas.kotlin.jvm")
}

dependencies {
    api(project(":core:model"))
    api(libs.okio)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}

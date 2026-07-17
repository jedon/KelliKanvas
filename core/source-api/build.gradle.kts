plugins {
    id("com.jedon.kellikanvas.kotlin.jvm")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:model"))
    api(libs.okio)

    testFixturesApi(libs.junit4)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.truth)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}

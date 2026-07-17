plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
}

group = "com.jedon.kellikanvas.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.android.tools.build:gradle:9.2.0")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.21")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")

    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
}

gradlePlugin {
    plugins {
        create("androidApplication") {
            id = "com.jedon.kellikanvas.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        create("androidLibrary") {
            id = "com.jedon.kellikanvas.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        create("androidCompose") {
            id = "com.jedon.kellikanvas.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        create("kotlinJvm") {
            id = "com.jedon.kellikanvas.kotlin.jvm"
            implementationClass = "KotlinJvmConventionPlugin"
        }
    }
}

tasks.test {
    useJUnit()
}

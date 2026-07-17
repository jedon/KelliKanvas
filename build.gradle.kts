import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    tasks.withType<KtLintCheckTask>().configureEach {
        source(
            fileTree("src") {
                include("**/*.kt")
                exclude("**/build/**", "**/generated/**")
            },
        )
    }
}

tasks.named("ktlintCheck") {
    dependsOn(gradle.includedBuild("build-logic").task(":ktlintCheck"))
}

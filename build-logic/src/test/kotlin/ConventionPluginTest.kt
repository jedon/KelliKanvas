import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConventionPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun applicationUsesSdk37Point0AndJava17() {
        val result =
            runBuild(
                """
                import com.android.build.api.dsl.ApplicationExtension

                plugins {
                    id("com.jedon.kellikanvas.android.application")
                }

                extensions.configure<ApplicationExtension> {
                    namespace = "com.jedon.test.application"
                }

                val androidExtension = extensions.getByType<ApplicationExtension>()

                tasks.register("verifyConvention") {
                    doLast {
                        check(androidExtension.compileSdk == 37)
                        check(androidExtension.compileSdkMinor == 0)
                        check(androidExtension.defaultConfig.minSdk == 28)
                        check(androidExtension.defaultConfig.targetSdk == 37)
                        check(androidExtension.defaultConfig.testInstrumentationRunner == "androidx.test.runner.AndroidJUnitRunner")
                        check(androidExtension.compileOptions.sourceCompatibility == JavaVersion.VERSION_17)
                        check(androidExtension.compileOptions.targetCompatibility == JavaVersion.VERSION_17)
                        check(androidExtension.lint.warningsAsErrors)
                        check(androidExtension.lint.baseline == null)
                        check(androidExtension.enableKotlin)
                        check(!pluginManager.hasPlugin("org.jetbrains.kotlin.android"))
                        println("APPLICATION_CONVENTION_OK")
                    }
                }
                """.trimIndent(),
            )

        assertThat(result).contains("APPLICATION_CONVENTION_OK")
    }

    @Test
    fun libraryUsesSdk37Point0AndJava17() {
        val result =
            runBuild(
                """
                import com.android.build.api.dsl.LibraryExtension

                plugins {
                    id("com.jedon.kellikanvas.android.library")
                }

                extensions.configure<LibraryExtension> {
                    namespace = "com.jedon.test.library"
                }

                val androidExtension = extensions.getByType<LibraryExtension>()

                tasks.register("verifyConvention") {
                    doLast {
                        check(androidExtension.compileSdk == 37)
                        check(androidExtension.compileSdkMinor == 0)
                        check(androidExtension.defaultConfig.minSdk == 28)
                        check(androidExtension.compileOptions.sourceCompatibility == JavaVersion.VERSION_17)
                        check(androidExtension.compileOptions.targetCompatibility == JavaVersion.VERSION_17)
                        check(androidExtension.lint.warningsAsErrors)
                        check(androidExtension.lint.baseline == null)
                        check(androidExtension.enableKotlin)
                        check(!pluginManager.hasPlugin("org.jetbrains.kotlin.android"))
                        println("LIBRARY_CONVENTION_OK")
                    }
                }
                """.trimIndent(),
            )

        assertThat(result).contains("LIBRARY_CONVENTION_OK")
    }

    @Test
    fun composeConventionEnablesCompose() {
        val result =
            runBuild(
                """
                import com.android.build.api.dsl.LibraryExtension

                plugins {
                    id("com.jedon.kellikanvas.android.library")
                    id("com.jedon.kellikanvas.android.compose")
                }

                extensions.configure<LibraryExtension> {
                    namespace = "com.jedon.test.compose"
                }

                val androidExtension = extensions.getByType<LibraryExtension>()

                tasks.register("verifyConvention") {
                    doLast {
                        check(androidExtension.buildFeatures.compose == true)
                        check(androidExtension.enableKotlin)
                        check(pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.compose"))
                        check(!pluginManager.hasPlugin("org.jetbrains.kotlin.android"))
                        println("COMPOSE_CONVENTION_OK")
                    }
                }
                """.trimIndent(),
            )

        assertThat(result).contains("COMPOSE_CONVENTION_OK")
    }

    @Test
    fun kotlinJvmConventionUsesJava17Toolchain() {
        val result =
            runBuild(
                """
                import org.jetbrains.kotlin.gradle.dsl.JvmTarget
                import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

                plugins {
                    id("com.jedon.kellikanvas.kotlin.jvm")
                }

                val javaExtension = extensions.getByType<JavaPluginExtension>()
                val kotlinExtension = extensions.getByType<KotlinJvmProjectExtension>()

                tasks.register("verifyConvention") {
                    doLast {
                        check(javaExtension.toolchain.languageVersion.get() == JavaLanguageVersion.of(17))
                        check(kotlinExtension.compilerOptions.jvmTarget.get() == JvmTarget.JVM_17)
                        check(pluginManager.hasPlugin("org.jetbrains.kotlin.jvm"))
                        println("KOTLIN_JVM_CONVENTION_OK")
                    }
                }
                """.trimIndent(),
            )

        assertThat(result).contains("KOTLIN_JVM_CONVENTION_OK")
    }

    private fun runBuild(buildScript: String): String {
        val projectDirectory = temporaryFolder.newFolder()
        File(projectDirectory, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "convention-test"
            """.trimIndent(),
        )
        File(projectDirectory, "build.gradle.kts").writeText(buildScript)

        return GradleRunner
            .create()
            .withProjectDir(projectDirectory)
            .withPluginClasspath()
            .withArguments("verifyConvention", "--stacktrace", "--no-configuration-cache")
            .build()
            .output
    }
}

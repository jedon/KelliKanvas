import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure(JavaPluginExtension::class.java) { extension ->
                extension.sourceCompatibility = JavaVersion.VERSION_17
                extension.targetCompatibility = JavaVersion.VERSION_17
                extension.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            }

            extensions.configure(KotlinJvmProjectExtension::class.java) { extension ->
                extension.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

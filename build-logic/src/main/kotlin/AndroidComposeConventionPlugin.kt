import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            pluginManager.withPlugin("com.android.application") {
                extensions.configure(ApplicationExtension::class.java) { extension ->
                    extension.buildFeatures.compose = true
                }
            }

            pluginManager.withPlugin("com.android.library") {
                extensions.configure(LibraryExtension::class.java) { extension ->
                    extension.buildFeatures.compose = true
                }
            }
        }
    }
}

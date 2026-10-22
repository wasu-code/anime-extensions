import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/** For `:utils` and every `:lib:<name>` module: plain Kotlin code in `src/`, no resources. */
@Suppress("UNUSED")
class LibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.apply(AndroidBasePlugin::class.java)

        extensions.configure<LibraryExtension> {
            sourceSets {
                named("main") {
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }
            androidResources.enable = false
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.addProvider("compileOnly", libs.findBundle("common").get())
    }
}

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/**
 * Settings shared by every Android module (:core, :utils, :lib:*, extensions).
 * Expects com.android.application or com.android.library to be applied already.
 */
@Suppress("UNUSED")
class AndroidBasePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val config = projectConfig()
        // ":lib:foo" -> "<namespace>.lib.foo", ":utils" -> "<namespace>.utils"
        val moduleNamespace = "${config.namespace}.${path.removePrefix(":").replace(':', '.').replace('-', '_')}"

        extensions.configure<CommonExtension> {
            namespace = moduleNamespace
            compileSdk = COMPILE_SDK
            defaultConfig.minSdk = MIN_SDK
            compileOptions.sourceCompatibility = JavaVersion.toVersion(JAVA_VERSION)
            compileOptions.targetCompatibility = JavaVersion.toVersion(JAVA_VERSION)
        }

        extensions.findByType(KotlinBaseExtension::class.java)?.compilerOptions {
            optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
            // Kotlin and Java must agree on the JVM target, otherwise Gradle fails the build.
            (this as? KotlinJvmCompilerOptions)?.jvmTarget?.set(JvmTarget.fromTarget(JAVA_VERSION.toString()))
        }
    }
}

private fun KotlinBaseExtension.compilerOptions(block: KotlinCommonCompilerOptions.() -> Unit) {
    if (this is HasConfigurableKotlinCompilerOptions<*>) compilerOptions(block)
}

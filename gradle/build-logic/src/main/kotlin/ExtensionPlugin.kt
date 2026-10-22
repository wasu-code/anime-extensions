import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import buildlogic.ContentWarning
import buildlogic.ExtensionConfig
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.security.MessageDigest

/**
 * Everything an extension module needs. Replaces the old /common.gradle.
 * Usage in src/<lang>/<name>/build.gradle.kts: `plugins { id("ext-android") }` + an `extension { }` block.
 */
@Suppress("UNUSED")
class ExtensionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.apply(AndroidBasePlugin::class.java)

        val config = projectConfig()
        val ext = extensions.create<ExtensionConfig>("extension")
        ext.contentWarning.convention(ContentWarning.SAFE)

        // src/en/foo -> "en.foo"; final applicationId = "<namespace>.extension.en.foo"
        val idSuffix = "${parent!!.name}.$name"
        val appId = "${config.namespace}.extension.$idSuffix"
        val keystore = rootProject.file("signingkey.jks")

        // An extension may ship its own AndroidManifest.xml (deep links).
        val ownManifest = file("AndroidManifest.xml")
        val mainManifest = if (ownManifest.exists()) ownManifest else rootProject.file("core/AndroidManifest.xml")

        extensions.configure<ApplicationExtension> {
            namespace = "${config.namespace}.extension"

            defaultConfig.targetSdk = TARGET_SDK
            defaultConfig.applicationIdSuffix = idSuffix

            sourceSets {
                named("main") {
                    manifest.srcFile(mainManifest)
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            lint {
                checkReleaseBuilds = false
            }

            signingConfigs {
                create("release") {
                    storeFile = keystore
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    // Without a keystore (local builds) fall back to the debug key so assembleRelease still works.
                    signingConfig = if (keystore.exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = false
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        // ---- values derived from the extension { } block (lazy: the block is evaluated after this plugin) ----
        val extName = ext.name.map {
            check(it.all { c -> c.code < 0x180 }) { "Extension name '$it' should be romanized" }
            it
        }
        val extClass = ext.className.map {
            check(it.startsWith(".")) { "className '$it' must be relative to the app package and start with a dot" }
            it
        }
        val versionName = ext.versionCode.map { "$EXTENSION_LIB.$it" }
        val warningCode = ext.contentWarning.map { it.ordinal } // SAFE=0, MIXED=1, NSFW=2 (what tachiyomix expects)

        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                // Fill the ${placeholders} of core/AndroidManifest.xml
                variant.manifestPlaceholders.put("appName", extName.map { "${config.labelPrefix}: $it" })
                variant.manifestPlaceholders.put("extName", extName)
                variant.manifestPlaceholders.put("extClass", extClass)
                variant.manifestPlaceholders.put("contentWarning", warningCode.map { it.toString() })
                variant.manifestPlaceholders.put("nsfw", warningCode.map { if (it == 0) "0" else "1" })
                variant.manifestPlaceholders.put("extensionLib", EXTENSION_LIB)

                variant.outputs.forEach { output ->
                    output.versionCode.set(ext.versionCode)
                    output.versionName.set(versionName)
                }
            }
        }

        dependencies.add("implementation", project(":core")) // manifest template + default launcher icon
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.addProvider("compileOnly", libs.findBundle("common").get())
        dependencies.add("implementation", project(":utils")) //TODO: don't implement all utils or remove unused with R8/proguard

        // ---- metadata file for the repo index ----
        val iconPath = "res/mipmap-xhdpi/ic_launcher.png"
        val iconFile = if (file(iconPath).exists()) file(iconPath) else rootProject.file("core/$iconPath")

        val sourceInfoTask = tasks.register<GenerateSourceInfoTask>("generateSourceInfo") {
            outputFile.set(layout.buildDirectory.file("source-info.json"))
            content.set(
                providers.provider {
                    val code = ext.versionCode.get()
                    val name = extName.get()
                    val sources = ext.sources.get().map { s ->
                        val sourceName = s.name.orElse(name).get()
                        val lang = s.lang.get()
                        mapOf(
                            "id" to s.id.orElse(sourceId(sourceName, lang, s.versionId.getOrElse(1))).get(),
                            "name" to sourceName,
                            "lang" to lang,
                            "baseUrl" to s.baseUrl.getOrElse(""),
                        )
                    }
                    toJson(
                        mapOf(
                            "pkg" to appId,
                            "name" to name,
                            "apk" to "${config.apkPrefix}-$idSuffix-v${versionName.get()}.apk",
                            "folderLang" to parent!!.name,
                            "code" to code,
                            "version" to versionName.get(),
                            "contentWarning" to warningCode.get(),
                            "icon" to iconFile.relativeTo(rootProject.projectDir).invariantSeparatorsPath,
                            "sources" to sources,
                        ),
                    )
                },
            )
        }
        // The CI uploads the APK together with this file, so make sure assembleRelease always writes it.
        tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(sourceInfoTask) }

        afterEvaluate {
            val sources = ext.sources.get()
            check(sources.isNotEmpty()) { "$path: at least one source { } block is required in extension { }" }
            sources.forEach { check(it.lang.isPresent) { "$path: every source { } needs a lang" } }
        }
    }
}

/** Same algorithm as HttpSource.generateId: first 64 bits of MD5("<name lowercase>/<lang>/<versionId>"), sign bit cleared. */
private fun sourceId(name: String, lang: String, versionId: Int): Long {
    val bytes = MessageDigest.getInstance("MD5").digest("${name.lowercase()}/$lang/$versionId".toByteArray())
    return (0..7).map { (bytes[it].toLong() and 0xff) shl (8 * (7 - it)) }.reduce(Long::or) and Long.MAX_VALUE
}

/** Minimal JSON writer so the build logic needs no extra dependency. */
private fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { toJson(it) }
    else -> error("Unsupported JSON value: $value")
}

pluginManagement {
    // Our own Gradle plugins (ext-android, lib-android, base-android) live in this included build.
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories; everything is resolved from here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // tachiyomix and injekt are published through JitPack; only ask it for com.github.* artifacts.
        maven(url = "https://www.jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

/**
 * Add or remove modules to load as needed for local development here.
 */
loadAllIndividualExtensions()
// loadIndividualExtension("all", "mangadex")

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")
include(":utils")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            include("src:${dir.name}:${subdir.name}")
        }
    }
}
fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}

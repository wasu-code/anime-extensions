plugins {
    alias(libs.plugins.android.library)
    id("base-android")
}

// :core contains no code. It only ships the manifest template and the default launcher icons that
// every extension inherits (an extension can override the icons with its own res/ folder).
android {
    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.directories.clear()
            res.directories.add("res")
        }
    }
}

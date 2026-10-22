// The root project builds nothing itself. It only puts the Android and Kotlin Gradle plugins on the
// classpath ONCE (`apply false`), so that every module and our own plugins in gradle/build-logic share
// the same plugin classes. Versions come from gradle/libs.versions.toml.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

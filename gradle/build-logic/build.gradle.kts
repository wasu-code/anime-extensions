plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.samWithReceiver)
    `java-gradle-plugin`
}

dependencies {
    compileOnly(gradleKotlinDsl())
    // compileOnly: the real AGP/KGP are put on the classpath by the root build (see /build.gradle.kts)
    compileOnly(libs.android.gradle)
    compileOnly(libs.kotlin.gradle)
}

// Lets us write `create("release") { storeFile = ... }` instead of `{ it.storeFile = ... }`
// for every Gradle `Action<T>` parameter.
samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

gradlePlugin {
    plugins {
        register("base-android") {
            id = "base-android"
            implementationClass = "AndroidBasePlugin"
        }
        register("ext-android") {
            id = "ext-android"
            implementationClass = "ExtensionPlugin"
        }
        register("lib-android") {
            id = "lib-android"
            implementationClass = "LibraryPlugin"
        }
    }
}

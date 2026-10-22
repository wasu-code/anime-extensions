// Android / toolchain levels. Change them here and every module follows.
internal const val COMPILE_SDK = 37 // tachiyomix 1.6 is compiled against 37
internal const val MIN_SDK = 26 // Mihon requires Android 8.0
internal const val TARGET_SDK = 37
internal const val JAVA_VERSION = 17 // tachiyomix itself targets 17; a consumer must be >= its dependencies

// Written into the manifest as `tachiyomix.extensionLib` and used as the major.minor of versionName.
internal const val EXTENSION_LIB = "1.6"

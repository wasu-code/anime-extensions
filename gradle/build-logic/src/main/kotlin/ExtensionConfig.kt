package buildlogic

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

enum class ContentWarning { SAFE, MIXED, NSFW }

/** The `extension { }` block of an extension's build.gradle.kts. */
abstract class ExtensionConfig @Inject constructor(private val objects: ObjectFactory) {
    /** Display name, romanized. */
    abstract val name: Property<String>

    /** Source class, relative to the app package (starts with a dot), e.g. ".MySource". */
    abstract val className: Property<String>

    /** Increment on every change. versionName is generated as "1.6.<versionCode>". */
    abstract val versionCode: Property<Int>

    abstract val contentWarning: Property<ContentWarning>

    /** One block per source the extension exposes. Only used for the published index (see README). */
    abstract val sources: ListProperty<SourceConfig>

    fun source(block: SourceConfig.() -> Unit) {
        sources.add(objects.newInstance<SourceConfig>().apply(block))
    }
}

abstract class SourceConfig {
    /** Defaults to the extension name. */
    abstract val name: Property<String>

    /** Must be the same value your source class declares, e.g. "en", "pt-BR", "all". */
    abstract val lang: Property<String>

    /** Must be the same value your source class declares. May stay empty for sources without a website. */
    abstract val baseUrl: Property<String>

    /** Defaults to 1. Only set if your source class overrides `versionId`. */
    abstract val versionId: Property<Int>

    /** Only set if your source class overrides `id`. Otherwise it is computed like HttpSource does. */
    abstract val id: Property<Long>
}

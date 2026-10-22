import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the metadata of one extension (package, version, sources, ...) to a JSON file.
 * CI uploads it next to the APK and .github/scripts/publish-repo.py builds the repo index from it,
 * which is what the "extensions inspector" used to do by loading the APK.
 */
@CacheableTask
abstract class GenerateSourceInfoTask : DefaultTask() {
    @get:Input
    abstract val content: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(content.get())
    }
}

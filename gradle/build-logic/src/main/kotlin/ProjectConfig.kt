import org.gradle.api.Project
import java.util.Properties

/** Values from /project.properties. */
internal class ProjectConfig(
    val namespace: String,
    val labelPrefix: String,
    val apkPrefix: String,
)

private val NAMESPACE_REGEX = Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*""")

internal fun Project.projectConfig(): ProjectConfig {
    // providers.fileContents() (instead of File.readText()) lets Gradle track the file as a build input.
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("project.properties")).asText.get()
    val props = Properties().apply { load(text.reader()) }

    fun required(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("project.properties: '$key' is missing or empty")

    return ProjectConfig(
        namespace = required("namespace").also {
            check(NAMESPACE_REGEX.matches(it)) { "project.properties: namespace '$it' is not a valid package name" }
        },
        labelPrefix = required("labelPrefix"),
        apkPrefix = required("apkPrefix"),
    )
}

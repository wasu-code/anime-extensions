package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class PluginState(
    val plugin: SitePlugin,
    val installed: Boolean = false,
    /** Set to `true` when the installed plugin is not available anymore in remote repo */
    val orphaned: Boolean? = null,
    val updateAvailable: Boolean? = null,
)

object PluginManager {
    private val CONTEXT = Injekt.get<Application>()
    private val EXTENSIONS_DIR = File(CONTEXT.filesDir, "cloudstream")

    suspend fun downloadPluginToFile(plugin: SitePlugin): File? =
        withContext(Dispatchers.IO) {
            val pluginUrl = plugin.url
            val file = File(EXTENSIONS_DIR, "${pluginUrl.hashCode()}.cs3")
            try {
                file.parentFile?.mkdirs()
                if (file.exists()) file.delete()
                file.createNewFile()

                val connection = URL(pluginUrl).openConnection() as HttpURLConnection
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        write(input, output)
                    }
                }
                connection.disconnect()
                file.setReadOnly() // Make it read-only (for Android 14+ to read dex files)

                PluginRegistry.upsert(plugin)

                file
            } catch (_: Exception) {
                null
            }
        }

    suspend fun deletePluginFile(pluginUrl: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(EXTENSIONS_DIR, "${pluginUrl.hashCode()}.cs3")
        val deleted = file.delete()
        if (deleted) { PluginRegistry.remove(pluginUrl) }

        deleted
    }

    suspend fun deleteAllPluginFiles(): Boolean = withContext(Dispatchers.IO) {
        EXTENSIONS_DIR.deleteRecursively()
        EXTENSIONS_DIR.mkdirs()
    }

    fun getPluginCount(): Int = EXTENSIONS_DIR.listFiles()?.size ?: 0

    fun getInstalledPlugins(): List<SitePlugin> = PluginRegistry.getAll()

    fun isPluginInstalled(pluginUrl: String): Boolean {
        val plugin = PluginRegistry.get(pluginUrl)
        return plugin != null
    }

    private fun write(stream: InputStream, output: OutputStream) {
        val input = BufferedInputStream(stream)
        val dataBuffer = ByteArray(512)
        var readBytes: Int
        while (input.read(dataBuffer).also { readBytes = it } != -1) {
            output.write(dataBuffer, 0, readBytes)
        }
    }
}

object PluginRegistry {
    private val CONTEXT = Injekt.get<Application>()
    private val REGISTRY_FILE = File(CONTEXT.filesDir, "cloudstream/plugins.json")

    private val JSON = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private var cache: MutableMap<Int, SitePlugin> = mutableMapOf()

    init {
        load()
    }

    private fun load() {
        if (!REGISTRY_FILE.exists()) return
        runCatching {
            val list = JSON.decodeFromString(
                ListSerializer(SitePlugin.serializer()),
                REGISTRY_FILE.readText(),
            )
            cache = list.associateBy { it.url.hashCode() }.toMutableMap()
        }
    }

    private fun save() {
        REGISTRY_FILE.parentFile?.mkdirs()
        REGISTRY_FILE.writeText(
            JSON.encodeToString(
                ListSerializer(SitePlugin.serializer()),
                cache.values.toList(),
            ),
        )
    }

    fun upsert(record: SitePlugin) {
        cache[record.url.hashCode()] = record
        save()
    }

    fun remove(url: String) {
        cache.remove(url.hashCode())
        save()
    }

    fun getAll(): List<SitePlugin> = cache.values.toList()

    fun get(url: String) = cache[url.hashCode()]
}

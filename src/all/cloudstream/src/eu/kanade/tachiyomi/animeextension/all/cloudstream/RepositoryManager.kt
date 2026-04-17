package eu.kanade.tachiyomi.animeextension.all.cloudstream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL

@Serializable
data class Repository(
    val name: String,
    val description: String? = null,
    val pluginLists: List<String> = emptyList(),
)

@Serializable
data class SitePlugin(
    val url: String,
    val name: String,
    val version: Int,
    val description: String? = null,
    /** plugin status (0=down, 1=ok, 2=slow, 3=beta) */
    val status: Int = 1,
    val internalName: String,
    val repositoryUrl: String?,
    val tvTypes: List<String>? = null,
    val language: String? = null,
)

object RepositoryManager {

    private val JSON = Json { ignoreUnknownKeys = true }

    suspend fun parseRepository(url: String): Repository? = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = URL(url).readText()
            JSON.decodeFromString<Repository>(response)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun parsePlugins(url: String): List<SitePlugin> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = URL(url).readText()
            JSON.decodeFromString<List<SitePlugin>>(response)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getRepoPlugins(repoUrl: String): List<SitePlugin> {
        val repo = parseRepository(repoUrl) ?: return emptyList()
        return repo.pluginLists.flatMap { parsePlugins(it) }
    }

    suspend fun getAllPlugins(repos: Set<String>): List<SitePlugin> {
        return repos.flatMap { getRepoPlugins(it) }.distinctBy { it.url }
    }

    suspend fun getWellKnownRepos(): List<String> = withContext(Dispatchers.IO) {
        val jsonUrl = "https://raw.githubusercontent.com/recloudstream/cs-repos/refs/heads/master/repos-db.json"
        val text = URL(jsonUrl).readText()

        val json = Json { ignoreUnknownKeys = true }
        val element = json.parseToJsonElement(text)

        return@withContext element.jsonArray.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item["url"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }
    }
}

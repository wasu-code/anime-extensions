package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Prerelease
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response

/**
 * Adapter: Wraps a Cloudstream MainAPI provider so it can be used
 * as a Aniyomi AnimeHttpSource at runtime.
 */
open class MainApiAdapter(
    private val api: MainAPI,
) : AnimeHttpSource() {
    override val name: String = api.name
    override val baseUrl: String = api.mainUrl
    override val lang: String = when (api.lang) {
        "un" -> "all"
        else -> api.lang
    }
    override val supportsLatest: Boolean = false

    // === Popular Anime ===

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (!api.hasMainPage) throw UnsupportedOperationException("This extension doesn't have main page")

        return runBlocking {
            try {
                api.getMainPage(page, MainPageRequest("popular", baseUrl, false))
                    ?.toAnimePage() as AnimesPage
            } catch (_: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // === Search ===

    @OptIn(Prerelease::class)
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return runBlocking {
            try {
                api.search(query, page)?.toAnimePage() ?: AnimesPage(emptyList(), false)
            } catch (_: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // === Latest Updates ===

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // === Anime Details ===

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = runBlocking {
            try {
                api.load(anime.url)
            } catch (_: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
        return details?.toSAnime() ?: SAnime.create()
    }

    override fun getAnimeUrl(anime: SAnime): String {
        return if (anime.url.startsWith("http")) {
            anime.url
        } else {
            "${baseUrl.removeSuffix("/")}/${anime.url.removePrefix("/")}"
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // === Episode List ===

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val loadResponse = runBlocking {
            try {
                api.load(anime.url)
            } catch (_: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
        return loadResponse?.toSEpisodeList() ?: emptyList()
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        return if (episode.url.startsWith("http")) {
            episode.url
        } else {
            "${baseUrl.removeSuffix("/")}/${episode.url.removePrefix("/")}"
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // === Video Streams ===

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videos = mutableListOf<Video>()
        val subs = mutableListOf<Track>()
        runBlocking {
            try {
                api.loadLinks(
                    episode.url,
                    isCasting = false,
                    subtitleCallback = { subtitleFile ->
                        subs.add(subtitleFile.toTrack())
                    },
                    callback = { extractorLink ->
                        videos.add(extractorLink.toVideo())
                    },
                )
            } catch (_: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
        return videos
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
}

class ConfigurableMainApiAdapter(val api: MainAPI) : MainApiAdapter(api), ConfigurableAnimeSource {
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "OVERRIDE_PREFS"
            title = "Override Preferences"
            summary = "If you know the key of a preference used in this plugin's source code, you can override it here."
            dialogMessage = """
                Insert key=value pairs. One per line.
            """.trimIndent()

            setOnPreferenceChangeListener { pref, newValue ->
                (newValue as String).split("\n").forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split("=", limit = 2)

                    if (parts.size < 2) {
                        Toast.makeText(screen.context, "Skipping invalid (missing '='): $line", Toast.LENGTH_SHORT).show()
                        return@forEach
                    }

                    val key = parts[0].trim()
                    val value = parts[1].trim()

                    if (key.isEmpty() || value.isEmpty()) {
                        Toast.makeText(screen.context, "Skipping invalid (empty key or value): $line", Toast.LENGTH_SHORT).show()
                        return@forEach
                    }

                    setKey(key, value)
                }

                Toast.makeText(screen.context, "Restart app to apply", Toast.LENGTH_SHORT).show()
                true
            }
        }.also(screen::addPreference)

        PreferenceDivider(screen.context).apply {
            bigText = "ℹ️ Plugin info"
            smallText = """
                Uses WebView? ${api.usesWebView}
                VPN status: ${api.vpnStatus}
                Provider type: ${api.providerType}
                Source plugin: ${api.sourcePlugin}
                Stored credentials: ${api.storedCredentials}
                Supported types: ${api.supportedTypes}
            """.trimIndent()
        }.also(screen::addPreference)

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                key = "DUMMY53829"
                title = "Open Plugin Settings"

                setOnPreferenceClickListener {
                    try {
                        api.openSettings?.invoke(screen.context)
                    } catch (e: Exception) {
                        Log.e("CloudStream", "Failed to open settings for ${api.name}", e)
                    }
                    true
                }
            }
            .also(screen::addPreference)
    }
}

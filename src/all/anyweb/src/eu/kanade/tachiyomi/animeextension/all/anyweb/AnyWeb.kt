package eu.kanade.tachiyomi.animeextension.all.anyweb

import android.app.Application
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.getValue
import kotlin.text.endsWith
import kotlin.text.startsWith

/**
 * Defines the strategy used to locate and extract playable video sources
 * from a given URL or page.
 *
 * One of: [AUTO], [DIRECT_LINK], [EPISODE_PAGE], [EPISODE_INDEX], [SEASON_INDEX]
 */
enum class ParsingStrategy() {
    /** Tries to automatically determine the best parsing strategy based on the URL or content. */
    AUTO,

    /** Treats the URL as a direct link to a video file or playlist. */
    DIRECT_LINK,

    /** Parses a single episode page to extract the actual video URL(s). */
    EPISODE_PAGE,

    /** Parses a page containing multiple episode links, then processes each episode page. */
    EPISODE_INDEX,

    /** Parses a page listing multiple episode indexes, then processes each episode index. */
    SEASON_INDEX,
}

const val EXCLUDE_SELECTOR_DEFAULTS = "nav, footer, header, aside, .comments"

val REGEX = "http://(.*?)\\.aniyomi\\.invalid/(.*)".toRegex()

class AnyWeb : AnimeHttpSource(), ConfigurableAnimeSource {
    override val name = "AnyWeb"
    override val baseUrl = ""
    override val lang = "all"
    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (!query.startsWith("http")) throw UnsupportedOperationException("URL expected")

        val parsingStrategyIndex = filters.find { it is ParsingStrategyFilter }?.state
        val parsingStrategy = ParsingStrategy.values()[parsingStrategyIndex as Int]

        val entry = SAnime.create().apply {
            url = "http://$parsingStrategy.aniyomi.invalid/$query"
        }

        return AnimesPage(listOf(entry), false)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        ParsingStrategyFilter(),
    )

    class ParsingStrategyFilter : AnimeFilter.Select<String>(
        "Parsing strategy",
        ParsingStrategy.values().map { it.toString() }.toTypedArray(),
        0,
    )

    /** Extracts the parsing strategy and URL from anime URL. */
    fun extractFromUrl(url: String): Pair<ParsingStrategy, String> {
        val matchResult = REGEX.find(url) ?: throw UnsupportedOperationException("")
        val (parsingStrategyString, url) = matchResult.destructured
        val parsingStrategyEnum = ParsingStrategy.valueOf(parsingStrategyString)
        return Pair(parsingStrategyEnum, url)
    }

    /**
     * Tries to automatically determine the best [ParsingStrategy] based on the URL or content.
     * Uses [ParsingStrategy.EPISODE_PAGE] as fallback.
     */
    fun guessParsingStrategy(response: Response): ParsingStrategy {
        // mimeType suggest video or playlist
        val isDirectLink = response.headers["Content-Type"]?.let {
            it.startsWith("video/") || it.endsWith("vnd.apple.mpegurl")
        } ?: false

        // is webpage and contains no video elements -> assume it contains links to episode pages
        val document = response.asJsoup()
        val isEpisodeIndex = !isDirectLink && document.selectFirst("video") != null

        return when {
            isDirectLink -> ParsingStrategy.DIRECT_LINK
            isEpisodeIndex -> ParsingStrategy.EPISODE_INDEX
            else -> ParsingStrategy.EPISODE_PAGE
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        var (parsingStrategy, url) = extractFromUrl(anime.url)

        val response = network.client.newCall(GET(url))
            .awaitSuccess()

        if (parsingStrategy == ParsingStrategy.AUTO) {
            parsingStrategy = guessParsingStrategy(response)
        }

        val anime = SAnime.create().apply {
            this.url = url
        }

        when (parsingStrategy) {
            ParsingStrategy.DIRECT_LINK -> {
                anime.apply {
                    title = "Direct"
                    status = SAnime.COMPLETED
                }
            }
            ParsingStrategy.EPISODE_PAGE -> {
                val document = response.asJsoup()
                anime.apply {
                    title = document.title()
                    description = document.selectFirst("meta[name=description]")?.attr("content")
                    status = SAnime.COMPLETED
                    thumbnail_url = document.selectFirst("video")?.attr("poster")
                        ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                }
            }
            else -> {
                val document = response.asJsoup()
                anime.apply {
                    title = document.title()
                    description = document.selectFirst("meta[name=description]")?.attr("content")
                    thumbnail_url = document.selectFirst("video")?.attr("poster")
                        ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                }
            }
        }

        return anime
    }

    private fun episodesFromIndex(url: String): List<SEpisode> {
        val document = network.client.newCall(GET(url, headers)).execute().asJsoup()

        // Ignore links in footer/header
        val excludeSelector = preferences.getString("INDEX_EXCLUDE_SELECTOR", null) ?: EXCLUDE_SELECTOR_DEFAULTS
        document.select(excludeSelector).forEach { it.remove() }

        val maxDepth = preferences.getString("INDEX_DEPTH", null)?.toIntOrNull() ?: 3

        val selectors = mutableListOf<String>()
        val weights = mutableListOf<Int>()

        for (d in 1..maxDepth) {
            val path = List(d) { "> *" }.joinToString(" ")
            val selector = ("$path > a")
                .replace("> * > a", "> a") // fix for depth=1
            selectors += selector
            weights += maxOf(1, maxDepth - (d - 1)) // e.g. depth 1 = 3, depth 2 = 2, etc.
        }

        val candidates = document.select("*").filter { element ->
            selectors.any { sel -> element.select(sel).isNotEmpty() }
        }

        var bestScore = 0
        var bestContainer: Element? = null

        for (container in candidates) {
            var score = 0
            for ((i, sel) in selectors.withIndex()) {
                val count = container.select(sel).size
                score += count * weights[i]
            }

            if (score > bestScore) {
                bestScore = score
                bestContainer = container
            }
        }

        val chapters = bestContainer?.select("a")?.mapIndexed { index, a ->
            SEpisode.create().apply {
                name = a.text().ifBlank { "Untitled" }
                this.url = a.absUrl("href")
                episode_number = index.toFloat()
            }
        } ?: emptyList()

        return chapters.reversed()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        var (parsingStrategy, url) = extractFromUrl(anime.url)

        val response = network.client.newCall(GET(url))
            .awaitSuccess()

        if (parsingStrategy == ParsingStrategy.AUTO) {
            parsingStrategy = guessParsingStrategy(response)
        }

        val episodes: List<SEpisode> = when (parsingStrategy) {
            ParsingStrategy.DIRECT_LINK -> {
                listOf(
                    SEpisode.create().apply {
                        name = "Direct"
                        this.url = anime.url
                    },
                )
            }
            ParsingStrategy.EPISODE_PAGE -> {
                val document = response.asJsoup()
                listOf(
                    SEpisode.create().apply {
                        name = "Webpage"
                        this.url = document.selectFirst("video source")?.attr("src") ?: ""
                    },
                )
            }
            ParsingStrategy.EPISODE_INDEX -> episodesFromIndex(url)
            ParsingStrategy.SEASON_INDEX -> {
                val seasons = episodesFromIndex(url)
                seasons.flatMapIndexed { seasonIndex, season ->
                    episodesFromIndex(season.url).map { episode ->
                        episode.episode_number = seasonIndex + (episode.episode_number + 1) / 100f
                        episode
                    }
                }
            }
            else -> emptyList()
        }

        return episodes
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = episode.url

        val cleanUrl = url.substringBefore("?")
        return when (cleanUrl.substringAfterLast(".")) {
            "m3u8" -> PlaylistUtils(network.client, headers).extractFromHls(
                playlistUrl = url,
                masterHeaders = headers,
                videoHeaders = headers,
            )
            else -> listOf(
                Video(
                    url,
                    "default",
                    url,
                    headers = headers,
                ),
            )
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "INDEX_DEPTH"
            title = "Index Depth"
            dialogTitle = "Set Index Depth"
            summary = """
                Defines how deep the DOM is scanned to auto-detect chapter links (when searching index:<url>).
                Setting this to 1 will detect only links that are placed one after another in the DOM (e.g. in a single list or container).
                Higher values increase scan depth and may help when dealing with chapters divided into sections.
                Setting this value too high might make the extension detect all links on a webpage.
                Recommended: 1–3.
            """.trimIndent()
            setDefaultValue("3")
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "INDEX_EXCLUDE_SELECTOR"
            title = "Index: CSS selector to exclude"
            dialogTitle = "Enter CSS selector"
            setDefaultValue(EXCLUDE_SELECTOR_DEFAULTS)
        }.also(screen::addPreference)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not Used")
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not Used")
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = throw UnsupportedOperationException("Not Used")
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not Used")
}

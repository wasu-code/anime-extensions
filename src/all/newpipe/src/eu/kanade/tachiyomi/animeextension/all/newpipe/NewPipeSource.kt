package eu.kanade.tachiyomi.animeextension.all.newpipe

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Request
import okhttp3.Response
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.StreamingService.LinkType.CHANNEL
import org.schabi.newpipe.extractor.StreamingService.LinkType.PLAYLIST
import org.schabi.newpipe.extractor.StreamingService.LinkType.STREAM
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NewPipeSource(val service: StreamingService) : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name: String = service.serviceInfo.name
    override val baseUrl: String = service.baseUrl
    override val lang = "all"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val commonPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_newpipe", 0x0000)
    }
    private val context by lazy { Injekt.get<Application>() }

    init {
        NewPipeInit.init(network.client)
    }

    var nextPage: Page? = null
    var originalUrl: String? = null
    fun getKiosk(kiosk: String, page: Int): AnimesPage {
        data class PageResult(
            val items: List<InfoItem>,
            val nextPage: Page?,
            val hasNextPage: Boolean,
            val originalUrl: String?,
        )

        val result = if (page > 1) {
            val info = KioskInfo.getMoreItems(service, originalUrl, nextPage)
            PageResult(
                info.items,
                info.nextPage,
                info.hasNextPage(),
                originalUrl = originalUrl,
            )
        } else {
            val kioskExtractor = service.kioskList.getExtractorById(kiosk, null)
            kioskExtractor.fetchPage()
            val info = KioskInfo.getInfo(kioskExtractor)
            PageResult(
                info.relatedItems,
                info.nextPage,
                info.hasNextPage(),
                originalUrl = info.originalUrl,
            )
        }

        if (result.hasNextPage) {
            nextPage = result.nextPage
            originalUrl = result.originalUrl
        }

        val listing = result.items.map { it.toSAnime() }

        return AnimesPage(listing, result.hasNextPage)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val primaryKiosk = preferences.getString("PRIMARY_KIOSK", null) ?: service.kioskList.defaultKioskId
        return getKiosk(primaryKiosk, page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val secondaryKiosk = preferences.getString("SECONDARY_KIOSK", null) ?: service.kioskList.defaultKioskId
        return getKiosk(secondaryKiosk, page)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        // Handle URL provided as query
        if (query.startsWith("http")) {
            val serviceFromQuery = NewPipe.getServiceByUrl(query)
            if (serviceFromQuery == service) {
                val url = query.trimEnd('?', '&') // ensure same url as from listings
                val info = getSingleEntryInfo(service, url)
                return AnimesPage(
                    listOf(
                        SAnime.create().apply {
                            title = info.name
                            thumbnail_url = info.thumbnails.last().url
                            setUrlWithoutDomain(url)
                        },
                    ),
                    false,
                )
            } else {
                throw UnsupportedOperationException("Unsupported URL")
            }
        }

        // Perform search
        val contentFilterState: Int = filters.findInstance<ContentFilter>()?.state ?: 0
        val sortFilterState: Int = filters.findInstance<SortFilter>()?.state ?: 0
        val contentFilter =
            service.searchQHFactory.availableContentFilter.takeIf { it.isNotEmpty() }
                ?.get(contentFilterState).let { listOf(it) }
        val sortFilter = service.searchQHFactory.availableSortFilter.takeIf { it.isNotEmpty() }?.get(sortFilterState)

        val searchInfo = SearchInfo.getInfo(
            service,
            service.searchQHFactory.fromQuery(query, contentFilter, sortFilter),
        )

//        if (page > 1) {
//            service.searchQHFactory.fromUrl(nextPageUrl)
//            val handler = service.searchQHFactory.fromUrl(nextPageUrl)
//            SearchInfo.getInfo(service, handler)
//        }
//        else {
//
//        }

        // TODO pagination        if (page > 1) searchInfo = FeedInfo.getInfo(searchInfo.nextPage.url)         service.searchQHFactory.fromUrl()

        val animes = searchInfo.relatedItems.map { it.toSAnime() }

        return AnimesPage(animes, false) // TODO searchInfo.hasNextPage()
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = baseUrl + anime.url

        return when (service.getLinkTypeByUrl(url)) {
            STREAM -> {
                val info = StreamInfo.getInfo(url)
                info.toSAnime()
            }
            PLAYLIST -> {
                val info = PlaylistInfo.getInfo(url)
                info.toSAnime()
            }
            CHANNEL -> {
                val info = ChannelInfo.getInfo(url)
                info.toSAnime()
            }
            else -> throw UnsupportedOperationException("Unsupported LinkType")
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = baseUrl + anime.url

        return when (service.getLinkTypeByUrl(url)) {
            STREAM -> {
                val info = StreamInfo.getInfo(url)
                listOf(
                    SEpisode.create().apply {
                        name = info.name
                        episode_number = 1f
                        date_upload = parseDate(info.uploadDate?.offsetDateTime().toString())
                        setUrlWithoutDomain(url)
                    },
                )
            }
            PLAYLIST -> {
                val playlist = PlaylistInfo.getInfo(url)
                val items = playlist.relatedItems.toMutableList()

                var nextPage: Page? = playlist.nextPage
                while (nextPage != null) {
                    val i = PlaylistInfo.getMoreItems(service, url, nextPage)
                    items.addAll(i.items)
                    nextPage = i.nextPage
                }

                items.mapIndexed { index, stream ->
                    SEpisode.create().apply {
                        name = stream.name
                        episode_number = (index + 1).toFloat()
                        scanlator = stream.uploaderName
                        date_upload = parseDate(stream.uploadDate?.offsetDateTime().toString())
                        setUrlWithoutDomain(stream.url)
                    }
                }.reversed()
            }
            CHANNEL -> {
                val info = ChannelInfo.getInfo(url)
                val tabs = info.tabs
                // for channels show only playlists (eventually videos if no playlists available)
                val preferredTab = tabs.find { it.url.contains(ChannelTabs.PLAYLISTS) }
                    ?: tabs.find { it.url.contains(ChannelTabs.VIDEOS) }

                preferredTab ?: return emptyList()

                val tabInfo = ChannelTabInfo.getInfo(
                    service,
                    preferredTab,
                )
                val items = tabInfo.relatedItems.toMutableList()

                var nextPage: Page? = tabInfo.nextPage
                while (nextPage != null) {
                    val i = ChannelTabInfo.getMoreItems(service, preferredTab, nextPage)
                    items.addAll(i.items)
                    nextPage = i.nextPage
                }

                items.mapIndexed { index, item ->
                    SEpisode.create().apply {
                        name = "${item.infoType.getIcon()} | ${item.name}"
                        episode_number = (items.size - index).toFloat()
                        setUrlWithoutDomain(item.url)
                    }
                }
            }
            else -> throw UnsupportedOperationException("Unsupported episode type")
        }
    }

    fun handlePlaylistInVideoList(episode: SEpisode): Boolean {
        val url = baseUrl + episode.url
        val isPlaylist = service.getLinkTypeByUrl(url) == PLAYLIST
        if (isPlaylist) {
            val searchIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("query", urlWithSafeEnding(Uri.parse(url)))
                putExtra("filter", NewPipeSource::class.java.`package`?.name)
            }
            context.startActivity(searchIntent)
            return true
        } else {
            return false
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // If playlist in video list, trigger search with intent instead of parsing
        if (handlePlaylistInVideoList(episode)) return emptyList()

        val url = baseUrl + episode.url
        val info = StreamInfo.getInfo(url)

        // info.streamSegments
        Log.d("AAA", info.audioStreams.size.toString())
        Log.d("AAA", info.subtitles.size.toString())
        Log.d("AAA", info.videoStreams.size.toString())
        Log.d("AAA", info.videoOnlyStreams.size.toString())

        // TODO subtitles
//        val subtitleTracks = info.subtitles.map {Track(it.content, it.locale.language + it.format,) }

//        val audioTracks = info.audioStreams.sortedByDescending { it.audioTrackType == AudioTrackType.ORIGINAL }
//            .map { Track(it.content, "${it.audioTrackName} ${it.audioTrackType} ${it.format} ${it.bitrate}") }

        val audioTracks = info.audioStreams
            .groupBy { it.audioLocale?.language ?: "und" } // group by language code
            .map { (_, group) ->
                // Pick the "best" from each group. Example: prefer ORIGINAL, then highest bitrate
                group.maxByOrNull { stream ->
                    val score = if (stream.audioTrackType == AudioTrackType.ORIGINAL) 1_000_000 else 0
                    score + stream.bitrate
                }!!
            }
            .sortedByDescending { it.audioTrackType == AudioTrackType.ORIGINAL } // put Original first
            .map { stream ->
                Track(
                    stream.content,
                    "${stream.audioTrackName} ${stream.audioTrackType}", // Locale(stream.audioLocale?.language ?: "und").isO3Language
                )
            }

        val videos = listOf(info.videoOnlyStreams, info.videoStreams).maxBy { it.size }
        return videos.map { stream: VideoStream ->
            Video(
                stream.content,
                "${stream.quality} (${stream.resolution}) ${stream.format}",
                stream.content,
//                subtitleTracks = subtitleTracks.filterNotNull(),
                audioTracks = audioTracks,
            )
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        mutableListOf<AnimeFilter<*>>().apply {
            service.searchQHFactory.availableContentFilter
                .takeIf { it.isNotEmpty() }
                ?.let { add(ContentFilter(it)) }
            service.searchQHFactory.availableSortFilter
                .takeIf { it.isNotEmpty() }
                ?.let { add(SortFilter(it)) }
        },
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ks = service.kioskList

        if (ks.availableKiosks.isNotEmpty()) {
            ListPreference(screen.context).apply {
                key = "PRIMARY_KIOSK"
                title = "Popular feed"
                entries = ks.availableKiosks.toTypedArray()
                entryValues = ks.availableKiosks.toTypedArray()
                setDefaultValue(ks.defaultKioskId)
                summary = "%s"
            }.also(screen::addPreference)

            ListPreference(screen.context).apply {
                key = "SECONDARY_KIOSK"
                title = "Latest feed"
                entries = ks.availableKiosks.toTypedArray()
                entryValues = ks.availableKiosks.toTypedArray()
                setDefaultValue(ks.defaultKioskId)
                summary = "%s"
            }.also(screen::addPreference)
        }

        EditTextPreference(screen.context).apply {
            summary = "Common settings"
            setEnabled(false)
        }.also(screen::addPreference)

        val switchValue = commonPreferences.getBoolean("HANDLE_SHARE", true)
        preferences.edit().putBoolean("HANDLE_SHARE", switchValue).apply()
        SwitchPreferenceCompat(screen.context).apply {
            key = "HANDLE_SHARE"
            title = "Allow opening from 'Share with...' dialog"
            summary = "Applies to all sources in this extension"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                val intent = Intent().apply {
                    val handlerClass = ShareHandlerToggleActivity::class.java
                    setClassName(handlerClass.`package`!!.name, handlerClass.name)
                    putExtra("extra_enable", newValue as Boolean)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                commonPreferences.edit().putBoolean("HANDLE_SHARE", newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
    }

    // Helper functions
    fun InfoItem.toSAnime(): SAnime = this.toSAnimeRaw().also { it.setUrlWithoutDomain(this.url) }
    fun StreamInfo.toSAnime(): SAnime = this.toSAnimeRaw().also { it.setUrlWithoutDomain(this.url) }
    fun PlaylistInfo.toSAnime(): SAnime = this.toSAnimeRaw().also { it.setUrlWithoutDomain(this.url) }
    fun ChannelInfo.toSAnime(): SAnime = this.toSAnimeRaw().also { it.setUrlWithoutDomain(this.url) }

    // Unused
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = throw UnsupportedOperationException("Not Used")
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not Used")
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not Used")
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not Used")
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException("Not Used")
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not Used")
}

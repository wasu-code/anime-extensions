package eu.kanade.tachiyomi.animeextension.all.newpipe

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.StreamingService.LinkType.CHANNEL
import org.schabi.newpipe.extractor.StreamingService.LinkType.PLAYLIST
import org.schabi.newpipe.extractor.StreamingService.LinkType.STREAM
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

class NewPipeService(val service: StreamingService) : AnimeHttpSource() {

    override val name: String = service.serviceInfo.name
    override val baseUrl: String = service.baseUrl
    override val lang = "all"
    override val supportsLatest = false

    var nextPageUrl: String? = null

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        // Handle valid URL provided as query
        if (query.contains(baseUrl)) {
            return AnimesPage(
                listOf(
                    SAnime.create().apply {
                        title = ""
                        setUrlWithoutDomain(query)
                    },
                ),
                false,
            )
        }

        // Skip incompatible URL provided as query
        if (query.startsWith("http")) throw UnsupportedOperationException("Unsupported URL")

        // Perform search
        NewPipeInit.init(network.client)
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

        val animes = searchInfo.relatedItems.map { item: InfoItem ->
            SAnime.create().apply {
                title = when (item.infoType) {
                    InfoItem.InfoType.CHANNEL -> "👤 | ${item.name}"
                    InfoItem.InfoType.PLAYLIST -> "≔ | ${item.name}"
                    else -> item.name
                }
                thumbnail_url = item.thumbnails.last().url
                setUrlWithoutDomain(item.url)
            }
        }

        return AnimesPage(animes, false) // TODO searchInfo.hasNextPage()
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        NewPipeInit.init(network.client)
        val url = baseUrl + anime.url

        return when (service.getLinkTypeByUrl(url)) {
            STREAM -> {
                val info = StreamInfo.getInfo(url)
                SAnime.create().apply {
                    title = info.name
                    description = info.description.plainText()
                    author = info.uploaderName
                    thumbnail_url = info.thumbnails.last().url
                    setUrlWithoutDomain(url)
                }
            }
            PLAYLIST -> {
                val info = PlaylistInfo.getInfo(url)
                SAnime.create().apply {
                    title = info.name
                    description = info.description.plainText()
                    author = info.uploaderName
                    thumbnail_url = info.thumbnails.last().url
                    setUrlWithoutDomain(url)
                }
            }
            CHANNEL -> {
                val info = ChannelInfo.getInfo(url)
                SAnime.create().apply {
                    title = info.name
                    description = info.description
                    author = info.parentChannelName
                    thumbnail_url = info.avatars.last().url
                    setUrlWithoutDomain(url)
                }
            }
            else -> throw UnsupportedOperationException("Unsupported LinkType")
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        NewPipeInit.init(network.client)
        val url = baseUrl + anime.url

        return when (service.getLinkTypeByUrl(url)) {
            STREAM -> {
                val info = StreamInfo.getInfo(url)
                listOf(
                    SEpisode.create().apply {
                        name = info.name
                        episode_number = 1f
//                        date_upload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                            info.uploadDate?.offsetDateTime()?.toEpochSecond() ?: 0L
//                        } else {
//                            0L
//                        }
                        setUrlWithoutDomain(url)
                    },
                )
            }
            PLAYLIST -> {
                val playlist = PlaylistInfo.getInfo(url)
                playlist.relatedItems.mapIndexed { index, stream ->
                    SEpisode.create().apply {
                        name = stream.name
                        episode_number = (index + 1).toFloat()
//                        date_upload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                            stream.uploadDate?.offsetDateTime()?.toEpochSecond() ?: 0L
//                        } else {
//                            0L
//                        }
                        setUrlWithoutDomain(stream.url)
                    }
                }.reversed()
                // TODO if has playlist.nextPage
            }
            CHANNEL -> {
                val info = ChannelInfo.getInfo(url)
                val feed = FeedInfo.getInfo(info.feedUrl)
                feed.relatedItems.mapIndexed { index, stream ->
                    SEpisode.create().apply {
                        name = stream.name
                        episode_number = (index + 1).toFloat()
                        setUrlWithoutDomain(stream.url)
                    }
                }
                // TODO make amount of feed pages configurable
                // feed.nextPage.url
            }
            else -> throw UnsupportedOperationException("Unsupported episode type")
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        NewPipeInit.init(network.client)
        val url = baseUrl + episode.url

        val info = StreamInfo.getInfo(url)

        // info.streamSegments
        // info.videoOnlyStreams
        Log.d("AAA", info.audioStreams.size.toString())
        Log.d("AAA", info.subtitles.size.toString())
        Log.d("AAA", info.videoStreams.size.toString())
        Log.d("AAA", info.videoOnlyStreams.size.toString())

        val subtitleTracks = info.subtitles.mapNotNull {
            Track(
                it.content,
                it.locale.language,
            ).takeUnless { it2 -> !it.isUrl }
        }
        val audioTracks = info.audioStreams.sortedByDescending { it.audioTrackType == AudioTrackType.ORIGINAL }
            .map { Track(it.content, "${it.audioTrackName} ${it.audioTrackType} ${it.format} ${it.bitrate}") }

        return info.videoOnlyStreams.map { stream: VideoStream ->
            Log.d("AAA", stream.quality.toString() + info.audioStreams.filter { it.quality == stream.quality }.size.toString())
            Video(
                stream.content,
                "${stream.quality} (${stream.resolution}) ${stream.format}",
                stream.content,
//                subtitleTracks = subtitleTracks.filterNotNull(),
                audioTracks = audioTracks.subList(0, 1), // currently only first to speedup loading
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

    //

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
}

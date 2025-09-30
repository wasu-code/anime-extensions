package eu.kanade.tachiyomi.animeextension.all.newpipe

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

class NewPipeService(val service: StreamingService) : AnimeHttpSource() {

    override val name: String = service.serviceInfo.name
    override val baseUrl: String = service.baseUrl
    override val lang = "all"
    override val supportsLatest = false

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        NewPipeInit.init(network.client)

//        if direct url
//        val info = try {
//            PlaylistInfo.getInfo(url)
//        } catch (_: ParsingException) {
//            StreamInfo.getInfo(url)
//        } as CommonInfo
//
//        val anime = SAnime.create().apply {
//            title = info.name
//            description = info.description.content
//            author = info.uploaderName
//            thumbnail_url = info.thumbnails.first().url
//            setUrlWithoutDomain(url)
//        }
//        return AnimesPage(listOf(anime), false)

        Log.d("AAA", service.searchQHFactory.availableContentFilter.joinToString())
        Log.d("AAA", service.searchQHFactory.availableSortFilter.joinToString())

        val state: Int = filters.findInstance<ContentFilter>()?.state ?: 0
        Log.d("AAA", state.toString())
        val contentFilter = service.searchQHFactory.availableContentFilter[state]
        Log.d("AAA", contentFilter)

        val searchInfo = SearchInfo.getInfo(
            service,
            service.searchQHFactory.fromQuery(query, listOf(contentFilter), ""), // TODO
        )

        // TODO pagination        if (page > 1) searchInfo = FeedInfo.getInfo(searchInfo.nextPage.url)

        val animes = searchInfo.relatedItems.map { item: InfoItem ->
            SAnime.create().apply {
                title = when (item.infoType) {
                    InfoItem.InfoType.CHANNEL -> "👤 | ${item.name}"
                    InfoItem.InfoType.PLAYLIST -> "≔ | ${item.name}"
                    else -> item.name
                }
                thumbnail_url = item.thumbnails.first().url
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
                    thumbnail_url = info.thumbnails.first().url
                    setUrlWithoutDomain(url)
                }
            }
            PLAYLIST -> {
                val info = PlaylistInfo.getInfo(url)
                SAnime.create().apply {
                    title = info.name
                    description = info.description.plainText()
                    author = info.uploaderName
                    thumbnail_url = info.thumbnails.first().url
                    setUrlWithoutDomain(url)
                }
            }
            CHANNEL -> {
                val info = ChannelInfo.getInfo(url)
                SAnime.create().apply {
                    title = info.name
                    description = info.description
                    author = info.parentChannelName
                    thumbnail_url = info.avatars.first().url
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
                        setUrlWithoutDomain(stream.url)
                    }
                }.reversed()
            }
            CHANNEL -> {
                val info = ChannelInfo.getInfo(url)
                info.feedUrl
                // TODO
                emptyList()
            }
            else -> throw UnsupportedOperationException("Unsupported episode type")
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        NewPipeInit.init(network.client)
        val url = baseUrl + episode.url

        val info = StreamInfo.getInfo(url)
        return info.videoStreams.map { stream ->
            Video(
                stream.content,
                quality = stream.quality,
                videoUrl = stream.content,
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

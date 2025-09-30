package eu.kanade.tachiyomi.animeextension.all.newpipe

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

class NewPipeService(service: StreamingService) : AnimeHttpSource() {

    override val name: String = service.serviceInfo.name
    override val baseUrl: String = service.baseUrl
    override val lang = "all"
    override val supportsLatest = false

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

//    override suspend fun getSearchAnime(
//        page: Int,
//        query: String,
//        filters: AnimeFilterList,
//    ): AnimesPage {
//        val searchResults = try {
//            val searchInfo = SearchInfo.getInfo(
//                service,
//                SearchQueryHandler(
//                    "https://www.youtube.com/results?search_query=$query",
//                    "https://www.youtube.com/results?search_query=$query",
//                    query,
//                    emptyList(),
//                    "",
//                ),
//            )
//            searchInfo.relatedItems
//        } catch (e: Exception) {
//            e.printStackTrace()
//            emptyList()
//        }
//
//        val animes = searchResults.map { item ->
//            SAnime.create().apply {
//                title = item.name
//                thumbnail_url = item.thumbnails.first().url
//                setUrlWithoutDomain(item.url)
//            }
//        }
//
//        return AnimesPage(animes, false)
//    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = GET(query)

    override fun searchAnimeParse(response: Response): AnimesPage {
        NewPipeInit.init(network.client)
        val url = response.request.url.toString()

        val info = try {
            PlaylistInfo.getInfo(url)
        } catch (e: Exception) {
            StreamInfo.getInfo(url)
        } as StreamInfo

        val anime = SAnime.create().apply {
            title = info.name
            description = info.description.content
            author = info.uploaderName
            artist = info.uploaderName
            thumbnail_url = info.thumbnails.first().url
            setUrlWithoutDomain(url)
        }
        return AnimesPage(listOf(anime), false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val url = response.request.url.toString()

        val info = try {
            PlaylistInfo.getInfo(url)
        } catch (e: Exception) {
            StreamInfo.getInfo(url)
        } as StreamInfo

        return SAnime.create().apply {
            title = info.name
            description = info.description.content
            author = info.uploaderName
            artist = info.uploaderName
            thumbnail_url = info.thumbnails.first().url
            setUrlWithoutDomain(url)
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        return try {
            // Playlist case
            val playlist = PlaylistInfo.getInfo(url)
            playlist.relatedItems.mapIndexed { index, stream ->
                SEpisode.create().apply {
                    name = stream.name
                    episode_number = (index + 1).toFloat()
                    setUrlWithoutDomain(stream.url)
                }
            }
        } catch (e: Exception) {
            // Single video fallback
            val info = StreamInfo.getInfo(url)
            listOf(
                SEpisode.create().apply {
                    name = info.name
                    episode_number = 1f
                    setUrlWithoutDomain(url)
                },
            )
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val info = StreamInfo.getInfo(url)
        return info.videoStreams.map { stream ->
            Video(
                stream.content,
                quality = stream.quality,
                videoUrl = stream.content,
            )
        }
    }

    //

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
}

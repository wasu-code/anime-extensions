package eu.kanade.tachiyomi.animeextension.all.newpipe

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService

class NewPipeFactory : AnimeSourceFactory {
    val services: List<StreamingService> = ServiceList.all()

    /** Music streaming services */
    val blacklist: List<StreamingService> = listOf(
        ServiceList.Bandcamp,
        ServiceList.SoundCloud,
    )

    override fun createSources(): List<AnimeSource> = services
        .filterNot { it in blacklist }
        .map { service -> NewPipeSource(service) }
}

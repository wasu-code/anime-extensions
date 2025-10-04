package eu.kanade.tachiyomi.animeextension.all.newpipe

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService

class NewPipeFactory : AnimeSourceFactory {
    val services: List<StreamingService> = ServiceList.all()

    override fun createSources(): List<AnimeSource> = services.map { service -> NewPipeSource(service) }
}

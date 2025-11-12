package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.app.Application
import android.util.Log
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Suppress("unused")
class CloudStreamFactory : AnimeSourceFactory {
    private val context = Injekt.get<Application>()

    override fun createSources(): List<AnimeSource> {
        val apis = PluginLoader.loadAllPlugins(context)
        Log.d("CloudStream", "Loaded ${apis.size} sources")
        // TODO: detect when should be configurable (if has openSettings method)
        return (apis.map { api -> ConfigurableMainApiAdapter(api) } + CloudStreamSettings()) as List<AnimeSource>
    }
}

package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.TvType
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URL

fun Context.getActivity(): android.app.Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

class CloudStreamSettings() : AnimeSource, ConfigurableAnimeSource {
    override val id: Long = 133745
    val lang: String = "all"
    override val name: String = "!➲ CloudStream Settings"
    override fun toString(): String = name

    private val context = Injekt.get<Application>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    @SuppressLint("ApplySharedPref")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val scope = CoroutineScope(Dispatchers.IO)
        val fm = FilterManager(preferences)

        val reposPref = EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Plugin repositories"
            summary = "${preferences.getString(key, "")?.lines()?.filter { it.isNotBlank() }?.size ?: 0} repo(s) added"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
                summary = "${newValue.lines().filter { it.isNotBlank() }.size} repo(s) added"
                screen.context.getActivity()?.recreate()
                true
            }
        }

        val repoFilterPref = fm.makeRepoFilter(screen.context)
        val langFilterPref = fm.makeLangFilter(screen.context)
        val typeFilterPref = fm.makeTypeFilter(screen.context)
        val statusFilterPref = fm.makeStatusFilter(screen.context)
        val installedOnlyFilterPref = fm.makeInstalledOnlyFilter(screen.context)

        screen.addPreference(reposPref)
        screen.addPreference(PreferenceDivider(smallText = "Filters", context = screen.context))
        screen.addPreference(repoFilterPref)
        screen.addPreference(langFilterPref)
        screen.addPreference(typeFilterPref)
        screen.addPreference(statusFilterPref)
        screen.addPreference(installedOnlyFilterPref)

        ButtonPreference(screen.context).apply {
            key = "PLUGINS_REFRESH"
            title = "✅ Apply filters"
            onClick = { screen.context.getActivity()?.recreate() }
        }.also(screen::addPreference)

        ButtonPreference(screen.context).apply {
            key = "APP_RESTART"
            title = "🔄 Restart app"
            summary = "Restart app to (un)load (un)installed plugins"
            onClick = { restartApp(context) }
        }.also(screen::addPreference)

        PreferenceDivider(
            screen.context,
            smallText = "Manage plugins",
        ).also(screen::addPreference)

        ConfirmActionPreference(screen.context).apply {
            key = "PLUGINS_PURGE"
            title = "Purge all plugin files"
            dialogMessage = "This will remove all installed CloudStream plugins"
            onConfirm = { screen.context.getActivity()?.recreate() }
            summary = "${PluginManager.getPluginCount()} plugin(s) installed, ${APIHolder.allProviders.size} provider(s) loaded"
            onConfirm = {
                scope.launch {
                    setEnabled(false)
                    val success = PluginManager.deleteAllPluginFiles()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(screen.context, "All plugin files deleted? $success", Toast.LENGTH_SHORT).show()
                        setEnabled(true)
                        preferences.edit().putStringSet("EXTENSIONS", emptySet()).commit()
                        screen.context.getActivity()?.recreate()
                    }
                }
            }
        }.also(screen::addPreference)

        loadPluginList(screen, fm, scope)
    }

    private fun loadPluginList(screen: PreferenceScreen, fm: FilterManager, scope: CoroutineScope) {
        scope.launch {
            val plugins = fm.getFilteredPlugins()
            withContext(Dispatchers.Main) {
                // Add divider and plugin switches
                if (plugins.isNotEmpty()) {
                    PreferenceDivider(smallText = "Showing ${plugins.size} plugins", context = screen.context).also(screen::addPreference)
                    plugins.forEach { plugin ->
                        screen.addPreference(
                            createPluginSwitch(
                                screen.context,
                                context,
                                plugin,
                                scope,
                                fm.isPluginInstalled(plugin.url),
                            ),
                        )
                    }
                } else {
                    PreferenceDivider(smallText = "No plugins match current filters", context = screen.context).also(screen::addPreference)
                }
            }
        }
    }

    private fun createPluginSwitch(context: Context, appContext: Application, plugin: SitePlugin, scope: CoroutineScope, isInstalled: Boolean = false): SwitchPreferenceCompat {
        return SwitchPreferenceCompat(context).apply {
            title = "${plugin.name} (${plugin.language?.uppercase() ?: "ALL"})"
            summary = """
            ${plugin.description}
            ${plugin.tvTypes?.joinToString(" // ")}
            from: ${URL(plugin.repositoryUrl).path.trimStart('/')}
            version: ${plugin.version}
            status: ${arrayOf("Down", "Ok", "Slow", "Beta")[plugin.status]}
            """.trimIndent()

            val installed = isInstalled
            setDefaultValue(installed)
            setEnabled(installed || plugin.status > 0)

            setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                setEnabled(false)
                scope.launch {
                    try {
                        if (enable) {
                            PluginManager.downloadPluginToFile(plugin.url)?.let {
                                PluginLoader.loadPlugin(appContext, it)
                            }
                        } else {
                            PluginManager.deletePluginFile(plugin.url)
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Plugin load failed", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        withContext(Dispatchers.Main) { setEnabled(true) }
                    }
                }
                true
            }
        }
    }

    /** Restart host application to force it to reload all plugins */
    fun restartApp(context: Application) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0) // kill old process after scheduling restart
        }
    }

    // Unused
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = throw UnsupportedOperationException("Not Used")
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> = throw UnsupportedOperationException("Not Used")
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> = throw UnsupportedOperationException("Not Used")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = throw UnsupportedOperationException("Not Used")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = throw UnsupportedOperationException("Not Used")
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException("Not Used")
}

@Suppress("UNCHECKED_CAST")
@SuppressLint("ApplySharedPref")
class FilterManager(private val prefs: SharedPreferences) {
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var cachedPlugins: List<SitePlugin> = emptyList()

    @Volatile private var installedPlugins: Array<File> = emptyArray()

    fun getRepos(): Set<String> =
        prefs.getString("REPOS", "")
            ?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun getFilteredRepos(): Set<String> =
        prefs.getStringSet("FILTER_REPO", getRepos()) ?: emptySet()

    suspend fun getPlugins(): List<SitePlugin> {
        if (cachedPlugins.isEmpty()) cachedPlugins = RepositoryManager.getAllPlugins(getRepos())
        return cachedPlugins
    }

    fun getInstalledPlugins(): Array<File> {
        if (installedPlugins.isEmpty()) installedPlugins = PluginManager.getInstalledPlugins() ?: emptyArray()
        return installedPlugins
    }

    fun isPluginInstalled(pluginUrl: String) = PluginManager.isPluginInstalled(pluginUrl)

    suspend fun getFilteredPlugins(): List<SitePlugin> {
        if (cachedPlugins.isEmpty()) cachedPlugins = RepositoryManager.getAllPlugins(getFilteredRepos())
        val selectedLangs = prefs.getStringSet("FILTER_LANGUAGE", emptySet()) ?: emptySet()
        val selectedTypes = prefs.getStringSet("FILTER_TVTYPE", TvType.values().map { it.name }.toSet()) ?: emptySet()
        val selectedStatus = prefs.getStringSet("FILTER_STATUS", setOf("0", "1", "2", "3")) ?: emptySet()
        val installedOnly = prefs.getBoolean("FILTER_INSTALLED_ONLY", false)

        return cachedPlugins.filter { plugin ->
            (!installedOnly || isPluginInstalled(plugin.url)) &&
                (plugin.tvTypes.isNullOrEmpty() || plugin.tvTypes.any { it in selectedTypes }) &&
                (plugin.status.toString() in selectedStatus) &&
                (selectedLangs.isEmpty() || plugin.language.isNullOrBlank() || plugin.language in selectedLangs)
        }
    }

    fun makeRepoFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_REPO"
        title = "Filter by repository"
        val repos = getRepos()
        entries = repos.toTypedArray()
        entryValues = repos.toTypedArray()
        values = prefs.getStringSet(key, emptySet()) ?: emptySet()
        summary = "${values.size} repo(s) selected"
        setDefaultValue(repos)
        setOnPreferenceChangeListener { pref, newValue ->
            summary = "${(newValue as Set<String>).size} repo(s) selected"
            true
        }
    }

    fun makeLangFilter(context: Context): MultiSelectListPreference {
        val pref = MultiSelectListPreference(context)
        pref.key = "FILTER_LANGUAGE"
        pref.title = "Filter by language"
        pref.summary = "Loading..."
        pref.setEnabled(false)
        pref.setDefaultValue(emptySet<String>())

        // Load available languages asynchronously
        scope.launch {
            val plugins = getPlugins()
            val langs = plugins
                .mapNotNull { it.language }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            withContext(Dispatchers.Main) {
                pref.entries = langs.toTypedArray()
                pref.entryValues = langs.toTypedArray()
                pref.setEnabled(langs.isNotEmpty())

                val selected = prefs.getStringSet(pref.key, emptySet()) ?: emptySet()
                pref.summary = "${selected.size}/${langs.size} selected"
            }
        }

        pref.setOnPreferenceChangeListener { p, newValue ->
            p.summary = "${(newValue as Set<String>).size}/${(p as MultiSelectListPreference).entries.size} selected"
            true
        }

        return pref
    }

    fun makeTypeFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_TVTYPE"
        title = "Filter by type"
        entries = TvType.values().map { it.name }.toTypedArray()
        entryValues = entries
        setDefaultValue(entries.toSet())
        summary = "${(prefs.getStringSet(key, emptySet())?.size) ?: 0}/${entries.size} selected"
    }

    fun makeStatusFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_STATUS"
        title = "Filter by status"
        entries = arrayOf("Down", "Ok", "Slow", "Beta")
        entryValues = arrayOf("0", "1", "2", "3")
        setDefaultValue(setOf("0", "1", "2", "3"))
        summary = "${(prefs.getStringSet(key, emptySet())?.size) ?: 0}/${entries.size} selected"
    }

    fun makeInstalledOnlyFilter(context: Context) = CheckBoxPreference(context).apply {
        key = "FILTER_INSTALLED_ONLY"
        title = "Show only installed plugins"
        setDefaultValue(false)
    }
}

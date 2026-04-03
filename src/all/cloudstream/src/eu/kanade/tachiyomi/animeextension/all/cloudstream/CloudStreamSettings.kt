package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceCategory
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
object PluginCache {
    private val PLUGIN_MAP = mutableMapOf<String, List<SitePlugin>>()

    fun get(repoUrl: String): List<SitePlugin>? = PLUGIN_MAP[repoUrl]
    fun put(repoUrl: String, plugins: List<SitePlugin>) { PLUGIN_MAP[repoUrl] = plugins }
}

class CloudStreamSettings() : AnimeSource, ConfigurableAnimeSource {
    override val id: Long = 133745
    val lang: String = "all"
    override val name: String = "! ⭐ CloudStream Settings ⭐ !"
    override fun toString(): String = name

    private val context = Injekt.get<Application>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    @SuppressLint("ApplySharedPref")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val scope = CoroutineScope(Dispatchers.IO)
        val fm by lazy { FilterManager.getInstance(preferences) }

        // Check host app compatibility with this extension
        val clazz = com.google.gson.stream.JsonReader::class.java
        val methodName = "setStrictness" // available only in gson v2.11+
        val hasMethod = clazz.methods.any { it.name == methodName }
        val hostAppName = context.applicationInfo.loadLabel(context.packageManager)
        if (!hasMethod) {
            PreferenceDivider(
                screen.context,
            ).apply {
                bigText = "⚠️"
                smallText = """
                    Your host app ($hostAppName) uses an outdated version of the gson library (older than v2.11.0).
                    Some extensions may not work properly (and throw NoSuchMethodError for setStrictness).
                """.trimIndent()
            }.also(screen::addPreference)
        }

        EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Plugin repositories"
            summary = "${preferences.getString(key, "")?.lines()?.filter { it.isNotBlank() }?.size ?: 0} repo(s) added"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
                summary = "${newValue.lines().filter { it.isNotBlank() }.size} repo(s) added"
                fm.applyFilters(screen.context)
                true
            }
        }.also(screen::addPreference)

        ButtonPreference(screen.context).apply {
            key = "BTN_ADD_ALL"
            title = "Add all well known repos"
            summary = "Add all well known plugin repositories."
            onClick = {
                scope.launch {
                    val userRepos = fm.getRepos()
                    val wellKnownRepos = RepositoryManager.getWellKnownRepos()
                    val allRepos = (userRepos + wellKnownRepos).toSet()
                    preferences.edit().putString("REPOS", allRepos.joinToString("\n")).commit()
                    withContext(Dispatchers.Main) {
                        fm.applyFilters(screen.context)
                    }
                }
            }
        }.also(screen::addPreference)

        val filters = PreferenceCategory(screen.context).apply {
            title = "Filters"
            summary = "Set filters to limit plugins shown"
        }.also(screen::addPreference)

        fm.makeRepoFilter(screen.context).also(filters::addPreference)
        fm.makeLangFilter(screen.context).also(filters::addPreference)
        fm.makeTypeFilter(screen.context).also(filters::addPreference)
        fm.makeStatusFilter(screen.context).also(filters::addPreference)
        fm.makeInstalledOnlyFilter(screen.context).also(filters::addPreference)

        val extensionList = PreferenceCategory(screen.context).apply {
            title = "Manage plugins"
            summary = "Install or uninstall plugins"
        }.also(screen::addPreference)

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
                        fm.applyFilters(screen.context)
                    }
                }
            }
        }.also(extensionList::addPreference)

        loadPluginList(screen, fm, scope)
    }

    private fun loadPluginList(screen: PreferenceScreen, fm: FilterManager, scope: CoroutineScope) {
        scope.launch {
            val loadingPref = PreferenceDivider(
                context = screen.context,
            ).apply {
                smallText = "Loading plugins..."
            }.also(screen::addPreference)

            val plugins = fm.getFilteredPlugins()
            withContext(Dispatchers.Main) {
                // Add divider and plugin switches
                if (plugins.isNotEmpty()) {
                    loadingPref.smallText = "Showing ${plugins.size} plugins"
                    plugins.forEach { plugin ->
                        screen.addPreference(
                            createPluginSwitch(
                                screen.context,
                                plugin,
                                scope,
                                fm.isPluginInstalled(plugin.url),
                            ),
                        )
                    }
                } else {
                    loadingPref.smallText = "No plugins match current filters"
                }
            }
        }
    }

    private fun createPluginSwitch(context: Context, plugin: SitePlugin, scope: CoroutineScope, isInstalled: Boolean = false): SwitchPreferenceCompat {
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
                            PluginManager.downloadPluginToFile(plugin.url)
                        } else {
                            PluginManager.deletePluginFile(plugin.url)
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Plugin load failed", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        withContext(Dispatchers.Main) { setEnabled(true) }
                        reloadPlugins()
                    }
                }
                true
            }
        }
    }

    /**
     * Reload plugins from disk so they appear in the host app.
     */
    fun reloadPlugins() {
        val applicationId = context.packageName // theoretically should be BuildConfig.APPLICATION_ID of host app
        val extensionPackageName = this::class.java.`package`?.name
        Intent("$applicationId.ACTION_EXTENSION_REPLACED").apply {
            data = Uri.parse("package:$extensionPackageName")
            `package` = context.packageName
            context.sendBroadcast(this)
        }
    }

    // Unused
    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getAnimeDetails"))
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = throw UnsupportedOperationException("Not Used")

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getEpisodeList"))
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> = throw UnsupportedOperationException("Not Used")

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getVideoList"))
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> = throw UnsupportedOperationException("Not Used")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = throw UnsupportedOperationException("Not Used")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = throw UnsupportedOperationException("Not Used")
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException("Not Used")
}

@Suppress("UNCHECKED_CAST")
@SuppressLint("ApplySharedPref")
class FilterManager(private val prefs: SharedPreferences) {
    companion object {
        @Volatile private var instance: FilterManager? = null
        fun getInstance(prefs: SharedPreferences): FilterManager = instance ?: synchronized(this) {
            instance ?: FilterManager(prefs).also { instance = it }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private val repoMutexMap = mutableMapOf<String, Mutex>()

    /**
     * Return list of plugins for given repository URL.
     * Will used cached list if available or fetch and add them to cache.
     */
    suspend fun getPluginsForRepo(repoUrl: String): List<SitePlugin> {
        // Return cached if available
        PluginCache.get(repoUrl)?.let { return it }

        val mutex = repoMutexMap.getOrPut(repoUrl) { Mutex() }
        return mutex.withLock {
            // Double-check cache inside lock
            PluginCache.get(repoUrl)?.let { return it }

            RepositoryManager.getRepoPlugins(repoUrl).also { PluginCache.put(repoUrl, it) }
        }
    }

    /**
     * Return set of repository URLs added in settings. This includes disabled repositories.
     */
    fun getRepos(): Set<String> =
        prefs.getString("REPOS", "")
            ?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /**
     * Return set of repository URLs enabled in settings.
     */
    fun getFilteredRepos(): Set<String> =
        prefs.getStringSet("FILTER_REPO", getRepos()) ?: emptySet()

    /**
     * Return list of all plugins across all repositories enabled in [makeRepoFilter].
     */
    suspend fun getPlugins(): List<SitePlugin> = getFilteredRepos().flatMap { getPluginsForRepo(it) }

    /**
     * Return list of plugins matching current filter settings.
     */
    suspend fun getFilteredPlugins(): List<SitePlugin> {
        val selectedLangs = prefs.getStringSet("FILTER_LANGUAGE", emptySet()) ?: emptySet()
        val selectedTypes = prefs.getStringSet("FILTER_TVTYPE", TvType.values().map { it.name }.toSet()) ?: emptySet()
        val selectedStatus = prefs.getStringSet("FILTER_STATUS", setOf("0", "1", "2", "3")) ?: emptySet()
        val installedOnly = prefs.getBoolean("FILTER_INSTALLED_ONLY", false)

        return getPlugins().filter { plugin ->
            (!installedOnly || isPluginInstalled(plugin.url)) &&
                (plugin.tvTypes.isNullOrEmpty() || plugin.tvTypes.any { it in selectedTypes }) &&
                (plugin.status.toString() in selectedStatus) &&
                (selectedLangs.isEmpty() || plugin.language.isNullOrBlank() || plugin.language in selectedLangs)
        }
    }

    fun isPluginInstalled(pluginUrl: String) = PluginManager.isPluginInstalled(pluginUrl)

    fun applyFilters(context: Context) { context.getActivity()?.recreate() }

    // Functions for creating preferences
    fun makeRepoFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_REPO"
        title = "Repository"

        val repos = getRepos()
        entries = repos.map {
            URL(it)
                .path
                .removePrefix("/")
        }.toTypedArray()
        entryValues = repos.toTypedArray()

        val storedValues = prefs.getStringSet(key, emptySet()) ?: emptySet()
        val validValues = storedValues.intersect(repos.toSet())
        values = validValues

        summary = "${values.size} repo(s) selected"
        setDefaultValue(repos)
        setOnPreferenceChangeListener { pref, newValue ->
            val selected = (newValue as Set<String>).intersect(repos.toSet())
            summary = "${selected.size} repo(s) selected"
            prefs.edit().putStringSet(key, selected).commit()
            (pref as MultiSelectListPreference).values = selected
            applyFilters(context)
            false // skip default saving (updated values already saved manually)
        }
    }

    fun makeLangFilter(context: Context): MultiSelectListPreference {
        val pref = MultiSelectListPreference(context)
        pref.key = "FILTER_LANGUAGE"
        pref.title = "Language"
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
            applyFilters(context)
            true
        }

        return pref
    }

    fun makeTypeFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_TVTYPE"
        title = "Type"
        entries = TvType.values().map { it.name }.toTypedArray()
        entryValues = entries
        setDefaultValue(entries.toSet())
        summary = "${(prefs.getStringSet(key, emptySet())?.size) ?: 0}/${entries.size} selected"
        setOnPreferenceChangeListener { _, _ ->
            applyFilters(context)
            true
        }
    }

    fun makeStatusFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_STATUS"
        title = "Status"
        entries = arrayOf("Down", "Ok", "Slow", "Beta")
        entryValues = arrayOf("0", "1", "2", "3")
        setDefaultValue(setOf("0", "1", "2", "3"))
        summary = "${(prefs.getStringSet(key, emptySet())?.size) ?: 0}/${entries.size} selected"
        setOnPreferenceChangeListener { _, _ ->
            applyFilters(context)
            true
        }
    }

    fun makeInstalledOnlyFilter(context: Context) = CheckBoxPreference(context).apply {
        key = "FILTER_INSTALLED_ONLY"
        title = "Only installed"
        setDefaultValue(false)
        setOnPreferenceChangeListener { _, _ ->
            applyFilters(context)
            true
        }
    }
}

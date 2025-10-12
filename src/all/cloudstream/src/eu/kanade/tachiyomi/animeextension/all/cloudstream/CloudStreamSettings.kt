package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.TvType
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URL

class CloudStreamSettings() : AnimeHttpSource(), ConfigurableAnimeSource {
    override val lang: String = "none"
    override val name: String = "! CloudStream Settings"

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
                true
            }
        }

        val repoFilterPref = fm.makeRepoFilter(screen.context)
        val langFilterPref = fm.makeLangFilter(screen.context)
        val typeFilterPref = fm.makeTypeFilter(screen.context)
        val statusFilterPref = fm.makeStatusFilter(screen.context)

        screen.addPreference(reposPref)
        screen.addPreference(PreferenceDivider(smallText = "Filters", context = screen.context))
        screen.addPreference(repoFilterPref)
        screen.addPreference(langFilterPref)
        screen.addPreference(typeFilterPref)
        screen.addPreference(statusFilterPref)

        screen.addPreference(PreferenceDivider(bigText = "ℹ️ To apply filters, reenter settings screen", context = screen.context))

        screen.addPreference(PreferenceDivider(smallText = "Manage plugins", context = screen.context))

        val purgePref = SwitchPreferenceCompat(screen.context).apply {
            key = "PLUGINS_PURGE"
            title = "Purge all plugin files"
            setDefaultValue(false)
            summary = "${PluginManager.getPluginCount()} plugin(s) installed, ${APIHolder.allProviders.size} provider(s) loaded"
            setOnPreferenceClickListener { pref ->
                val switchPref = pref as SwitchPreferenceCompat
                switchPref.isChecked = false

                scope.launch {
                    setEnabled(false)
                    val success = PluginManager.deleteAllPluginFiles()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(screen.context, "All plugin files deleted? $success", Toast.LENGTH_SHORT).show()
                        setEnabled(true)
                        preferences.edit().putStringSet("EXTENSIONS", emptySet()).commit()
                    }
                }
                false
            }
        }
        screen.addPreference(purgePref)

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
                        screen.addPreference(createPluginSwitch(screen.context, context, plugin, scope))
                    }
                } else {
                    PreferenceDivider(smallText = "No plugins match current filters", context = screen.context).also(screen::addPreference)
                }
            }
        }
    }

    private fun createPluginSwitch(context: Context, appContext: Application, plugin: SitePlugin, scope: CoroutineScope): SwitchPreferenceCompat {
        return SwitchPreferenceCompat(context).apply {
            title = "${plugin.name} (${plugin.language?.uppercase() ?: "ALL"})"
            summary = """
            ${plugin.description}
            ${plugin.tvTypes?.joinToString(" // ")}
            from: ${URL(plugin.repositoryUrl).path.trimStart('/')}
            version: ${plugin.version}
            status: ${arrayOf("Down", "Ok", "Slow", "Beta")[plugin.status]}
            """.trimIndent()

            val installed = PluginManager.isPluginInstalled(plugin.url)
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
    override val baseUrl: String = ""
    override val supportsLatest: Boolean = false
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
}

class PreferenceDivider(context: Context, bigText: String? = null, smallText: String? = null) : EditTextPreference(context) {
    init {
        title = bigText
        summary = smallText
        setEnabled(false)
    }
}

@Suppress("UNCHECKED_CAST")
@SuppressLint("ApplySharedPref")
class FilterManager(private val prefs: SharedPreferences) {
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var cachedPlugins: List<SitePlugin> = emptyList()

    fun getRepos(): Set<String> =
        prefs.getString("REPOS", "")
            ?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun getFilteredRepos(): Set<String> =
        prefs.getStringSet("FILTER_REPO", getRepos()) ?: emptySet()

    suspend fun getPlugins(): List<SitePlugin> {
        if (cachedPlugins.isEmpty()) cachedPlugins = RepositoryManager.getAllPlugins(getRepos())
        return cachedPlugins
    }

    suspend fun getFilteredPlugins(): List<SitePlugin> {
        if (cachedPlugins.isEmpty()) cachedPlugins = RepositoryManager.getAllPlugins(getFilteredRepos())
        val selectedLangs = prefs.getStringSet("FILTER_LANGUAGE", emptySet()) ?: emptySet()
        val selectedTypes = prefs.getStringSet("FILTER_TVTYPE", TvType.values().map { it.name }.toSet()) ?: emptySet()
        val selectedStatus = prefs.getStringSet("FILTER_STATUS", setOf("0", "1", "2", "3")) ?: emptySet()

        return cachedPlugins.filter { plugin ->
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
            prefs.edit().putStringSet(pref.key, newValue as Set<String>).commit()
            summary = "${newValue.size} repo(s) selected"
            true
        }
    }

    fun makeLangFilter(context: Context): MultiSelectListPreference {
        val pref = MultiSelectListPreference(context)
        pref.key = "FILTER_LANGUAGE"
        pref.title = "Filter by language"
        pref.summary = "Loading..."
        pref.setEnabled(false)

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
                pref.setDefaultValue(langs.toSet())
                pref.setEnabled(langs.isNotEmpty())

                val selected = prefs.getStringSet(pref.key, emptySet()) ?: emptySet()
                pref.summary = "${selected.size}/${langs.size} selected"
            }
        }

        pref.setOnPreferenceChangeListener { p, newValue ->
            prefs.edit().putStringSet(p.key, newValue as Set<String>).commit()
            p.summary = "${newValue.size}/${(p as MultiSelectListPreference).entries.size} selected"
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
        setOnPreferenceChangeListener { pref, newValue ->
            prefs.edit().putStringSet(pref.key, newValue as Set<String>).commit()
            true
        }
    }

    fun makeStatusFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_STATUS"
        title = "Filter by status"
        entries = arrayOf("Down", "Ok", "Slow", "Beta")
        entryValues = arrayOf("0", "1", "2", "3")
        setDefaultValue(setOf("0", "1", "2", "3"))
        summary = "${(prefs.getStringSet(key, emptySet())?.size) ?: 0}/${entries.size} selected"
        setOnPreferenceChangeListener { pref, newValue ->
            prefs.edit().putStringSet(pref.key, newValue as Set<String>).commit()
            true
        }
    }
}

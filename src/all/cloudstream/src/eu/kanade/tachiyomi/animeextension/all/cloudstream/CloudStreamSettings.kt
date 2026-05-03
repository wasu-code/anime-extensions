package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
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

class CloudStreamSettings : AnimeSource, ConfigurableAnimeSource {
    override val id: Long = 133745
    val lang: String = "all"
    override val name: String = "! ⭐ CloudStream Settings ⭐ !"
    override fun toString(): String = name

    private val hostContext = Injekt.get<Application>()
    private val preferences: SharedPreferences by lazy {
        hostContext.getSharedPreferences("source_$id", 0x0000)
    }

    @SuppressLint("ApplySharedPref")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val scope = CoroutineScope(Dispatchers.IO)
        val fm by lazy { FilterManager.getInstance(preferences) }

        // Check host app compatibility with this extension
        val clazz = com.google.gson.stream.JsonReader::class.java
        val methodName = "setStrictness" // available only in gson v2.11+
        val hasMethod = clazz.methods.any { it.name == methodName }
        val hostAppName = hostContext.applicationInfo.loadLabel(hostContext.packageManager)
        if (!hasMethod) {
            newPreference(screen.context) {
                summary = """
                    Your host app ($hostAppName) uses an outdated version of the GSON library (older than v2.11.0).
                    Some extensions may not work properly (and throw NoSuchMethodError for setStrictness).
                """.trimIndent()
                setIcon_reflect(android.R.drawable.ic_dialog_alert)
            }.also(screen::addPreference)
        }

        EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Plugin repositories"
            dialogTitle = "List of plugin repositories"
            dialogMessage = "Paste here the repository URLs you want to add (one per line)\nRepository URL usually ends with /repo.json"
            summary = "${preferences.getString(key, "")?.lines()?.filter { it.isNotBlank() }?.size ?: 0} repo(s) added"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
                summary = "${newValue.lines().filter { it.isNotBlank() }.size} repo(s) added"
                fm.applyFilters(screen.context)
                true
            }
        }.also(screen::addPreference)

        newPreference(screen.context) {
            key = "BTN_ADD_ALL"
            title = "Add all well known repos"
            summary = "Add all well known plugin repositories."
            setIcon_reflect(android.R.drawable.ic_menu_add)
            setOnPreferenceClickListener {
                scope.launch {
                    val userRepos = fm.getRepos()
                    val wellKnownRepos = RepositoryManager.getWellKnownRepos()
                    val allRepos = (userRepos + wellKnownRepos).toSet()
                    preferences.edit().putString("REPOS", allRepos.joinToString("\n")).commit()
                    withContext(Dispatchers.Main) {
                        fm.applyFilters(screen.context)
                        Toast.makeText(screen.context, "Don't forget to select newly added repos in filters!", Toast.LENGTH_LONG).show()
                    }
                }
                true
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

        PreferenceCategory(screen.context).apply {
            title = "Manage plugins"
            summary = "Install or uninstall plugins"
        }.also(screen::addPreference)

        newPreference(screen.context) {
            title = "Purge all plugin files"
            summary = "${PluginManager.getPluginCount()} plugin(s) installed, ${APIHolder.allProviders.size} provider(s) loaded"
            setIcon_reflect(android.R.drawable.ic_menu_delete)
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context)
                    .setTitle("Purge plugins?")
                    .setMessage("This will remove all installed CloudStream plugins")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("Purge") { _, _ ->
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
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }.also(screen::addPreference)

        loadPluginList(screen, fm, scope)
    }

    private fun newPreference(context: Context, block: Preference.() -> Unit): Preference =
        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(context)
            .apply(block)

    private fun loadPluginList(screen: PreferenceScreen, fm: FilterManager, scope: CoroutineScope) {
        scope.launch {
            val loadingPref = newPreference(screen.context) {
                summary = "Loading plugins..."
                setIcon_reflect(android.R.drawable.button_onoff_indicator_off)
            }.also(screen::addPreference)

            val pluginStates = fm.getFilteredPlugins()
            withContext(Dispatchers.Main) {
                if (pluginStates.isNotEmpty()) {
                    loadingPref.apply {
                        summary = "Showing ${pluginStates.size} plugins"
                        setIcon_reflect(android.R.drawable.button_onoff_indicator_on)
                    }
                    pluginStates.forEach { pluginState ->
                        screen.addPreference(createPluginItem(screen.context, pluginState, scope))
                    }
                } else {
                    loadingPref.summary = "No plugins match current filters"
                }
            }
        }
    }

    private fun createPluginItem(context: Context, pluginState: PluginState, scope: CoroutineScope): Preference {
        val plugin = pluginState.plugin
        return newPreference(context) {
            title = "${plugin.name} (${plugin.language?.uppercase() ?: "ALL"})"
            summary = """
                ${plugin.description}
                ${plugin.tvTypes?.joinToString(" // ")}
                from: ${URL(plugin.repositoryUrl).path.trimStart('/')}
                version: ${plugin.version}
                status: ${arrayOf("Down", "Ok", "Slow", "Beta")[plugin.status]}
            """.trimIndent()

            val installed = pluginState.installed
            setDefaultValue(installed)
            setEnabled(installed || plugin.status > 0)
            setIcon_reflect(
                when {
                    // update pending
                    pluginState.updateAvailable == true -> android.R.drawable.ic_notification_overlay
                    // installed and working as expected
                    installed && plugin.status == 1 -> android.R.drawable.star_big_on
                    // installed but not working
                    installed && plugin.status == 0 -> android.R.drawable.ic_notification_clear_all
                    // installed but slow or in beta state
                    installed -> android.R.drawable.star_big_off
                    // not installed and not expected to work
                    plugin.status == 0 -> android.R.drawable.ic_notification_clear_all
                    // available for download
                    else -> android.R.drawable.stat_sys_download
                },
            )

            setOnPreferenceClickListener {
                val items = arrayOf("Install/Update", "Uninstall")
                AlertDialog.Builder(context)
                    .setTitle("Manage Plugin")
                    .setIcon(android.R.drawable.ic_dialog_dialer)
                    .setItems(items) { _, which ->
                        setEnabled(false)
                        scope.launch(Dispatchers.Main) {
                            val success = try {
                                withContext(Dispatchers.IO) {
                                    when (which) {
                                        0 -> {
                                            val file = PluginManager.downloadPluginToFile(plugin)
                                            file != null && PluginLoader.loadPlugin(hostContext, file)
                                        }
                                        1 -> PluginManager.deletePluginFile(plugin.url)
                                        else -> false
                                    }
                                }
                            } catch (_: Exception) {
                                false
                            }

                            if (success) {
                                when (which) {
                                    0 -> setIcon_reflect(android.R.drawable.star_big_on)
                                    1 -> setIcon_reflect(android.R.drawable.stat_sys_download)
                                }
                            } else {
                                val message = when (which) {
                                    0 -> "Initial load failed. Extension may not be supported"
                                    else -> "Operation failed"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                setIcon_reflect(android.R.drawable.ic_popup_disk_full)
                            }

                            setEnabled(true)
                            reloadPlugins()
                        }
                    }
                    .show()
                true
            }
        }
    }

    /**
     * Reload plugins from disk so they appear in the host app.
     */
    fun reloadPlugins() {
        val applicationId = hostContext.packageName // theoretically should be BuildConfig.APPLICATION_ID of host app
        val extensionPackageName = this::class.java.`package`?.name
        Intent("$applicationId.ACTION_EXTENSION_REPLACED").apply {
            data = "package:$extensionPackageName".toUri()
            `package` = hostContext.packageName
            hostContext.sendBroadcast(this)
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

//  === Preference Helpers ====================================================

private fun newPreference(context: Context, block: Preference.() -> Unit): Preference =
    Preference::class.java
        .getConstructor(Context::class.java)
        .newInstance(context)
        .apply(block)

@Suppress("FunctionName")
private fun PreferenceScreen.getPreference_reflect(index: Int): Preference? = try {
    PreferenceScreen::class.java
        .getMethod("getPreference", Int::class.javaPrimitiveType)
        .invoke(this, index) as? Preference
} catch (_: Exception) {
    null
}

@Suppress("FunctionName")
private fun PreferenceScreen.getPreferenceCount_reflect(): Int = try {
    PreferenceScreen::class.java
        .getMethod("getPreferenceCount")
        .invoke(this) as Int
} catch (_: Exception) {
    0
}

@Suppress("FunctionName")
private fun PreferenceScreen.removePreference_reflect(pref: Preference) {
    try {
        PreferenceScreen::class.java
            .getMethod("removePreference", Preference::class.java)
            .invoke(this, pref)
    } catch (_: Exception) {
    }
}

@Suppress("FunctionName")
private fun PreferenceScreen.removeAllPreferences_reflect() {
    try {
        PreferenceScreen::class.java
            .getMethod("removeAll")
            .invoke(this)
    } catch (_: Exception) {
    }
}

@Suppress("FunctionName")
fun Preference.setIcon_reflect(resId: Int): Preference {
    try {
        val contextField = this.javaClass.getDeclaredField("mContext")
        contextField.isAccessible = true
        val context = contextField.get(this)

        val drawable = context.javaClass
            .getMethod("getDrawable", Int::class.javaPrimitiveType)
            .invoke(context, resId)

        val drawableClass = Class.forName("android.graphics.drawable.Drawable")

        this.javaClass
            .getMethod("setIcon", drawableClass)
            .invoke(this, drawable)

        this.javaClass
            .getMethod("setIconSpaceReserved", Boolean::class.javaPrimitiveType)
            .invoke(this, true)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return this
}

//  === FilterManager =========================================================

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
     * Will use cached list if available or fetch and add them to cache.
     */
    suspend fun getPluginsForRepo(repoUrl: String): List<SitePlugin> {
        // Return cached if available
        PluginCache.get(repoUrl)?.let { return it }

        val mutex = repoMutexMap.getOrPut(repoUrl) { Mutex() }
        return mutex.withLock {
            PluginCache.get(repoUrl)?.let { return it }
            RepositoryManager.getRepoPlugins(repoUrl).also { PluginCache.put(repoUrl, it) }
        }
    }

    /** Return set of repository URLs added in settings. This includes disabled repositories. */
    fun getRepos(): Set<String> =
        prefs.getString("REPOS", "")
            ?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** Return set of repository URLs enabled in settings. */
    fun getActiveRepos(): Set<String> =
        prefs.getStringSet("FILTER_REPO", getRepos()) ?: emptySet()

    /**
     * Return list of all plugins across all repositories enabled in [makeRepoFilter] and locally installed.
     */
    suspend fun getPlugins(): List<PluginState> {
        val activeRepos = getActiveRepos()
        val repoPlugins = activeRepos.flatMap { getPluginsForRepo(it) }
        val installedPlugins = PluginManager.getInstalledPlugins()

        // sort here so plugins from not active repos are added at the end
        // but in alphabetical order
        val repoPluginsMap = repoPlugins
            .sortedBy { it.name }
            .associateBy { it.url.hashCode() }
        val installedPluginsMap = installedPlugins
            .sortedBy { it.name }
            .associateBy { it.url.hashCode() }

        val result = LinkedHashMap<Int, PluginState>()

        for ((key, repoPlugin) in repoPluginsMap) {
            val installed = installedPluginsMap[key]
            result[key] = PluginState(
                plugin = installed ?: repoPlugin,
                installed = installed != null,
                updateAvailable = installed != null && installed.version < repoPlugin.version,
                orphaned = false,
            )
        }

        // iterate local plugins
        for ((key, installed) in installedPluginsMap) {
            // not among plugins from enabled repos
            if (key !in repoPluginsMap) {
                // if repo is enabled but plugin not in repo anymore -> orphan
                // else we're not sure (would need to fetch its repo)
                val isOrphan = if (installed.repositoryUrl in activeRepos) true else null
                result[key] = PluginState(
                    plugin = installed,
                    installed = true,
                    updateAvailable = if (isOrphan == true) false else null,
                    orphaned = isOrphan,
                )
            }
        }

        return result.values.toList()
    }

    /** Return list of plugins matching current filter settings. */
    suspend fun getFilteredPlugins(): List<PluginState> {
        val selectedLangs = prefs.getStringSet("FILTER_LANGUAGE", emptySet()) ?: emptySet()
        val selectedTypes = prefs.getStringSet("FILTER_TVTYPE", TvType.values().map { it.name }.toSet()) ?: emptySet()
        val selectedStatus = prefs.getStringSet("FILTER_STATUS", setOf("0", "1", "2", "3")) ?: emptySet()
        val installedOnly = prefs.getBoolean("FILTER_INSTALLED_ONLY", false)

        return getPlugins().filter { pluginState ->
            val plugin = pluginState.plugin
            (!installedOnly || pluginState.installed) &&
                (plugin.tvTypes.isNullOrEmpty() || plugin.tvTypes.any { it in selectedTypes }) &&
                (plugin.status.toString() in selectedStatus) &&
                (selectedLangs.isEmpty() || plugin.language.isNullOrBlank() || plugin.language in selectedLangs)
        }
    }

    fun applyFilters(context: Context) { context.getActivity()?.recreate() }

    fun makeRepoFilter(context: Context) = MultiSelectListPreference(context).apply {
        key = "FILTER_REPO"
        title = "Repository"

        val repos = getRepos()
        entries = repos.map { URL(it).path.removePrefix("/") }.toTypedArray()
        entryValues = repos.toTypedArray()

        val storedValues = prefs.getStringSet(key, emptySet()) ?: emptySet()
        values = storedValues.intersect(repos.toSet())

        summary = "${values.size} repo(s) selected"
        setDefaultValue(repos)
        setOnPreferenceChangeListener { pref, newValue ->
            val selected = (newValue as Set<String>).intersect(repos.toSet())
            summary = "${selected.size} repo(s) selected"
            prefs.edit().putStringSet(key, selected).commit()
            (pref as MultiSelectListPreference).values = selected
            applyFilters(context)
            false
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
            val langs = getPlugins().mapNotNull { it.plugin.language }
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

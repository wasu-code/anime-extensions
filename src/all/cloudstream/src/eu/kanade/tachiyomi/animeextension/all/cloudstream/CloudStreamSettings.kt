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
import java.util.concurrent.atomic.AtomicInteger

object PluginCache {
    private val PLUGIN_MAP = mutableMapOf<String, List<SitePlugin>>()

    fun get(repoUrl: String): List<SitePlugin>? = PLUGIN_MAP[repoUrl]
    fun put(repoUrl: String, plugins: List<SitePlugin>) { PLUGIN_MAP[repoUrl] = plugins }
}

class CloudStreamSettings : AnimeSource, ConfigurableAnimeSource {
    override val id: Long = 133745
    val lang: String = "all"

    // `!` character to pin it to the top of the list
    override val name: String = "! CloudStream Settings"

    // display name
    override fun toString(): String = "Settings"

    private val hostContext = Injekt.get<Application>()
    private val preferences: SharedPreferences by lazy {
        hostContext.getSharedPreferences("source_$id", 0x0000)
    }

    @SuppressLint("ApplySharedPref")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val scope = CoroutineScope(Dispatchers.IO)
        val filters = PluginListFilters()
        val fm = FilterManager.getInstance(preferences)

        val reposPref = EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Plugin repositories"
            dialogTitle = "List of plugin repositories"
            dialogMessage = "Paste here the repository URLs you want to add (one per line)\nRepository URL usually ends with /repo.json"
            summary = "${preferences.getString(key, "")?.lines()?.filter { it.isNotBlank() }?.size ?: 0} repo(s) added"
            setDefaultValue("")
        }.also(screen::addPreference)

        val addWellKnown = newPreference(screen.context) {
            key = "BTN_ADD_ALL"
            title = "Add all well known repos"
            summary = "Add all well known plugin repositories."
            setIcon(android.R.drawable.ic_menu_add)
        }.also(screen::addPreference)

        val filtersCat = PreferenceCategory(screen.context).apply {
            title = "Filters"
            summary = "Set filters to limit plugins shown"
        }.also(screen::addPreference)

        val repos = fm.getRepos()

        val repoFilter = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_REPO"
            title = "Repository"
            entries = repos.map { URL(it).path.removePrefix("/") }.toTypedArray()
            entryValues = repos.toTypedArray()
            val storedValues = preferences.getStringSet(key, emptySet()) ?: emptySet()
            values = storedValues.intersect(repos)
            summary = "${values.size} repo(s) selected"
            setDefaultValue(repos)
        }.also(filtersCat::addPreference)

        // Entries populated asynchronously once the plugin list is first loaded
        val langFilter = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_LANGUAGE"
            title = "Language"
            summary = "Loading..."
            setEnabled(false)
            setDefaultValue(emptySet<String>())
        }.also(filtersCat::addPreference)

        val typeFilter = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_TVTYPE"
            title = "Type"
            entries = TvType.values().map { it.name }.toTypedArray()
            entryValues = entries
            setDefaultValue(entries.toSet())
            summary = "${(preferences.getStringSet(key, emptySet())?.size) ?: 0}/${entries.size} selected"
        }.also(filtersCat::addPreference)

        val statusFilter = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_STATUS"
            title = "Status"
            entries = arrayOf("Down", "Ok", "Slow", "Beta")
            entryValues = arrayOf("0", "1", "2", "3")
            setDefaultValue(setOf("0", "1", "2", "3"))
            summary = "${(preferences.getStringSet(key, emptySet())?.size) ?: 0}/${entries.size} selected"
        }.also(filtersCat::addPreference)

        val installedOnlyFilter = CheckBoxPreference(screen.context).apply {
            key = "FILTER_INSTALLED_ONLY"
            title = "Only installed"
            setDefaultValue(false)
        }.also(filtersCat::addPreference)

        PreferenceCategory(screen.context).apply {
            title = "Manage plugins"
            summary = "Install or uninstall plugins"
        }.also(screen::addPreference)

        newPreference(screen.context) {
            title = "Purge all plugin files"
            summary = "${PluginManager.getPluginCount()} plugin(s) installed, ${APIHolder.allProviders.size} provider(s) loaded"
            setIcon(android.R.drawable.ic_menu_delete)
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
                                reloadPluginList(screen, fm, scope, langFilter, filters)
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }.also(screen::addPreference)

        reposPref.setOnPreferenceChangeListener { _, newValue ->
            val newRepos = (newValue as String).lines().filter { it.isNotBlank() }.toSet()
            reposPref.summary = "${newRepos.size} repo(s) added"
            filters.allRepos = newRepos

            // Rebuild repo filter entries to match new repo list
            repoFilter.apply {
                entries = newRepos.map { URL(it).path.removePrefix("/") }.toTypedArray()
                entryValues = newRepos.toTypedArray()
                val current = values ?: emptySet()

                @Suppress("UNCHECKED_CAST")
                val valid = current.intersect(newRepos)
                if (valid != current) {
                    values = valid
                    filters.repos = valid
                }
                summary = "${values.size} repo(s) selected"
            }

            reloadPluginList(screen, fm, scope, langFilter, filters)
            true
        }

        repoFilter.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selected = (newValue as Set<String>)
            repoFilter.summary = "${selected.size} repo(s) selected"
            filters.repos = selected
            reloadPluginList(screen, fm, scope, langFilter, filters)
            true
        }

        langFilter.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selected = newValue as Set<String>
            langFilter.summary = "${selected.size}/${langFilter.entries?.size ?: 0} selected"
            filters.languages = selected
            reloadPluginList(screen, fm, scope, langFilter, filters)
            true
        }

        typeFilter.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selected = newValue as Set<String>
            typeFilter.summary = "${selected.size}/${typeFilter.entries?.size ?: 0} selected"
            filters.types = selected
            reloadPluginList(screen, fm, scope, langFilter, filters)
            true
        }

        statusFilter.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selected = newValue as Set<String>
            statusFilter.summary = "${selected.size}/${statusFilter.entries?.size ?: 0} selected"
            filters.statuses = selected
            reloadPluginList(screen, fm, scope, langFilter, filters)
            true
        }

        installedOnlyFilter.setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as Boolean
            filters.installedOnly = selected
            reloadPluginList(screen, fm, scope, langFilter, filters)
            true
        }

        addWellKnown.setOnPreferenceClickListener {
            scope.launch {
                val userRepos = fm.getRepos()
                val wellKnownRepos = RepositoryManager.getWellKnownRepos()
                val allRepos = (userRepos + wellKnownRepos).toSet()
                val joined = allRepos.joinToString("\n")
                preferences.edit().putString("REPOS", joined).commit()
                withContext(Dispatchers.Main) {
                    reposPref.text = joined
                    reposPref.summary = "${allRepos.size} repo(s) added"

                    // Refresh repoFilter entries to match the new repo list
                    repoFilter.apply {
                        entries = allRepos.map { URL(it).path.removePrefix("/") }.toTypedArray()
                        entryValues = allRepos.toTypedArray()
                        val validValues = (values ?: emptySet()).intersect(allRepos)
                        values = validValues
                        summary = "${validValues.size} repo(s) selected"
                    }

                    reloadPluginList(screen, fm, scope, langFilter, filters)

                    Toast.makeText(screen.context, "Don't forget to select newly added repos in filters!", Toast.LENGTH_LONG).show()
                }
            }
            true
        }

        // Initial load
        filters.apply {
            this.allRepos = reposPref.text.orEmpty().lines().filter { it.isNotBlank() }.toSet()
            this.repos = repoFilter.values
            this.languages = langFilter.values
            this.types = typeFilter.values
            this.statuses = statusFilter.values
            this.installedOnly = installedOnlyFilter.isChecked
        }
        reloadPluginList(screen, fm, scope, langFilter, filters)
    }

    private val requestGeneration = AtomicInteger(0)

    /**
     * Remove all plugin-list preferences from the screen, then reload them applying current
     * filter values.
     */
    private fun reloadPluginList(
        screen: PreferenceScreen,
        fm: FilterManager,
        scope: CoroutineScope,
        langFilter: MultiSelectListPreference,
        filters: PluginListFilters,
    ) {
        val current = filters.copy()
        // Every new reload invalidates previous async requests
        val generation = requestGeneration.incrementAndGet()

        val toRemove = (0 until screen.getPreferenceCount())
            .mapNotNull { screen.getPreference(it) }
            .filter { it.key?.startsWith("plugin_") == true }
        toRemove.forEach { screen.removePreference(it) }

        val loadingPref = newPreference(screen.context) {
            key = "plugin_loading"
            summary = "Loading plugins..."
            setIcon(android.R.drawable.button_onoff_indicator_off)
        }.also(screen::addPreference)

        scope.launch {
            val pluginStates = fm.getFilteredPlugins(current)

            // Populate lang filter entries from the full plugin list for the active repos
            val availableLangs = fm.getAvailableLangs(current.repos)

            withContext(Dispatchers.Main) {
                if (generation != requestGeneration.get()) return@withContext

                // Update lang filter entries now that we have plugin data.
                langFilter.apply {
                    val prevValues = values ?: emptySet<String>()
                    entries = availableLangs.toTypedArray()
                    entryValues = availableLangs.toTypedArray()
                    setEnabled(availableLangs.isNotEmpty())

                    // Prune any selected values that are no longer valid entry values
                    val validValues = prevValues.intersect(availableLangs.toSet())
                    values = validValues

                    summary = "${validValues.size}/${availableLangs.size} selected"
                }

                if (pluginStates.isNotEmpty()) {
                    loadingPref.apply {
                        summary = "Showing ${pluginStates.size} plugins"
                        setIcon(android.R.drawable.button_onoff_indicator_on)
                    }

                    if (generation != requestGeneration.get()) { return@withContext }

                    pluginStates.forEachIndexed { index, pluginState ->
                        createPluginItem(screen.context, pluginState, scope).apply {
                            key = "plugin_$index"
                        }.also(screen::addPreference)
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
            setIcon(
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
                                    0 -> setIcon(android.R.drawable.star_big_on)
                                    1 -> setIcon(android.R.drawable.stat_sys_download)
                                }
                            } else {
                                val message = when (which) {
                                    0 -> "Initial load failed. Extension may not be supported"
                                    else -> "Operation failed"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                setIcon(android.R.drawable.ic_popup_disk_full)
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

//  Those are basically stubs, since they should be shadowed by actual members

private fun PreferenceScreen.getPreference(index: Int): Preference? = try {
    PreferenceScreen::class.java
        .getMethod("getPreference", Int::class.javaPrimitiveType)
        .invoke(this, index) as? Preference
} catch (_: Exception) {
    null
}

private fun PreferenceScreen.getPreferenceCount(): Int = try {
    PreferenceScreen::class.java
        .getMethod("getPreferenceCount")
        .invoke(this) as Int
} catch (_: Exception) {
    0
}

private fun PreferenceScreen.removePreference(pref: Preference) {
    try {
        PreferenceScreen::class.java
            .getMethod("removePreference", Preference::class.java)
            .invoke(this, pref)
    } catch (_: Exception) {
    }
}

fun Preference.setIcon(resId: Int): Preference {
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

internal data class PluginListFilters(
    var allRepos: Set<String> = emptySet(),
    var repos: Set<String> = emptySet(),
    var languages: Set<String> = emptySet(),
    var types: Set<String> = emptySet(),
    var statuses: Set<String> = emptySet(),
    var installedOnly: Boolean = false,
)

@Suppress("UNCHECKED_CAST")
@SuppressLint("ApplySharedPref")
internal class FilterManager(private val prefs: SharedPreferences) {
    companion object {
        @Volatile private var instance: FilterManager? = null
        fun getInstance(prefs: SharedPreferences): FilterManager = instance ?: synchronized(this) {
            instance ?: FilterManager(prefs).also { instance = it }
        }
    }

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

    /** Return list of all plugins across the given active repos and locally installed plugins. */
    suspend fun getPlugins(activeRepos: Set<String>): List<PluginState> {
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

    /** Return list of plugins matching the provided filter values. */
    suspend fun getFilteredPlugins(filters: PluginListFilters): List<PluginState> = getPlugins(filters.repos).filter { pluginState ->
        val plugin = pluginState.plugin
        (!filters.installedOnly || pluginState.installed) &&
            (plugin.tvTypes.isNullOrEmpty() || plugin.tvTypes.any { it in filters.types }) &&
            (plugin.status.toString() in filters.statuses) &&
            (filters.languages.isEmpty() || plugin.language.isNullOrBlank() || plugin.language in filters.languages)
    }

    /**
     * Return sorted list of distinct language codes across all plugins in the given repos.
     * Used to populate the language filter entries after a plugin list load.
     */
    suspend fun getAvailableLangs(activeRepos: Set<String>): List<String> =
        getPlugins(activeRepos)
            .mapNotNull { it.plugin.language }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
}

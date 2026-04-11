package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
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

class CloudStreamSettings() : AnimeSource, ConfigurableAnimeSource {
    override val id: Long = 133745
    val lang: String = "all"
    override val name: String = "! ⭐ CloudStream Settings ⭐ !"
    override fun toString(): String = name

    private val hostContext = Injekt.get<Application>()
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
        val hostAppName = hostContext.applicationInfo.loadLabel(hostContext.packageManager)
        if (!hasMethod) {
            Preference::class.java
                .getConstructor(Context::class.java)
                .newInstance(screen.context)
                .apply {
                    summary = """
                        Your host app ($hostAppName) uses an outdated version of the GSON library (older than v2.11.0).
                        Some extensions may not work properly (and throw NoSuchMethodError for setStrictness).
                    """.trimIndent()
                    setIconReflect(android.R.drawable.ic_dialog_alert)
                }
                .also(screen::addPreference)
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

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                key = "BTN_ADD_ALL"
                title = "Add all well known repos"
                summary = "Add all well known plugin repositories."
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
            }
            .setIconReflect(android.R.drawable.ic_menu_add)
            .also(screen::addPreference)

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

//        ConfirmActionPreference(screen.context).apply {
//            key = "PLUGINS_PURGE"
//            title = "Purge all plugin files"
//            dialogMessage = "This will remove all installed CloudStream plugins"
//            onConfirm = { screen.context.getActivity()?.recreate() }
//            summary = "${PluginManager.getPluginCount()} plugin(s) installed, ${APIHolder.allProviders.size} provider(s) loaded"
//            onConfirm = {
//                scope.launch {
//                    setEnabled(false)
//                    val success = PluginManager.deleteAllPluginFiles()
//                    withContext(Dispatchers.Main) {
//                        Toast.makeText(screen.context, "All plugin files deleted? $success", Toast.LENGTH_SHORT).show()
//                        setEnabled(true)
//                        preferences.edit().putStringSet("EXTENSIONS", emptySet()).commit()
//                        fm.applyFilters(screen.context)
//                    }
//                }
//            }
//        }.also(extensionList::addPreference)

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                title = "Purge all plugin files"
                summary = "${PluginManager.getPluginCount()} plugin(s) installed, ${APIHolder.allProviders.size} provider(s) loaded"
                setIconReflect(android.R.drawable.ic_menu_delete)

                setOnPreferenceClickListener {
                    AlertDialog.Builder(screen.context)
                        .setTitle("Purge plugins?")
                        .setMessage("This will remove all installed CloudStream plugins")
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton("Purge") { dialog, which ->
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
            }
            .also(screen::addPreference)

        loadPluginList(screen, fm, scope)
    }

    fun Preference.setIconReflect(resId: Int): Preference {
        try {
            // get the context from Preference via reflection
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

            // always reserve space for icon
            this.javaClass
                .getMethod("setIconSpaceReserved", Boolean::class.javaPrimitiveType)
                .invoke(this, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return this
    }

    private fun loadPluginList(screen: PreferenceScreen, fm: FilterManager, scope: CoroutineScope) {
        scope.launch {
            val loadingPref = Preference::class.java
                .getConstructor(Context::class.java)
                .newInstance(screen.context)
                .apply {
                    summary = "Loading plugins..."
                    setIconReflect(android.R.drawable.button_onoff_indicator_off)
                }.also(screen::addPreference)

            val plugins = fm.getFilteredPlugins()
            withContext(Dispatchers.Main) {
                // Add divider and plugin switches
                if (plugins.isNotEmpty()) {
                    loadingPref.apply {
                        summary = "Showing ${plugins.size} plugins"
                        setIconReflect(android.R.drawable.button_onoff_indicator_on)
                    }
                    plugins.forEach { plugin ->
                        screen.addPreference(
                            createPluginItem(
                                screen.context,
                                plugin,
                                scope,
                                fm.isPluginInstalled(plugin.url),
                            ),
                        )
                    }
                } else {
                    loadingPref.summary = "No plugins match current filters"
                }
            }
        }
    }

    private fun createPluginItem(context: Context, plugin: SitePlugin, scope: CoroutineScope, isInstalled: Boolean = false): Preference {
        return Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(context).apply {
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
                setIconReflect(
                    when {
//                    updateAvailable -> android.R.drawable.ic_notification_overlay
                        installed && plugin.status == 0 -> android.R.drawable.ic_notification_clear_all
                        installed && plugin.status == 1 -> android.R.drawable.star_big_on
                        installed -> android.R.drawable.star_big_off
                        plugin.status == 0 -> android.R.drawable.ic_notification_clear_all
                        else -> android.R.drawable.stat_sys_download
                    },
                )

                setOnPreferenceClickListener {
                    val items = arrayOf(
                        "Install/Update",
                        "Uninstall",
                    )

                    AlertDialog.Builder(context)
                        .setTitle("Manage Plugin")
                        .setIcon(android.R.drawable.ic_dialog_dialer)
                        .setItems(items) { _, which ->
                            setEnabled(false)
                            var success: Boolean
                            scope.launch {
                                try {
                                    when (which) {
                                        0 -> {
                                            val file = PluginManager.downloadPluginToFile(plugin.url)
                                            // test drive
                                            success = file?.let {
                                                PluginLoader.loadPlugin(hostContext, it)
                                            } ?: false
                                            if (success) {
                                                setIconReflect(android.R.drawable.star_big_on)
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "Plugin load failed", Toast.LENGTH_SHORT).show()
                                                }
                                                setIconReflect(android.R.drawable.ic_popup_disk_full)
                                            }
                                        }
                                        1 -> {
                                            success = PluginManager.deletePluginFile(plugin.url)
                                            if (success) {
                                                setIconReflect(android.R.drawable.stat_sys_download)
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    setIconReflect(android.R.drawable.ic_popup_disk_full)
                                } finally {
                                    setEnabled(true)
                                    reloadPlugins()
                                }
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
            data = Uri.parse("package:$extensionPackageName")
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

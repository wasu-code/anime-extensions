package com.lagradost.cloudstream3

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AcraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
    }

    companion object {
        private var appContext: Context? = null
        fun getContext(): Context? = appContext

        val preferences: SharedPreferences by lazy {
            Injekt.get<Application>().getSharedPreferences("cs_source_all", 0x0000)
        }

        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        // Generic getter
        inline fun <reified T> getKey(path: String, defVal: T? = null): T? {
            val stringValue = preferences.getString(path, null) ?: return defVal
            return try {
                json.decodeFromString<T>(stringValue)
            } catch (e: Exception) {
                defVal
            }
        }

        // Generic setter
        inline fun <reified T> setKey(path: String, value: T) {
            val stringValue = json.encodeToString(value)
            preferences.edit().putString(path, stringValue).apply()
        }

        // Remove a single key
        fun removeKey(path: String) {
            preferences.edit().remove(path).apply()
        }

        // Remove keys by folder (prefix)
        fun removeKeys(folder: String): Int {
            val keysToRemove = preferences.all.keys.filter { it.startsWith("$folder/") }
            val editor = preferences.edit()
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
            return keysToRemove.size
        }

        // Get all keys in a folder (prefix)
        fun getKeys(folder: String): List<String> {
            val prefix = "$folder/"
            return preferences.all.keys
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
        }

        // Folder + path setter
        inline fun <reified T> setKey(folder: String, path: String, value: T) {
            setKey("$folder/$path", value)
        }

        // Folder + path getter
        inline fun <reified T> getKey(folder: String, path: String, defVal: T? = null): T? {
            return getKey("$folder/$path", defVal)
        }
    }
}

package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log

class LoggingContext(base: Context) : ContextWrapper(base) {

    // Public set of all keys accessed
    val accessedKeys: MutableSet<String> = mutableSetOf()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val prefs = super.getSharedPreferences(name, mode)
        return LoggingSharedPreferences(prefs, name, accessedKeys)
    }
}

class LoggingSharedPreferences(
    private val prefs: SharedPreferences,
    private val name: String,
    private val globalKeys: MutableSet<String>,
) : SharedPreferences {

    private fun trackKey(key: String?) {
        if (key != null) globalKeys.add(key)
    }

    // --- Reading methods ---
    override fun getString(key: String, defValue: String?): String? {
        trackKey(key)
        Log.d("Prefs", "[$name] getString called for key: $key")
        return prefs.getString(key, defValue)
    }

    override fun getInt(key: String, defValue: Int): Int {
        trackKey(key)
        Log.d("Prefs", "[$name] getInt called for key: $key")
        return prefs.getInt(key, defValue)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        trackKey(key)
        Log.d("Prefs", "[$name] getBoolean called for key: $key")
        return prefs.getBoolean(key, defValue)
    }

    override fun getLong(key: String, defValue: Long): Long {
        trackKey(key)
        Log.d("Prefs", "[$name] getLong called for key: $key")
        return prefs.getLong(key, defValue)
    }

    override fun getFloat(key: String, defValue: Float): Float {
        trackKey(key)
        Log.d("Prefs", "[$name] getFloat called for key: $key")
        return prefs.getFloat(key, defValue)
    }

    override fun getStringSet(key: String?, defValue: Set<String?>?): Set<String?>? {
        trackKey(key)
        Log.d("Prefs", "[$name] getStringSet called for key: $key")
        return prefs.getStringSet(key, defValue)
    }

    override fun edit(): SharedPreferences.Editor {
        return LoggingEditor(prefs.edit(), name, globalKeys)
    }

    // --- Editor wrapper ---
    private class LoggingEditor(
        private val editor: SharedPreferences.Editor,
        private val name: String,
        private val globalKeys: MutableSet<String>,
    ) : SharedPreferences.Editor {

        private fun trackKey(key: String?) {
            if (key != null) globalKeys.add(key)
        }

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            trackKey(key)
            Log.d("Prefs", "[$name] putString($key, $value)")
            editor.putString(key, value)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            trackKey(key)
            Log.d("Prefs", "[$name] putInt($key, $value)")
            editor.putInt(key, value)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            trackKey(key)
            Log.d("Prefs", "[$name] putBoolean($key, $value)")
            editor.putBoolean(key, value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            trackKey(key)
            Log.d("Prefs", "[$name] putLong($key, $value)")
            editor.putLong(key, value)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            trackKey(key)
            Log.d("Prefs", "[$name] putFloat($key, $value)")
            editor.putFloat(key, value)
            return this
        }

        override fun putStringSet(key: String?, value: Set<String?>?): SharedPreferences.Editor {
            trackKey(key)
            Log.d("Prefs", "[$name] putStringSet($key, $value)")
            editor.putStringSet(key, value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            trackKey(key)
            Log.d("Prefs", "[$name] remove($key)")
            editor.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            Log.d("Prefs", "[$name] clear()")
            editor.clear()
            return this
        }

        override fun commit(): Boolean {
            Log.d("Prefs", "[$name] commit()")
            return editor.commit()
        }

        override fun apply() {
            Log.d("Prefs", "[$name] apply()")
            editor.apply()
        }
    }

    // Delegate other SharedPreferences methods
    override fun getAll(): MutableMap<String, *> = prefs.all
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

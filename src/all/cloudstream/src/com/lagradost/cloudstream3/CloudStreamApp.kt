package com.lagradost.cloudstream3

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

open class CloudStreamApp : Application() {

    // WSU: some methods were omitted from this implementation
    // see: https://github.com/recloudstream/cloudstream/blob/master/app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.kt

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
    }

    companion object {
        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            private set(value) {
                _context = WeakReference(value)
                setContext(WeakReference(value))
            }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            // WSU ->
            val context = Injekt.get<Application>()
            val intent = Intent().apply {
                component = ComponentName(
                    context,
                    "eu.kanade.tachiyomi.ui.webview.WebViewActivity",
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("url_key", url)
                putExtra("source_key", 133745) // Hardcoded to CloudStreamSettings ID, should probably have ID of extensions that invokes it(?)
                putExtra("title_key", "Example title")
            }
            context.startActivity(intent)

            // <- WSU
        }

        fun openBrowser(url: String, activity: FragmentActivity?) {
            openBrowser(
                url,
                false,
                activity?.supportFragmentManager?.fragments?.lastOrNull()
            )
        }
    }
}

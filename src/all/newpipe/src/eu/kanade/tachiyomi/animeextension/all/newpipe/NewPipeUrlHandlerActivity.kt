package eu.kanade.tachiyomi.animeextension.all.newpipe

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import kotlin.system.exitProcess

class NewPipeUrlHandlerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            putExtra("query", urlWithSafeEnding(intent.data))
            putExtra("filter", packageName)
        }
        startActivity(mainIntent)

        finish()
        exitProcess(0)
    }
}

class NewPipeShareHandlerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shareText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        // Extract first valid URL from shared text
        val matcher = android.util.Patterns.WEB_URL.matcher(shareText)
        val match = if (matcher.find()) matcher.group() else null
        val url = Uri.parse(match)

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            putExtra("query", urlWithSafeEnding(url))
            putExtra("filter", packageName)
        }
        startActivity(mainIntent)

        finish()
        exitProcess(0)
    }
}

/** Host app trims trailing `-` or `_` what may break the link */
fun urlWithSafeEnding(url: Uri?): String {
    val length = url?.query?.length
    return if (length != null && length > 0) "$url&" else "$url?"
}

class ShareHandlerToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val enable = intent.getBooleanExtra("extra_enable", true)
        val componentName = ComponentName(
            this,
            NewPipeShareHandlerActivity::class.java,
        )
        packageManager.setComponentEnabledSetting(
            componentName,
            if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        finish()
    }
}

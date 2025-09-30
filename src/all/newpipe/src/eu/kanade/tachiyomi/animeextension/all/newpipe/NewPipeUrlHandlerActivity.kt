package eu.kanade.tachiyomi.animeextension.all.newpipe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlin.system.exitProcess

class NewPipeUrlHandlerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.data.toString()
//        val service = NewPipe.getServiceByUrl(url)

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            // TODO if query ends with _ or - it will be trimmed in query in Aniyomi
            putExtra("query", url)
            putExtra("filter", packageName)
        }
        startActivity(mainIntent)

        finish()
        exitProcess(0)
    }
}

package com.lagradost.cloudstream3

import android.app.Application
import android.content.Context

class AcraApplication : Application() {

    companion object {
        private var appContext: Context? = null

        fun getContext(): Context? = appContext

        inline fun <reified T : Any> getKey(path: String): T? {
            return null
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
    }
}

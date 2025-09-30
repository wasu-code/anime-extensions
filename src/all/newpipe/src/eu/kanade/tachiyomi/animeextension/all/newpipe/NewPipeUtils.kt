package eu.kanade.tachiyomi.animeextension.all.newpipe

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

object NewPipeInit {
    private var initialized = false

    fun init(client: OkHttpClient) {
        if (!initialized) {
            val downloader = OkHttpDownloader(client)
            NewPipe.init(downloader)
            initialized = true
        }
    }
}

class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
        val okRequest = Request.Builder()
            .url(request.url())
            .apply {
                for ((key, values) in request.headers()) {
                    values.forEach { value ->
                        addHeader(key, value)
                    }
                }
            }
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .build()

        val call = client.newCall(okRequest)
        val response = call.execute()
        val body = response.body.string()

        return org.schabi.newpipe.extractor.downloader.Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }
}

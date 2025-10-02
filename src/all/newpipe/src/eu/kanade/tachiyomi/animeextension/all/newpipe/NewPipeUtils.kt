package eu.kanade.tachiyomi.animeextension.all.newpipe

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.Description.HTML
import org.schabi.newpipe.extractor.stream.Description.MARKDOWN
import org.schabi.newpipe.extractor.stream.Description.PLAIN_TEXT
import java.text.SimpleDateFormat
import java.util.Locale

fun Description.plainText(): String = when (this.type) {
    PLAIN_TEXT, MARKDOWN -> this.content
    HTML -> Jsoup.parse(this.content).text()
    else -> "<invalid description type>"
}

class SortFilter(entries: Array<String>) : AnimeFilter.Select<String>("Sort", entries, 0)
class ContentFilter(entries: Array<String>) : AnimeFilter.Select<String>("Content", entries, 0)

inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

/** Parse the date-time part without the offset */
fun parseDate(dateString: String) = try {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    sdf.parse(dateString.take(19))?.time ?: 0L
} catch (e: Exception) {
    0L
}

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
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
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

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString(),
        )
    }
}

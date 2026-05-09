package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

class Techinmind: GDMirrorbot() {
    override var name = "Techinmind Cloud AIO"
    override var mainUrl = "https://stream.techinmind.space"
    override var requiresReferer = true
}

open class GDMirrorbot : ExtractorApi() {
    override var name = "GDMirrorbot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (sid, host) = if (!url.contains("key=")) {
            Pair(url.substringAfterLast("embed/"), getBaseUrl(app.get(url).url))
        } else {
            var pageText = app.get(url).text
            val finalId = Regex("""FinalID\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val myKey = Regex("""myKey\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val idType = Regex("""idType\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1) ?: "imdbid"
            val baseUrl = Regex("""let\s+baseUrl\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val hostUrl = baseUrl?.let { getBaseUrl(it) }

            if (finalId != null && myKey != null) {
                val apiUrl = "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                pageText = app.get(apiUrl).text
            }

            val jsonElement = json.parseToJsonElement(pageText)
            val jsonObject = jsonElement.jsonObject

            val embedId = url.substringAfterLast("/")
            val sidValue = jsonObject["data"]?.jsonArray
                ?.takeIf { it.isNotEmpty() }
                ?.get(0)?.jsonObject
                ?.get("fileslug")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: embedId

            Pair(sidValue, hostUrl)
        }

        val postData = mapOf("sid" to sid)
        val responseText = app.post("$host/embedhelper.php", data = postData).text

        val root = json.parseToJsonElement(responseText).jsonObject

        val siteUrls = root["siteUrls"]?.jsonObject ?: return
        val siteFriendlyNames = root["siteFriendlyNames"]?.jsonObject

        val mresultElement = root["mresult"] ?: return

        val decodedMresult = when (mresultElement) {
            is JsonObject -> mresultElement

            is JsonPrimitive -> try {
                val decoded = base64Decode(mresultElement.content)
                json.parseToJsonElement(decoded).jsonObject
            } catch (e: Exception) {
                Log.e("GDMirrorbot", "Failed to decode mresult: $e")
                return
            }

            else -> return
        }

        val keys = siteUrls.keys.intersect(decodedMresult.keys)

        keys.forEach { key ->
            val base = siteUrls[key]?.jsonPrimitive?.content?.trimEnd('/')
                ?: return@forEach

            val path = decodedMresult[key]?.jsonPrimitive?.content?.trimStart('/')
                ?: return@forEach
            val fullUrl = "$base/$path"
            val friendlyName = siteFriendlyNames?.get(key)?.jsonPrimitive?.content ?: key

            try {
                when (friendlyName) {
                    "StreamHG","EarnVids" -> VidHidePro().getUrl(fullUrl, referer, subtitleCallback, callback)
                    "RpmShare", "UpnShare", "StreamP2p" -> VidStack().getUrl(fullUrl, referer, subtitleCallback, callback)
                    else -> loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("GDMirrorbot", "Failed to extract from $friendlyName at $fullUrl: $e")
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}


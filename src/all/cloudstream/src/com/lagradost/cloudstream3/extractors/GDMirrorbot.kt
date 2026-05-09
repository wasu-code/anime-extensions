package com.lagradost.cloudstream3.extractors

// WSU -->
//import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
// WSU <--
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    // WSU -->
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    // WSU <--

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
                val apiUrl = if (url.contains("/tv/")) {
                    val season = Regex("""/tv/\d+/(\d+)/""").find(url)?.groupValues?.get(1) ?: "1"
                    val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
                    "$mainUrl/myseriesapi?tmdbid=$finalId&season=$season&epname=$episode&key=$myKey"
                } else {
                    "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                }
                pageText = app.get(apiUrl).text
            }

            // WSU -->
            //val jsonElement = JsonParser.parseString(pageText)
            //if (!jsonElement.isJsonObject) return
            //val jsonObject = jsonElement.asJsonObject
            val jsonElement = json.parseToJsonElement(pageText)
            val jsonObject = jsonElement.jsonObject
            // WSU <--

            val embedId = url.substringAfterLast("/")

            // WSU -->
//            val sidValue = jsonObject["data"]?.asJsonArray
//                ?.takeIf { it.size() > 0 }
//                ?.get(0)?.asJsonObject
//                ?.get("fileslug")?.asString
//                ?.takeIf { it.isNotBlank() } ?: embedId
            val sidValue = jsonObject["data"]?.jsonArray
                ?.takeIf { it.isNotEmpty() }
                ?.get(0)?.jsonObject
                ?.get("fileslug")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: embedId
            // WSU <--

            Pair(sidValue, hostUrl)
        }

        val postData = mapOf("sid" to sid)
        val responseText = app.post("$host/embedhelper.php", data = postData).text

        // WSU -->
//        val rootElement = JsonParser.parseString(responseText)
//        if (!rootElement.isJsonObject) return
//        val root = rootElement.asJsonObject
//
//        val siteUrls = root["siteUrls"]?.asJsonObject ?: return
//        val siteFriendlyNames = root["siteFriendlyNames"]?.asJsonObject
//
//        val decodedMresult = when {
//            root["mresult"]?.isJsonObject == true -> root["mresult"]!!.asJsonObject
//            root["mresult"]?.isJsonPrimitive == true -> try {
//                base64Decode(root["mresult"]!!.asString)
//                    .let { JsonParser.parseString(it).asJsonObject }
//            } catch (e: Exception) {
//                Log.e("GDMirrorbot", "Failed to decode mresult: $e")
//                return
//            }
//            else -> return
//        }
//
//        siteUrls.keySet().intersect(decodedMresult.keySet()).forEach { key ->
//            val base = siteUrls[key]?.asString?.trimEnd('/') ?: return@forEach
//            val path = decodedMresult[key]?.asString?.trimStart('/') ?: return@forEach
//            val fullUrl = "$base/$path"
//            val friendlyName = siteFriendlyNames?.get(key)?.asString ?: key
//
//            try {
//                when (friendlyName) {
//                    "StreamHG","EarnVids" -> VidHidePro().getUrl(fullUrl, referer, subtitleCallback, callback)
//                    "RpmShare", "UpnShare", "StreamP2p" -> VidStack().getUrl(fullUrl, referer, subtitleCallback, callback)
//                    else -> loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
//                }
//            } catch (e: Exception) {
//                Log.e("GDMirrorbot", "Failed to extract from $friendlyName at $fullUrl: $e")
//            }
//        }
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
        // WSU <--
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}


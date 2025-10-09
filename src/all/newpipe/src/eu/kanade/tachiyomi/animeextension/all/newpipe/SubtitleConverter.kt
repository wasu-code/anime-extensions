package eu.kanade.tachiyomi.animeextension.all.newpipe

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class SubtitleConverter {

    fun convertTtmlToSrt(ttml: String): String {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(ttml.reader())

        val sb = StringBuilder()
        var index = 1
        var text = ""
        var start: String? = null
        var end: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") {
                        start = parser.getAttributeValue(null, "begin")
                        end = parser.getAttributeValue(null, "end")
                        text = ""
                    }
                }
                XmlPullParser.TEXT -> text += parser.text
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p") {
                        sb.appendLine(index++)
                        sb.appendLine("${toSrtTime(start)} --> ${toSrtTime(end)}")
                        sb.appendLine(text.trim())
                        sb.appendLine()
                    }
                }
            }
            parser.next()
        }

        return sb.toString()
    }

    fun toSrtTime(tt: String?): String {
        if (tt == null) return "00:00:00,000"
        // Example TTML time: "00:00:04.560"
        return tt.replace(".", ",")
    }
}

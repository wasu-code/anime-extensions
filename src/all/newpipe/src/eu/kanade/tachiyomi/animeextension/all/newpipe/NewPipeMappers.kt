package eu.kanade.tachiyomi.animeextension.all.newpipe

import eu.kanade.tachiyomi.animesource.model.SAnime
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

// toSAnime
fun InfoItem.toSAnimeRaw(): SAnime = SAnime.create().apply {
    title = when (infoType) {
        InfoItem.InfoType.CHANNEL -> "👤 | $name"
        InfoItem.InfoType.PLAYLIST -> "≔ | $name"
        else -> name
    }
    thumbnail_url = thumbnails.last().url
    url = this@toSAnimeRaw.url
}

fun StreamInfo.toSAnimeRaw(): SAnime = SAnime.create().apply {
    title = name
    description = this@toSAnimeRaw.description.plainText()
    author = uploaderName
    thumbnail_url = thumbnails.last().url
    status = SAnime.COMPLETED
    url = this@toSAnimeRaw.url
}

fun PlaylistInfo.toSAnimeRaw(): SAnime = SAnime.create().apply {
    title = name
    description = this@toSAnimeRaw.description.plainText()
    author = uploaderName
    thumbnail_url = thumbnails.last().url
    url = this@toSAnimeRaw.url
}

fun ChannelInfo.toSAnimeRaw(): SAnime = SAnime.create().apply {
    title = "👤 | $name"
    description = description
    author = parentChannelName.ifBlank { name }
    thumbnail_url = avatars.last().url
    url = this@toSAnimeRaw.url
}

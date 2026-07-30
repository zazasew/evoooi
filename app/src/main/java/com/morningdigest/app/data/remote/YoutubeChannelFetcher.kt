package com.morningdigest.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Everything needed for the "add a YouTube channel" / "check for new
 * videos" feature - deliberately free of any Google API key, so adding a
 * channel is just "paste the channel address":
 * - [resolveChannel] fetches the public channel page HTML once (the same
 *   page a browser would load) and reads the channel ID, name, and avatar
 *   straight out of its standard `<meta>`/`<link>` tags.
 * - [fetchRecentVideos] reads the channel's own public Atom feed
 *   (`/feeds/videos.xml?channel_id=...`), the same free, keyless, no-quota
 *   mechanism podcast/RSS readers have used for YouTube for years.
 */
class YoutubeChannelFetcher(private val client: OkHttpClient) {

    data class ResolvedChannel(val channelId: String, val name: String, val avatarUrl: String)
    data class VideoEntry(val videoId: String, val title: String, val link: String)

    sealed class ResolveResult {
        data class Success(val channel: ResolvedChannel) : ResolveResult()
        data class Failure(val reason: String) : ResolveResult()
    }

    /** Accepts a full channel/handle URL (with or without scheme), a bare "@handle" or plain handle, or an existing "UC..." channel ID. */
    suspend fun resolveChannel(input: String): ResolveResult = withContext(Dispatchers.IO) {
        val trimmed = input.trim().removeSuffix("/")
        if (trimmed.isBlank()) return@withContext ResolveResult.Failure("Paste a channel link first")

        val pageUrl = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            // Handles pasted without a scheme, e.g. "youtube.com/@FoxNews" or "www.youtube.com/@FoxNews".
            trimmed.startsWith("youtube.com/", true) || trimmed.startsWith("www.youtube.com/", true) ||
                trimmed.startsWith("m.youtube.com/", true) -> "https://$trimmed"
            trimmed.startsWith("@") -> "https://www.youtube.com/$trimmed"
            trimmed.startsWith("UC") && trimmed.length in 20..30 && trimmed.none { it == '/' || it == ' ' } ->
                "https://www.youtube.com/channel/$trimmed"
            else -> "https://www.youtube.com/@$trimmed"
        }

        try {
            val html = fetchBody(pageUrl)
                ?: return@withContext ResolveResult.Failure("Couldn't reach that page - check the link and your connection")

            val channelId = extractChannelId(html)
                ?: return@withContext ResolveResult.Failure("That doesn't look like a YouTube channel page")

            val name = metaContent(html, "og:title")
                ?: return@withContext ResolveResult.Failure("Found the channel, but couldn't read its name")

            val avatarUrl = metaContent(html, "og:image").orEmpty()

            ResolveResult.Success(ResolvedChannel(channelId, name, avatarUrl))
        } catch (e: Exception) {
            ResolveResult.Failure(e.message ?: "Something went wrong resolving that channel")
        }
    }

    /**
     * Every video in the channel's feed, newest first (YouTube's own Atom
     * feed always lists them this way) - typically the ~15 most recent
     * uploads. Callers decide how far back "new" goes by comparing against
     * whatever video ID they last saw.
     */
    suspend fun fetchRecentVideos(channelId: String): List<VideoEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val xml = fetchBody("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId") ?: return@withContext emptyList()
            // The Atom feed does not expose a reliable "Short" flag. For the
            // small number of recent entries, inspect the public video page and
            // drop entries explicitly marked by YouTube as Shorts. This is only
            // used when a feed is actually being refreshed; it is not a
            // background polling loop.
            parseEntries(xml).filterNot { isShort(it) }
        }.getOrElse { emptyList() }
    }

    private fun fetchBody(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            // Without this, Google shows EU/EEA visitors a "Before you continue
            // to YouTube" cookie-consent page instead of the real channel page,
            // which has none of the tags we're looking for. This is the
            // standard, widely-documented way to skip that wall when there's
            // no browser session to click "I agree" in.
            .header("Cookie", "CONSENT=YES+cb.20240107-11-p0.en+FX+000; SOCS=CAI")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /**
     * Tries several places YouTube puts the channel ID, roughly most-to-least
     * reliable: the canonical link tag (simplest, least likely to change),
     * then the embedded page-data JSON blobs.
     */
    private fun extractChannelId(html: String): String? {
        Regex("<link[^>]*rel=[\"']canonical[\"'][^>]*href=[\"']https://www\\.youtube\\.com/channel/(UC[\\w-]{20,})[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { return it }
        Regex("\"channelId\":\"(UC[\\w-]{20,})\"").find(html)?.groupValues?.get(1)?.let { return it }
        Regex("\"externalId\":\"(UC[\\w-]{20,})\"").find(html)?.groupValues?.get(1)?.let { return it }
        Regex("itemprop=[\"']channelId[\"'][^>]*content=[\"'](UC[\\w-]{20,})[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /**
     * Reads a `<meta ...>` tag's `content` for a given `property`/`name`,
     * regardless of which attribute comes first in the tag - Open Graph tags
     * don't guarantee an order, and requiring one specific order is a common
     * way for this kind of scraping to silently break.
     */
    private fun metaContent(html: String, key: String): String? {
        val tagPattern = Regex(
            """<meta\s+[^>]*(?:property|name)=["']$key["'][^>]*>|<meta\s+[^>]*content=["'][^"']*["'][^>]*(?:property|name)=["']$key["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val tag = tagPattern.find(html)?.value ?: return null
        val contentPattern = Regex("content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        val raw = contentPattern.find(tag)?.groupValues?.get(1) ?: return null
        return android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun isShort(entry: VideoEntry): Boolean {
        if (entry.title.contains("#shorts", ignoreCase = true)) return true
        return runCatching {
            val html = fetchBody("https://www.youtube.com/watch?v=${entry.videoId}") ?: return@runCatching false
            Regex("\"isShort\"\\s*:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(html) ||
                html.contains("/shorts/${entry.videoId}", ignoreCase = true)
        }.getOrDefault(false)
    }

    /** Parses every <entry> in the channel's Atom feed, in the order YouTube provides (newest first). */
    private fun parseEntries(xml: String): List<VideoEntry> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val entries = mutableListOf<VideoEntry>()
        var inEntry = false
        var videoId: String? = null
        var title: String? = null
        var link: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        inEntry = true
                        videoId = null
                        title = null
                        link = null
                    }
                    "videoId" -> if (inEntry && videoId == null) videoId = parser.nextText().trim()
                    "title" -> if (inEntry && title == null) title = parser.nextText().trim()
                    "link" -> if (inEntry && link == null) {
                        val href = parser.getAttributeValue(null, "href")
                        if (!href.isNullOrBlank()) link = href
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry") {
                    val id = videoId
                    val t = title
                    val l = link
                    if (id != null && t != null && l != null) entries += VideoEntry(id, t, l)
                    inEntry = false
                }
            }
            event = parser.next()
        }
        return entries
    }
}

package app.svetlo

import java.net.URLDecoder

enum class MediaKind { DIRECT, HLS, DASH }

data class MediaItem(val url: String, val kind: MediaKind, val title: String? = null) {
    val isHls: Boolean get() = kind == MediaKind.HLS

    /** Every detected kind can be downloaded (DASH via [DashDownloader], audio saved separately). */
    val isSupported: Boolean get() = true

    /** MIME type used when handing this stream to an installed media player. */
    val externalMimeType: String
        get() = when (kind) {
            MediaKind.HLS -> "application/vnd.apple.mpegurl"
            MediaKind.DASH -> "application/dash+xml"
            MediaKind.DIRECT -> DIRECT_MIME[fileName.substringAfterLast('.', "").lowercase()] ?: "*/*"
        }

    val fileName: String get() = MediaDetector.fileName(url)
    val host: String get() = MediaDetector.split(url)?.host.orEmpty()

    /** Two-line label for a list dialog: name, then "kind · host". */
    val label: String
        get() {
            val name = fileName.takeIf {
                it.isNotBlank() && if (kind == MediaKind.DIRECT) {
                    it.substringAfterLast('.', "").isNotEmpty()
                } else {
                    it.substringBeforeLast('.').lowercase() !in GENERIC
                }
            }
                ?: title?.takeIf { it.isNotBlank() }
                ?: fileName.ifEmpty { host }
            val tag = when (kind) {
                MediaKind.DIRECT -> "Файл"
                MediaKind.HLS -> "Поток HLS"
                MediaKind.DASH -> "Поток DASH"
            }
            return "$name\n$tag · $host"
        }

    private companion object {
        val GENERIC = setOf("master", "index", "playlist", "manifest", "main", "video", "stream", "chunklist", "media", "")
        val DIRECT_MIME = mapOf(
            "mp4" to "video/mp4", "m4v" to "video/mp4", "3gp" to "video/3gpp", "mov" to "video/quicktime",
            "mkv" to "video/x-matroska", "webm" to "video/webm", "flv" to "video/x-flv",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        )
    }
}

/**
 * Collects media URLs seen on the current page (network requests + DOM scan). Thread-safe:
 * [offer] runs on WebView's IO threads, [list] on the UI thread.
 *
 * Heuristics and their limits:
 * - Segments (.ts/.m4s/.aac under a playlist, seg-N/fragN chunks, byte-range chunk URLs) are dropped.
 * - Any URL under the directory of a known .m3u8/.mpd (same host) is treated as part of that stream.
 * - A playlist requested within [variantWindowMs] after another playlist whose directory is a prefix
 *   of its own (same host) is treated as a variant/media playlist of it. Variants on another host or
 *   an unrelated path are still listed separately; two different videos in the same directory
 *   opened within the window collapse into one entry.
 */
class MediaDetector(
    private val clock: () -> Long = System::currentTimeMillis,
    private val variantWindowMs: Long = 30_000,
) {
    private class Stream(val host: String, val dir: String, val at: Long)

    private val lock = Any()
    private val items = LinkedHashMap<String, MediaItem>()
    private val streams = ArrayList<Stream>()

    /** Page title, set by the UI (e.g. from onReceivedTitle); attached to items returned by [list]. */
    @Volatile var pageTitle: String? = null

    fun clear() = synchronized(lock) {
        items.clear()
        streams.clear()
    }

    fun list(): List<MediaItem> {
        val title = pageTitle?.trim()?.takeIf { it.isNotEmpty() }
        return synchronized(lock) { items.values.map { if (it.title == null && title != null) it.copy(title = title) else it } }
    }

    val count: Int get() = synchronized(lock) { items.size }

    /** Returns true if a new item was added. */
    fun offer(url: String): Boolean {
        val kind = classify(url) ?: return false
        return add(url, kind)
    }

    /** Adds a URL found in a <video>/<audio> element regardless of extension. */
    fun offerDom(url: String): Boolean {
        if (offer(url)) return true
        if (isNoise(url)) return false
        return add(url, MediaKind.DIRECT)
    }

    private fun add(url: String, kind: MediaKind): Boolean {
        val u = split(url) ?: return false
        val dir = u.path.substringBeforeLast('/') + "/"
        val now = clock()
        synchronized(lock) {
            val key = url.substringBefore('#').substringBefore('?')
            if (items.containsKey(key)) return false
            if (kind == MediaKind.DIRECT) {
                if (streams.any { it.host == u.host && dir.startsWith(it.dir) }) return false
            } else {
                val parent = streams.any { it.host == u.host && dir.startsWith(it.dir) && now - it.at <= variantWindowMs }
                if (dir.length > 1) streams += Stream(u.host, dir, now)
                if (parent) return false
                // Segments seen before their playlist (e.g. from the DOM scan) are dropped retroactively.
                if (dir.length > 1) items.values.removeAll {
                    val o = split(it.url)
                    it.kind == MediaKind.DIRECT && o != null && o.host == u.host && o.path.startsWith(dir)
                }
            }
            while (items.size >= MAX_ITEMS) items.remove(items.keys.first())
            items[key] = MediaItem(url, kind)
            return true
        }
    }

    internal class Parts(val host: String, val path: String, val query: String)

    companion object {
        private val DIRECT_EXT = listOf(".mp4", ".webm", ".mkv", ".mov", ".3gp", ".m4v", ".mp3", ".m4a", ".ogg", ".flv")
        private const val MAX_ITEMS = 80
        private val SEGMENT_EXT = listOf(".ts", ".m4s", ".m4f", ".cmfv", ".cmfa")
        private val CHUNK_NAME = Regex("""(^|[-_.])(seg|segment|frag|fragment|chunk)[-_]?\d+([-_.]|$)""")
        private val PREVIEW = Regex("""(^|[/_.-])(thumb|thumbs|thumbnail|thumbnails|preview|previews|sprite|sprites)([/_.-]|$)""")
        private val RANGE_PARAM = Regex("""(^|&)(range|bytestart|byteend|byterange)=""")
        private val HLS_QUERY = Regex("""(^|&)(format|type|ext|output|protocol|mime|manifest)=([^&]*(m3u8|mpegurl)|hls)(&|$)""")
        private val DASH_QUERY = Regex("""(^|&)(format|type|ext|output|protocol|mime|manifest)=([^&]*(mpd|dash\+xml)|dash)(&|$)""")

        const val SCAN_JS = """(function(){var r=[];
          document.querySelectorAll('video,video source,audio,audio source').forEach(function(e){
            var s=e.currentSrc||e.src; if(s&&s.indexOf('blob:')!==0)r.push(s);});
          return JSON.stringify(r);})()"""

        /** Manual split instead of java.net.URI: real-world URLs often contain characters URI rejects. */
        internal fun split(url: String): Parts? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0 || url.substring(0, schemeEnd).lowercase() !in setOf("http", "https")) return null
            val rest = url.substring(schemeEnd + 3).substringBefore('#')
            val authEnd = rest.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) rest.length else it }
            val host = rest.substring(0, authEnd).substringAfterLast('@').lowercase()
            if (host.isBlank()) return null
            val tail = rest.substring(authEnd)
            val path = tail.substringBefore('?').ifEmpty { "/" }
            val query = if ('?' in tail) tail.substringAfter('?') else ""
            return Parts(host, path, query)
        }

        internal fun fileName(url: String): String {
            val p = split(url) ?: return ""
            val last = p.path.trimEnd('/').substringAfterLast('/')
            return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        }

        private fun decodedQuery(q: String) =
            runCatching { URLDecoder.decode(q.replace("+", "%2B"), "UTF-8") }.getOrDefault(q).lowercase()

        /** Kind of a network request URL, or null if it isn't a standalone media resource. */
        fun classify(url: String): MediaKind? {
            val u = split(url) ?: return null
            val path = u.path.lowercase()
            val query = decodedQuery(u.query)
            if (path.endsWith(".m3u8") || ".m3u8/" in path || HLS_QUERY.containsMatchIn(query)) return MediaKind.HLS
            if (path.endsWith(".mpd") || ".mpd/" in path || DASH_QUERY.containsMatchIn(query)) return MediaKind.DASH
            if (isNoise(url)) return null
            return if (DIRECT_EXT.any { path.endsWith(it) }) MediaKind.DIRECT else null
        }

        private fun isNoise(url: String): Boolean {
            val u = split(url) ?: return true
            val path = u.path.lowercase()
            val name = path.substringAfterLast('/')
            return SEGMENT_EXT.any { path.endsWith(it) } ||
                CHUNK_NAME.containsMatchIn(name.substringBeforeLast('.')) ||
                RANGE_PARAM.containsMatchIn(u.query.lowercase()) ||
                PREVIEW.containsMatchIn(path)
        }
    }
}

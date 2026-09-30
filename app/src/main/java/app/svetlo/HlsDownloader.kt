package app.svetlo

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS downloader without external deps: parses master/media playlists, fetches segments in
 * parallel (bounded window) and writes them strictly in order, decrypting AES-128 when needed.
 */
class HlsDownloader(
    private val headers: Map<String, String>,
    private val parallelism: Int = 4,
    private val credentialOrigin: String? = headers["X-Svetlo-Origin"],
    private val isPaused: () -> Boolean = { false },
    private val isCancelled: () -> Boolean,
) {
    class Key(val url: String, val iv: ByteArray?)

    /** [offset]/[length] are -1 unless the segment is an EXT-X-BYTERANGE sub-range. */
    class Segment(val url: String, val key: Key?, val seq: Long, val offset: Long = -1, val length: Long = -1)

    /** [ext] forces the file extension (DASH knows its container from the manifest). */
    class Playlist(val init: Segment?, val segments: List<Segment>, private val ext: String? = null) {
        val isFmp4: Boolean get() = init != null

        /** File extension for the concatenated stream. */
        fun extension(audio: Boolean = false): String {
            if (ext != null) return ext
            if (init != null) return if (audio) "m4a" else "mp4"
            val path = segments.first().url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".aac") -> "aac"
                path.endsWith(".mp3") -> "mp3"
                else -> "ts"
            }
        }

        fun mime(audio: Boolean = false): String = when (extension(audio)) {
            "mp4" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "weba" -> "audio/webm"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            else -> "video/mp2t"
        }
    }

    class Variant(
        val url: String,
        val bandwidth: Long,
        val resolution: String?,
        val codecs: String?,
        /** Separate audio rendition playlist, or null when audio is muxed into the variant. */
        val audioUrl: String?,
    ) {
        val width: Int = resolution?.substringBefore('x')?.toIntOrNull() ?: 0
        val height: Int = resolution?.substringAfter('x', "")?.toIntOrNull() ?: 0

        /** E.g. "720p · 2.5 Мбит/с" (+ " · звук отдельно"). */
        val label: String
            get() {
                val parts = ArrayList<String>()
                if (height > 0) parts += "${height}p"
                if (bandwidth > 0) parts += String.format(Locale.US, "%.1f Мбит/с", bandwidth / 1_000_000.0)
                if (parts.isEmpty()) parts += "Поток"
                if (audioUrl != null) parts += "звук отдельно"
                return parts.joinToString(" · ")
            }
    }

    /** Video playlist to download plus an optional separate audio playlist URL. */
    class Selection(val video: Playlist, val audioUrl: String?)

    /** Qualities of a master playlist, best first; empty for a media playlist. */
    fun variants(url: String): List<Variant> {
        val text = fetchWithRetry(url).decodeToString()
        return if (isMaster(text)) parseVariants(url, text) else emptyList()
    }

    /**
     * Resolves what to download. With [variantUrl] (from [variants]) that quality is used together
     * with [audioUrl]; otherwise the best variant is auto-picked (muxed audio preferred).
     */
    fun select(url: String, variantUrl: String? = null, audioUrl: String? = null): Selection {
        if (variantUrl != null) return Selection(resolve(variantUrl), audioUrl)
        val text = fetchWithRetry(url).decodeToString()
        if (!isMaster(text)) return Selection(parseMedia(url, text), audioUrl)
        val v = pickDefault(parseVariants(url, text)) ?: error("Нет вариантов потока")
        return Selection(resolve(v.url), v.audioUrl)
    }

    /** Media playlist for [url]; a master playlist resolves to its default variant (video only). */
    fun resolve(url: String): Playlist {
        var base = url
        var text = fetchWithRetry(base).decodeToString()
        if (isMaster(text)) {
            base = pickDefault(parseVariants(base, text))?.url ?: error("Нет вариантов потока")
            text = fetchWithRetry(base).decodeToString()
        }
        return parseMedia(base, text)
    }

    /** Writes init + all segments to [out] in order. [progress] runs on the calling thread. */
    fun download(p: Playlist, out: OutputStream, progress: (done: Int, total: Int, bytes: Long) -> Unit) {
        val parts = listOfNotNull(p.init) + p.segments
        val skip = parts.size - p.segments.size
        val threads = parallelism.coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "hls-fetch").apply { isDaemon = true } }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val futures = arrayOfNulls<Future<ByteArray>>(parts.size)
        val window = threads
        var submitted = 0
        var bytes = 0L
        try {
            for (i in parts.indices) {
                while (submitted < parts.size && submitted < i + window) {
                    val seg = parts[submitted]
                    futures[submitted++] = pool.submit(Callable { load(seg, keys) })
                }
                val data = await(futures[i]!!)
                futures[i] = null
                checkCancelled()
                out.write(data)
                bytes += data.size
                if (i >= skip) progress(i + 1 - skip, p.segments.size, bytes)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun await(f: Future<ByteArray>): ByteArray {
        while (true) {
            checkCancelled()
            try {
                return f.get(200, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            } catch (e: ExecutionException) {
                throw e.cause as? Exception ?: e
            }
        }
    }

    private fun load(seg: Segment, keys: ConcurrentHashMap<String, ByteArray>): ByteArray {
        val data = fetchWithRetry(seg.url, seg.offset, seg.length)
        val k = seg.key ?: return data
        val keyBytes = keys[k.url] ?: fetchWithRetry(k.url).also { keys[k.url] = it }
        require(keyBytes.size == 16) { "Неверный ключ шифрования" }
        val iv = k.iv ?: ByteBuffer.allocate(16).putLong(8, seg.seq).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun checkCancelled() {
        while (isPaused()) {
            if (isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
            Thread.sleep(100)
        }
        if (isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }

    internal fun fetchText(url: String): String = fetchWithRetry(url).decodeToString()

    /** Total size via a 1-byte Range probe; -1 when unknown or the server ignores ranges. */
    internal fun contentLength(url: String): Long = runCatching {
        checkCancelled()
        val conn = connection(url, "bytes=0-0")
        try {
            if (conn.responseCode != 206) -1L
            else conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(-1L)

    private fun fetchWithRetry(url: String, offset: Long = -1, length: Long = -1): ByteArray {
        var last: Exception? = null
        for (attempt in 0 until 4) {
            try {
                return fetch(url, offset, length)
            } catch (e: HttpException) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < 3) {
                // Back off 1s, 2s, 4s in short slices so cancellation stays responsive.
                val until = System.currentTimeMillis() + (1000L shl attempt)
                while (System.currentTimeMillis() < until) {
                    checkCancelled()
                    Thread.sleep(100)
                }
            }
        }
        throw last!!
    }

    private fun fetch(url: String, offset: Long = -1, length: Long = -1): ByteArray {
        checkCancelled()
        require(length <= MAX_RESPONSE_BYTES) { "Фрагмент слишком большой" }
        val conn = connection(url, if (length >= 0) "bytes=$offset-${offset + length - 1}" else null)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code)
            val body = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(conn.contentLength.coerceIn(8192, 16 shl 20))
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size().toLong() + n > MAX_RESPONSE_BYTES) throw IOException("Ответ сервера превышает лимит 16 МБ")
                    out.write(buf, 0, n)
                    checkCancelled()
                }
                out.toByteArray()
            }
            // Server ignored Range and sent the whole resource.
            if (length >= 0 && code == 200) {
                if (offset + length > body.size) throw IOException("Неполный ответ сервера")
                return body.copyOfRange(offset.toInt(), (offset + length).toInt())
            }
            if (code == 206 && length >= 0) {
                val range = conn.getHeaderField("Content-Range").orEmpty()
                require(range.startsWith("bytes $offset-")) { "Неверный диапазон ответа" }
                if (body.size.toLong() != length) throw IOException("Неполный ответ сервера")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** Redirects are explicit so a server cannot forward another origin's credentials. */
    private fun connection(url: String, range: String?): HttpURLConnection {
        var current = URL(url)
        require(current.protocol == "http" || current.protocol == "https") { "Неподдерживаемый адрес потока" }
        repeat(6) {
            checkCancelled()
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            headers.forEach { (k, v) ->
                if (!k.equals("X-Svetlo-Origin", true) &&
                    (!(k.equals("Cookie", true) || k.equals("Authorization", true)) || credentialOrigin != null && Origin.same(credentialOrigin, current.toString())) &&
                    !(k.equals("Referer", true) && v.startsWith("https:") && current.protocol == "http")) conn.setRequestProperty(k, v)
            }
            range?.let { conn.setRequestProperty("Range", it) }
            val code = try { conn.responseCode } catch (e: Exception) { conn.disconnect(); throw e }
            if (code !in listOf(301, 302, 303, 307, 308)) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location == null) throw IOException("Пустая переадресация")
            val next = URL(current, location)
            require(next.protocol == "http" || next.protocol == "https") { "Неподдерживаемая переадресация" }
            if (current.protocol == "https" && next.protocol != "https") throw IOException("Небезопасная переадресация HTTPS → HTTP")
            current = next
        }
        throw IOException("Слишком много переадресаций")
    }

    class HttpException(val code: Int) : IOException("HTTP $code")

    companion object {
        internal const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        private fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

        internal fun attr(line: String, name: String): String? =
            Regex("""(?:^|[:,])$name=("[^"]*"|[^,]*)""").find(line)?.groupValues?.get(1)?.trim('"')

        private fun hex(s: String): ByteArray {
            val padded = s.padStart(32, '0')
            return ByteArray(16) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        private fun rejectDrm(line: String) {
            val method = attr(line, "METHOD") ?: return
            val format = attr(line, "KEYFORMAT")
            if (method == "NONE") return
            if (method != "AES-128" || (format != null && format != "identity")) {
                error("Видео защищено DRM ($method) — скачать его нельзя")
            }
        }

        /** Parses a master playlist: deduped by height (muxed audio, then higher bandwidth wins), best first. */
        internal fun parseVariants(base: String, text: String): List<Variant> {
            val baseUrl = URL(base)
            class Rendition(val uri: String?, val default: Boolean)
            val audio = HashMap<String, MutableList<Rendition>>()
            val raw = ArrayList<Pair<String, String>>()
            var pending: String? = null
            for (l in text.lines()) {
                val line = l.trim()
                when {
                    line.startsWith("#EXT-X-SESSION-KEY:") -> rejectDrm(line)
                    line.startsWith("#EXT-X-MEDIA:") && attr(line, "TYPE") == "AUDIO" -> {
                        val group = attr(line, "GROUP-ID") ?: continue
                        audio.getOrPut(group) { ArrayList() } +=
                            Rendition(attr(line, "URI")?.let { URL(baseUrl, it).toString() }, attr(line, "DEFAULT") == "YES")
                    }
                    line.startsWith("#EXT-X-STREAM-INF:") -> pending = line
                    pending != null && line.isNotEmpty() && !line.startsWith("#") -> {
                        raw += pending to URL(baseUrl, line).toString()
                        pending = null
                    }
                }
            }
            val all = raw.map { (inf, uri) ->
                val group = attr(inf, "AUDIO")?.let { audio[it] }.orEmpty()
                val audioUrl = (group.firstOrNull { it.default && it.uri != null } ?: group.firstOrNull { it.uri != null })?.uri
                Variant(
                    url = uri,
                    bandwidth = attr(inf, "BANDWIDTH")?.toLongOrNull() ?: attr(inf, "AVERAGE-BANDWIDTH")?.toLongOrNull() ?: 0,
                    resolution = attr(inf, "RESOLUTION"),
                    codecs = attr(inf, "CODECS"),
                    audioUrl = audioUrl?.takeIf { it != uri },
                )
            }
            val byQuality = all.groupBy { if (it.height > 0) "h${it.height}" else "b${it.bandwidth}" }
            return byQuality.values
                .map { g -> g.sortedWith(compareBy<Variant> { if (it.audioUrl == null) 0 else 1 }.thenByDescending { it.bandwidth }).first() }
                .sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
        }

        /** Best variant, but a muxed one of similar quality (≥ 2/3 height or ≥ 60% bitrate) beats split audio. */
        fun pickDefault(variants: List<Variant>): Variant? {
            val top = variants.firstOrNull() ?: return null
            if (top.audioUrl == null) return top
            return variants.firstOrNull {
                it.audioUrl == null &&
                    if (top.height > 0) it.height * 3 >= top.height * 2 else it.bandwidth * 10 >= top.bandwidth * 6
            } ?: top
        }

        internal fun parseMedia(base: String, text: String): Playlist {
            require(text.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" } || !text.contains("#EXT-X-TARGETDURATION")) { "Это прямая трансляция (HLS live) — скачать её нельзя" }
            val baseUrl = URL(base)
            var seq = 0L
            var key: Key? = null
            var init: Segment? = null
            var range: Pair<Long, Long?>? = null
            var prevUrl: String? = null
            var prevEnd = 0L
            val segments = ArrayList<Segment>()
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0
                    line.startsWith("#EXT-X-KEY:") -> {
                        rejectDrm(line)
                        key = when (attr(line, "METHOD")) {
                            "NONE", null -> null
                            else -> Key(
                                URL(baseUrl, attr(line, "URI") ?: error("Нет ключа")).toString(),
                                attr(line, "IV")?.removePrefix("0x")?.removePrefix("0X")?.let(::hex),
                            )
                        }
                    }
                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        val v = line.substringAfter(':').trim()
                        range = v.substringBefore('@').toLong() to v.substringAfter('@', "").toLongOrNull()
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val uri = URL(baseUrl, attr(line, "URI") ?: continue).toString()
                        val br = attr(line, "BYTERANGE")
                        val map = if (br == null) Segment(uri, key, 0)
                        else Segment(uri, key, 0, br.substringAfter('@', "0").toLong(), br.substringBefore('@').toLong())
                        // A new init section mid-stream (after a discontinuity) goes inline.
                        if (init == null && segments.isEmpty()) init = map else segments += map
                    }
                    line.isNotEmpty() && !line.startsWith("#") -> {
                        val url = URL(baseUrl, line).toString()
                        val r = range
                        segments += if (r == null) Segment(url, key, seq) else {
                            val offset = r.second ?: if (url == prevUrl) prevEnd else 0L
                            prevUrl = url
                            prevEnd = offset + r.first
                            Segment(url, key, seq, offset, r.first)
                        }
                        range = null
                        seq++
                    }
                }
            }
            require(segments.isNotEmpty()) { "Плейлист пустой" }
            return Playlist(init, segments)
        }
    }
}

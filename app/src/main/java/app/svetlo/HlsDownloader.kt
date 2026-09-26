package app.svetlo

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HLS downloader: picks the highest-bandwidth variant, downloads all segments
 * sequentially, decrypts AES-128 if needed, and concatenates them into one stream.
 */
class HlsDownloader(
    private val headers: Map<String, String>,
    private val isCancelled: () -> Boolean,
) {
    class Segment(val url: String, val key: Key?, val seq: Long)
    class Key(val url: String, val iv: ByteArray?)
    class Playlist(val init: String?, val segments: List<Segment>)

    fun resolve(url: String): Playlist {
        var base = url
        var text = fetch(base).decodeToString()
        if (text.contains("#EXT-X-STREAM-INF")) {
            base = bestVariant(base, text)
            text = fetch(base).decodeToString()
        }
        return parseMedia(base, text)
    }

    fun download(p: Playlist, out: OutputStream, progress: (done: Int, total: Int) -> Unit) {
        val keys = HashMap<String, ByteArray>()
        p.init?.let { out.write(fetch(it)) }
        p.segments.forEachIndexed { i, seg ->
            if (isCancelled()) throw InterruptedException("cancelled")
            var data = fetchWithRetry(seg.url)
            seg.key?.let { k ->
                val keyBytes = keys.getOrPut(k.url) { fetch(k.url) }
                val iv = k.iv ?: ByteBuffer.allocate(16).putLong(8, seg.seq).array()
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                data = cipher.doFinal(data)
            }
            out.write(data)
            progress(i + 1, p.segments.size)
        }
    }

    private fun bestVariant(base: String, text: String): String {
        var best: String? = null
        var bestBw = -1L
        var pendingBw: Long? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingBw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLong() ?: 0
            } else if (pendingBw != null && line.isNotEmpty() && !line.startsWith("#")) {
                if (pendingBw > bestBw) {
                    bestBw = pendingBw
                    best = URL(URL(base), line).toString()
                }
                pendingBw = null
            }
        }
        return best ?: error("Нет вариантов потока")
    }

    private fun parseMedia(base: String, text: String): Playlist {
        val baseUrl = URL(base)
        var seq = 0L
        var key: Key? = null
        var init: String? = null
        val segments = ArrayList<Segment>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD")
                    key = when (method) {
                        "NONE", null -> null
                        "AES-128" -> Key(
                            URL(baseUrl, attr(line, "URI") ?: error("Нет ключа")).toString(),
                            attr(line, "IV")?.removePrefix("0x")?.removePrefix("0X")?.let(::hex),
                        )
                        else -> error("Шифрование $method не поддерживается (вероятно DRM)")
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> init = attr(line, "URI")?.let { URL(baseUrl, it).toString() }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += Segment(URL(baseUrl, line).toString(), key, seq)
                    seq++
                }
            }
        }
        require(segments.isNotEmpty()) { "Плейлист пустой" }
        return Playlist(init, segments)
    }

    private fun attr(line: String, name: String): String? =
        Regex("""(?:^|[:,])$name=("[^"]*"|[^,]*)""").find(line)?.groupValues?.get(1)?.trim('"')

    private fun hex(s: String): ByteArray {
        val padded = s.padStart(32, '0')
        return ByteArray(16) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun fetchWithRetry(url: String): ByteArray {
        var last: Exception? = null
        repeat(3) {
            try {
                return fetch(url)
            } catch (e: Exception) {
                last = e
                Thread.sleep(1000L * (it + 1))
            }
        }
        throw last!!
    }

    private fun fetch(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

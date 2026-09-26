package app.svetlo

import app.svetlo.HlsDownloader.Playlist
import app.svetlo.HlsDownloader.Segment
import app.svetlo.HlsDownloader.Variant
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/**
 * MPEG-DASH downloader for static MPDs. The manifest is turned into [Playlist]s so [HlsDownloader]
 * does the parallel ordered fetch. Variant ids are "dash:<Representation@id>".
 *
 * Multi-period: segments of all Periods are appended when every Period has the chosen
 * representation id (same encoding split into chapters), otherwise only the first Period is saved.
 */
class DashDownloader(private val dl: HlsDownloader) {
    class Selection(val video: Playlist, val audio: Playlist?)

    internal class Rep(
        val id: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val codecs: String?,
        val mime: String,
        val audio: Boolean,
        val drm: Boolean,
        /** Period, AdaptationSet, Representation. */
        val chain: List<Element>,
        val base: String,
        /** Period duration in seconds, NaN if unknown. */
        val duration: Double,
    ) {
        val webm get() = "webm" in mime
        val ext get() = if (audio) (if (webm) "weba" else "m4a") else (if (webm) "webm" else "mp4")
    }

    /** Audio/video representations of each Period in document order. */
    internal class Manifest(val periods: List<List<Rep>>) {
        val reps get() = periods.first()
    }

    /** Video qualities, best first; each carries its best matching audio track as [Variant.audioUrl]. */
    fun variants(url: String): List<Variant> = variants(parse(url, dl.fetchText(url)))

    /** [videoId]/[audioId] come from [variants]; without them the default quality is chosen. */
    fun select(url: String, videoId: String? = null, audioId: String? = null): Selection {
        val m = parse(url, dl.fetchText(url))
        var vid = videoId
        var aid = audioId
        if (vid == null) {
            val v = HlsDownloader.pickDefault(variants(m))
            if (v != null) {
                vid = v.url
                aid = v.audioUrl
            } else { // audio-only manifest
                vid = bestAudio(m.reps.filter { it.audio }, null)?.let { "dash:${it.id}" } ?: error("В манифесте нет видео")
                aid = null
            }
        }
        val length = { u: String -> dl.contentLength(u) }
        return Selection(playlist(m, vid, length), aid?.let { playlist(m, it, length) })
    }

    companion object {
        private const val DRM = "Видео защищено DRM — скачать его нельзя"
        private const val CHUNK = 2L shl 20
        private val TOKEN = Regex("""\$(RepresentationID|Number|Time|Bandwidth|)(?:%0(\d+)d)?\$""")
        private val ISO = Regex("""P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?""")
        private val AUDIO_CODEC = Regex("""^(mp4a|opus|vorbis|ac-3|ec-3|flac|mp3)""")

        internal fun variants(m: Manifest): List<Variant> {
            val video = m.reps.filter { !it.audio }
            val clear = video.filter { !it.drm }
            if (video.isNotEmpty() && clear.isEmpty()) error(DRM)
            val audio = m.reps.filter { it.audio && !it.drm }
            return clear.groupBy { if (it.height > 0) "h${it.height}" else "b${it.bandwidth}" }.values
                .map { g -> g.maxBy { it.bandwidth } }
                .sortedWith(compareByDescending<Rep> { it.height }.thenByDescending { it.bandwidth })
                .map { r ->
                    Variant(
                        "dash:${r.id}", r.bandwidth, if (r.width > 0 && r.height > 0) "${r.width}x${r.height}" else null,
                        r.codecs, bestAudio(audio, r)?.let { "dash:${it.id}" },
                    )
                }
        }

        /** Highest bitrate, preferring the video's container (mp4a with mp4, opus/vorbis with webm). */
        private fun bestAudio(audio: List<Rep>, video: Rep?) =
            audio.maxWithOrNull(compareBy<Rep> { video != null && it.webm == video.webm }.thenBy { it.bandwidth })

        internal fun playlist(m: Manifest, id: String, length: (String) -> Long): Playlist {
            val key = id.removePrefix("dash:")
            val first = m.reps.firstOrNull { it.id == key } ?: error("Поток не найден в манифесте")
            val same = m.periods.map { p -> p.firstOrNull { it.id == key } }
            val reps = if (same.all { it != null }) same.filterNotNull() else listOf(first)
            if (reps.any { it.drm }) error(DRM)
            val parts = reps.map { build(it, length) }
            return Playlist(parts[0].init, parts.flatMap { it.segments }, first.ext)
        }

        internal fun parse(url: String, xml: String): Manifest {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.isExpandEntityReferences = false }
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val root = f.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
            require(name(root) == "MPD") { "Это не DASH-манифест" }
            if (root.getAttribute("type") == "dynamic") error("Это прямая трансляция (DASH live) — скачать её нельзя")
            val mpdBase = baseOf(root, url)
            val total = iso(root.getAttribute("mediaPresentationDuration"))
            val periods = kids(root, "Period")
            require(periods.isNotEmpty()) { "В манифесте нет видео" }
            val out = periods.mapIndexed { pi, p ->
                val start = iso(p.getAttribute("start")).takeUnless { it.isNaN() } ?: 0.0
                val dur = iso(p.getAttribute("duration")).takeUnless { it.isNaN() }
                    ?: periods.getOrNull(pi + 1)?.let { iso(it.getAttribute("start")) - start }?.takeUnless { it.isNaN() }
                    ?: (total - start)
                val pBase = baseOf(p, mpdBase)
                kids(p, "AdaptationSet").withIndex().flatMap { (si, s) ->
                    val sBase = baseOf(s, pBase)
                    kids(s, "Representation").mapIndexedNotNull { ri, r ->
                        fun a(n: String) = r.getAttribute(n).ifEmpty { s.getAttribute(n) }.ifEmpty { null }
                        val mime = a("mimeType").orEmpty()
                        val codecs = a("codecs")
                        val type = s.getAttribute("contentType").ifEmpty { mime.substringBefore('/') }.ifEmpty {
                            when {
                                codecs == null -> ""
                                AUDIO_CODEC.containsMatchIn(codecs) -> "audio"
                                else -> "video"
                            }
                        }
                        if (type != "video" && type != "audio") return@mapIndexedNotNull null
                        Rep(
                            id = r.getAttribute("id").ifEmpty { "$si.$ri" },
                            bandwidth = a("bandwidth")?.toLongOrNull() ?: 0,
                            width = a("width")?.toIntOrNull() ?: 0,
                            height = a("height")?.toIntOrNull() ?: 0,
                            codecs = codecs,
                            mime = mime,
                            audio = type == "audio",
                            drm = kids(s, "ContentProtection").isNotEmpty() || kids(r, "ContentProtection").isNotEmpty(),
                            chain = listOf(p, s, r),
                            base = baseOf(r, sBase),
                            duration = dur,
                        )
                    }
                }
            }
            require(out.first().isNotEmpty()) { "В манифесте нет видео" }
            return Manifest(out)
        }

        internal fun build(r: Rep, length: (String) -> Long): Playlist {
            val inner = { n: String -> r.chain.reversed().mapNotNull { kids(it, n).firstOrNull() } }
            val tpl = inner("SegmentTemplate")
            if (tpl.isNotEmpty()) return template(r, tpl)
            inner("SegmentList").firstOrNull()?.let { return list(r, it) }
            // SegmentBase / bare BaseURL: one self-contained file, fetched in ranged chunks when possible.
            val size = length(r.base)
            val segs = if (size <= 0) listOf(Segment(r.base, null, 0)) else (0L until size step CHUNK)
                .mapIndexed { i, off -> Segment(r.base, null, i.toLong(), off, minOf(CHUNK, size - off)) }
            return Playlist(null, segs, r.ext)
        }

        /** [tpl] is innermost first: Representation attributes override AdaptationSet/Period ones. */
        private fun template(r: Rep, tpl: List<Element>): Playlist {
            fun a(n: String) = tpl.firstNotNullOfOrNull { it.getAttribute(n).ifEmpty { null } }
            val ts = a("timescale")?.toLongOrNull() ?: 1
            val startNumber = a("startNumber")?.toLongOrNull() ?: 1
            val pto = a("presentationTimeOffset")?.toLongOrNull() ?: 0
            val media = a("media") ?: error("Неподдерживаемый DASH-манифест")
            val times = ArrayList<Long>()
            val timeline = tpl.firstNotNullOfOrNull { kids(it, "SegmentTimeline").firstOrNull() }
            if (timeline != null) {
                val ss = kids(timeline, "S")
                var t = 0L
                ss.forEachIndexed { i, e ->
                    e.getAttribute("t").toLongOrNull()?.let { t = it }
                    val d = e.getAttribute("d").toLongOrNull()?.takeIf { it > 0 } ?: error("Неверный SegmentTimeline")
                    var rep = e.getAttribute("r").toLongOrNull() ?: 0
                    if (rep < 0) {
                        val until = ss.getOrNull(i + 1)?.getAttribute("t")?.toLongOrNull()
                            ?: if (r.duration.isNaN()) error("Неизвестна длительность видео") else pto + Math.round(r.duration * ts)
                        rep = (until - t + d - 1) / d - 1
                    }
                    require(times.size + rep < 1_000_000) { "Слишком много сегментов" }
                    for (k in 0..rep) {
                        times += t
                        t += d
                    }
                }
            } else {
                val d = a("duration")?.toLongOrNull()?.takeIf { it > 0 } ?: error("Неподдерживаемый DASH-манифест")
                if (r.duration.isNaN()) error("Неизвестна длительность видео")
                val count = ceil(r.duration * ts / d - 1e-6).toLong()
                require(count < 1_000_000) { "Слишком много сегментов" }
                for (i in 0 until count) times += pto + i * d
            }
            require(times.isNotEmpty()) { "Список сегментов пуст" }
            val segs = times.mapIndexed { i, t -> Segment(resolve(r.base, fill(media, r, startNumber + i, t)), null, i.toLong()) }
            val init = a("initialization")?.let { Segment(resolve(r.base, fill(it, r, startNumber, 0)), null, 0) }
            return Playlist(init, segs, r.ext)
        }

        private fun list(r: Rep, l: Element): Playlist {
            fun seg(url: String, range: String, i: Long): Segment {
                val u = if (url.isEmpty()) r.base else resolve(r.base, url)
                if (range.isEmpty()) return Segment(u, null, i)
                val (from, to) = range.split('-').map { it.trim().toLong() }
                return Segment(u, null, i, from, to - from + 1)
            }
            val init = kids(l, "Initialization").firstOrNull()?.let { seg(it.getAttribute("sourceURL"), it.getAttribute("range"), 0) }
            val segs = kids(l, "SegmentURL").mapIndexed { i, e -> seg(e.getAttribute("media"), e.getAttribute("mediaRange"), i.toLong()) }
            require(segs.isNotEmpty()) { "Список сегментов пуст" }
            return Playlist(init, segs, r.ext)
        }

        internal fun fill(tpl: String, r: Rep, number: Long, time: Long): String = TOKEN.replace(tpl) { m ->
            val v = when (m.groupValues[1]) {
                "" -> return@replace "$"
                "RepresentationID" -> return@replace r.id
                "Number" -> number
                "Time" -> time
                else -> r.bandwidth
            }
            m.groupValues[2].toIntOrNull()?.let { String.format(Locale.US, "%0${it}d", v) } ?: v.toString()
        }

        /** ISO 8601 duration (PnDTnHnMnS) in seconds, NaN if absent or unparseable. */
        internal fun iso(s: String?): Double {
            val g = ISO.matchEntire(s?.trim().orEmpty())?.groupValues ?: return Double.NaN
            fun n(i: Int) = g[i].toDoubleOrNull() ?: 0.0
            return n(1) * 86400 + n(2) * 3600 + n(3) * 60 + n(4)
        }

        private fun resolve(base: String, rel: String) = URL(URL(base), rel).toString()

        private fun name(e: Element) = e.localName ?: e.nodeName.substringAfter(':')

        private fun kids(e: Element, n: String): List<Element> {
            val out = ArrayList<Element>()
            var c = e.firstChild
            while (c != null) {
                if (c is Element && name(c) == n) out += c
                c = c.nextSibling
            }
            return out
        }

        private fun baseOf(e: Element, parent: String): String =
            kids(e, "BaseURL").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { resolve(parent, it) } ?: parent
    }
}

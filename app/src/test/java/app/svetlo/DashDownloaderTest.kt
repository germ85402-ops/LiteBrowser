package app.svetlo

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class DashDownloaderTest {
    private lateinit var server: TestServer

    @Before
    fun start() {
        server = TestServer()
    }

    @After
    fun stop() = server.close()

    private fun mpd(body: String, attrs: String = """type="static" mediaPresentationDuration="PT10S"""") =
        """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" $attrs>$body</MPD>"""

    private fun parse(xml: String, url: String = "http://h/v/manifest.mpd") = DashDownloader.parse(url, xml)

    private fun playlist(m: DashDownloader.Manifest, id: String, len: Long = -1) = DashDownloader.playlist(m, id) { len }

    private fun expectError(text: String, block: () -> Unit) {
        try {
            block()
            fail("expected error containing '$text'")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains(text))
        }
    }

    @Test
    fun templateWithTimelineRepeatToPeriodEnd() {
        val m = parse(mpd("""
            <Period>
              <AdaptationSet mimeType="video/mp4">
                <SegmentTemplate timescale="1000" initialization="${'$'}RepresentationID${'$'}/init.mp4"
                    media="${'$'}RepresentationID${'$'}/${'$'}Time${'$'}-${'$'}Number%05d${'$'}.m4s" startNumber="3">
                  <SegmentTimeline><S t="0" d="2000" r="1"/><S d="3000" r="-1"/></SegmentTimeline>
                </SegmentTemplate>
                <Representation id="v1" bandwidth="500000" width="1280" height="720" codecs="avc1.64001f"/>
              </AdaptationSet>
            </Period>"""))
        val p = playlist(m, "dash:v1")
        assertEquals("http://h/v/v1/init.mp4", p.init!!.url)
        // 2 × 2s, then 3s repeated up to 10s: t = 0, 2000, 4000, 7000
        assertEquals(
            listOf("0-00003", "2000-00004", "4000-00005", "7000-00006").map { "http://h/v/v1/$it.m4s" },
            p.segments.map { it.url },
        )
        assertEquals("mp4", p.extension())
        assertEquals("video/mp4", p.mime())
    }

    @Test
    fun templateDurationCountAndBandwidthToken() {
        val m = parse(mpd("""
            <Period duration="PT0H0M9.5S">
              <AdaptationSet contentType="audio" mimeType="audio/mp4">
                <Representation id="a" bandwidth="128000" codecs="mp4a.40.2">
                  <SegmentTemplate duration="2" initialization="i-${'$'}Bandwidth${'$'}.mp4" media="s${'$'}Number${'$'}.m4s"/>
                </Representation>
              </AdaptationSet>
            </Period>""", """type="static" mediaPresentationDuration="PT1H""""))
        val p = playlist(m, "a")
        assertEquals("http://h/v/i-128000.mp4", p.init!!.url)
        assertEquals((1..5).map { "http://h/v/s$it.m4s" }, p.segments.map { it.url })
        assertEquals("m4a", p.extension(audio = true))
        assertEquals(9.5, DashDownloader.iso("PT0H0M9.5S"), 1e-9)
        assertEquals(90061.0, DashDownloader.iso("P1DT1H1M1S"), 1e-9)
    }

    @Test
    fun segmentListAndNestedBaseUrls() {
        val m = parse(mpd("""
            <BaseURL>https://cdn.x/root/</BaseURL>
            <Period><BaseURL>p1/</BaseURL>
              <AdaptationSet mimeType="video/webm"><BaseURL>vid/</BaseURL>
                <Representation id="v" bandwidth="1" width="640" height="360"><BaseURL>360/</BaseURL>
                  <SegmentList>
                    <Initialization sourceURL="init.webm" range="0-99"/>
                    <SegmentURL media="a.webm"/>
                    <SegmentURL mediaRange="100-199"/>
                    <SegmentURL media="/abs/c.webm"/>
                  </SegmentList>
                </Representation>
              </AdaptationSet>
            </Period>"""))
        val p = playlist(m, "v")
        assertEquals("https://cdn.x/root/p1/vid/360/init.webm", p.init!!.url)
        assertEquals(100L, p.init!!.length)
        assertEquals(
            listOf("https://cdn.x/root/p1/vid/360/a.webm", "https://cdn.x/root/p1/vid/360/", "https://cdn.x/abs/c.webm"),
            p.segments.map { it.url },
        )
        assertEquals(100L, p.segments[1].offset)
        assertEquals(100L, p.segments[1].length)
        assertEquals("webm", p.extension())
        assertEquals("video/webm", p.mime())
    }

    @Test
    fun segmentBaseSingleFileIsChunked() {
        val m = parse(mpd("""
            <Period><AdaptationSet mimeType="video/mp4">
              <Representation id="v" bandwidth="1" width="1920" height="1080"><BaseURL>video.mp4</BaseURL>
                <SegmentBase indexRange="800-1199"><Initialization range="0-799"/></SegmentBase>
              </Representation>
            </AdaptationSet></Period>"""))
        val size = 5L * 1024 * 1024 + 7
        val p = playlist(m, "v", size)
        assertNull(p.init)
        assertEquals(3, p.segments.size)
        assertTrue(p.segments.all { it.url == "http://h/v/video.mp4" })
        assertEquals(size, p.segments.sumOf { it.length })
        assertEquals(4L * 1024 * 1024, p.segments[2].offset)
        // Unknown size (no Range support): the whole file as one request.
        assertEquals(-1L, playlist(m, "v").segments.single().length)
    }

    @Test
    fun variantsDedupeByHeightAndMatchAudioContainer() {
        val m = parse(mpd("""
            <Period>
              <AdaptationSet mimeType="video/mp4" contentType="video">
                <Representation id="v720a" bandwidth="2000000" width="1280" height="720"><BaseURL>a.mp4</BaseURL></Representation>
                <Representation id="v720b" bandwidth="3000000" width="1280" height="720"><BaseURL>b.mp4</BaseURL></Representation>
                <Representation id="v1080" bandwidth="5000000" width="1920" height="1080"><BaseURL>c.mp4</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet mimeType="video/webm">
                <Representation id="w480" bandwidth="900000" width="854" height="480"><BaseURL>w.webm</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet mimeType="audio/mp4">
                <Representation id="aac" bandwidth="128000" codecs="mp4a.40.2"><BaseURL>a.m4a</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet mimeType="audio/webm">
                <Representation id="opus" bandwidth="160000" codecs="opus"><BaseURL>o.weba</BaseURL></Representation>
              </AdaptationSet>
              <AdaptationSet mimeType="text/vtt"><Representation id="sub" bandwidth="1"/></AdaptationSet>
            </Period>"""))
        val vs = DashDownloader.variants(m)
        assertEquals(listOf("dash:v1080", "dash:v720b", "dash:w480"), vs.map { it.url })
        assertEquals(listOf("dash:aac", "dash:aac", "dash:opus"), vs.map { it.audioUrl })
        assertEquals("1080p · 5.0 Мбит/с · звук отдельно", vs[0].label)
        assertEquals("dash:v1080", StreamDownloader.pickDefault(vs)!!.url)
        assertEquals("weba", playlist(m, "opus").extension(audio = true))
    }

    @Test
    fun rejectsDrmAndLive() {
        val drm = parse(mpd("""
            <Period><AdaptationSet mimeType="video/mp4">
              <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
              <Representation id="v" bandwidth="1" height="720"><BaseURL>v.mp4</BaseURL></Representation>
            </AdaptationSet></Period>"""))
        expectError("DRM") { DashDownloader.variants(drm) }
        expectError("DRM") { playlist(drm, "v") }
        expectError("трансляция") { parse(mpd("<Period/>", """type="dynamic"""")) }
    }

    @Test
    fun multiPeriodConcatenatesSameRepresentation() {
        val period = { n: Int -> """<Period duration="PT4S"><AdaptationSet mimeType="video/mp4">
            <SegmentTemplate duration="2" initialization="init.mp4" media="p$n-${'$'}Number${'$'}.m4s"/>
            <Representation id="v" bandwidth="1" height="360"/></AdaptationSet></Period>""" }
        val p = playlist(parse(mpd(period(1) + period(2))), "v")
        assertEquals(listOf("p1-1", "p1-2", "p2-1", "p2-2").map { "http://h/v/$it.m4s" }, p.segments.map { it.url })
    }

    @Test
    fun downloadsVideoAndAudioInOrder() {
        val routes = server.routes
        routes["/d/v/init.mp4"] = "VINIT".toByteArray()
        routes["/d/a/init.mp4"] = "AINIT".toByteArray()
        val video = ByteArrayOutputStream().apply { write("VINIT".toByteArray()) }
        val audio = ByteArrayOutputStream().apply { write("AINIT".toByteArray()) }
        for (i in 1..10) {
            routes["/d/v/$i.m4s"] = "<v$i>".toByteArray()
            routes["/d/a/$i.m4s"] = "<a$i>".toByteArray()
            server.delays["/d/v/$i.m4s"] = ((11 - i) * 10).toLong() // earlier segments finish last
            video.write("<v$i>".toByteArray())
            audio.write("<a$i>".toByteArray())
        }
        routes["/d/manifest.mpd"] = mpd("""
            <Period>
              <AdaptationSet mimeType="video/mp4">
                <Representation id="v" bandwidth="1000000" width="1280" height="720">
                  <SegmentTemplate duration="1" initialization="v/init.mp4" media="v/${'$'}Number${'$'}.m4s"/>
                </Representation>
              </AdaptationSet>
              <AdaptationSet mimeType="audio/mp4">
                <Representation id="a" bandwidth="128000">
                  <BaseURL>a/</BaseURL>
                  <SegmentTemplate duration="1" initialization="init.mp4" media="${'$'}Number${'$'}.m4s"/>
                </Representation>
              </AdaptationSet>
            </Period>""").toByteArray()
        val dl = HlsDownloader(emptyMap(), parallelism = 4) { false }
        val url = "${server.base}/d/manifest.mpd"
        val vs = StreamDownloader.variants(url, emptyMap(), MediaKind.DASH)
        assertEquals("dash:a", vs.single().audioUrl)
        val sel = DashDownloader(dl).select(url)
        val vOut = ByteArrayOutputStream()
        val aOut = ByteArrayOutputStream()
        var last = 0
        dl.download(sel.video, vOut) { done, _, _ -> last = done }
        dl.download(sel.audio!!, aOut) { _, _, _ -> }
        assertArrayEquals(video.toByteArray(), vOut.toByteArray())
        assertArrayEquals(audio.toByteArray(), aOut.toByteArray())
        assertEquals(10, last)
    }

    @Test
    fun downloadsSingleFileInRangedChunks() {
        val body = ByteArray(5 * 1024 * 1024 + 123) { (it * 31).toByte() }
        server.routes["/s/video.mp4"] = body
        server.routes["/s/m.mpd"] = mpd("""<Period><AdaptationSet mimeType="video/mp4">
            <Representation id="v" bandwidth="1"><BaseURL>video.mp4</BaseURL><SegmentBase indexRange="0-9"/></Representation>
            </AdaptationSet></Period>""").toByteArray()
        val dl = HlsDownloader(emptyMap()) { false }
        val sel = DashDownloader(dl).select("${server.base}/s/m.mpd")
        assertEquals(3, sel.video.segments.size)
        assertNull(sel.audio)
        val out = ByteArrayOutputStream()
        dl.download(sel.video, out) { _, _, _ -> }
        assertArrayEquals(body, out.toByteArray())
    }
}

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
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsDownloaderTest {
    private lateinit var server: TestServer
    private val routes get() = server.routes
    private val delays get() = server.delays
    private val maxActive get() = server.maxActive
    private val base get() = server.base

    @Before
    fun start() {
        server = TestServer()
    }

    @After
    fun stop() = server.close()

    private fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(data)

    @Test
    fun picksBestVariantAndDecryptsSegments() {
        val key = ByteArray(16) { it.toByte() }
        val seg0 = "segment-zero".toByteArray()
        val seg1 = "segment-one!".toByteArray()
        // Segment 0 uses sequence-number IV, segment 1 uses an explicit IV.
        routes["/hi/0.ts"] = encrypt(seg0, key, ByteBuffer.allocate(16).putLong(8, 5).array())
        routes["/hi/1.ts"] = encrypt(seg1, key, ByteArray(16) { 0x11 })
        routes["/key.bin"] = key
        routes["/master.m3u8"] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=320x180
            lo/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720
            hi/index.m3u8
        """.trimIndent().toByteArray()
        routes["/hi/index.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-KEY:METHOD=AES-128,URI="/key.bin"
            #EXTINF:4.0,
            0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="/key.bin",IV=0x11111111111111111111111111111111
            #EXTINF:4.0,
            1.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val dl = HlsDownloader(emptyMap()) { false }
        val playlist = dl.resolve("$base/master.m3u8")
        assertEquals(2, playlist.segments.size)
        assertEquals("$base/hi/0.ts", playlist.segments[0].url)

        val out = ByteArrayOutputStream()
        var last = 0
        dl.download(playlist, out) { done, _, _ -> last = done }
        assertArrayEquals(seg0 + seg1, out.toByteArray())
        assertEquals(2, last)
    }

    @Test
    fun writesFmp4InitSegmentFirst() {
        routes["/v/init.mp4"] = "INIT".toByteArray()
        routes["/v/a.m4s"] = "A".toByteArray()
        routes["/v/media.m3u8"] = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\na.m4s\n".toByteArray()
        val dl = HlsDownloader(emptyMap()) { false }
        val p = dl.resolve("$base/v/media.m3u8")
        val out = ByteArrayOutputStream()
        dl.download(p, out) { _, _, _ -> }
        assertEquals("INITA", out.toString())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsSampleAes() {
        routes["/d.m3u8"] = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\nx.ts\n".toByteArray()
        HlsDownloader(emptyMap()) { false }.resolve("$base/d.m3u8")
    }

    @Test
    fun parsesVariantsWithAudioGroupsBestFirst() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",LANGUAGE="en",URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="ru",DEFAULT=YES,URI="audio/ru.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f",AUDIO="aud"
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.64001f",AUDIO="aud"
            720lo.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,URI="iframe.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            1080.m3u8
        """.trimIndent()
        val vs = HlsDownloader.parseVariants("http://h/v/master.m3u8", master)
        assertEquals(listOf(1080, 720, 360), vs.map { it.height })
        assertEquals("http://h/v/720.m3u8", vs[1].url)
        assertEquals(2500000L, vs[1].bandwidth)
        assertEquals("1280x720", vs[1].resolution)
        assertEquals("avc1.64001f", vs[1].codecs)
        assertEquals("http://h/v/audio/ru.m3u8", vs[0].audioUrl)
        assertNull(vs[2].audioUrl)
        assertEquals("avc1.4d401e,mp4a.40.2", vs[2].codecs)
        // Split-audio 1080p is still the default: the muxed 360p is not of similar quality.
        assertEquals(vs[0], HlsDownloader.pickDefault(vs))
    }

    @Test
    fun prefersMuxedVariantOfSimilarQuality() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="x",URI="a.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,AUDIO="a"
            split.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2900000,RESOLUTION=1280x720
            muxed.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="a"
            split1080.m3u8
        """.trimIndent()
        val vs = HlsDownloader.parseVariants("http://h/master.m3u8", master)
        assertEquals(2, vs.size)
        assertEquals("http://h/muxed.m3u8", vs[1].url)
        assertEquals("http://h/muxed.m3u8", HlsDownloader.pickDefault(vs)!!.url)
    }

    @Test
    fun variantsOfMediaPlaylistIsEmpty() {
        routes["/m.m3u8"] = "#EXTM3U\n#EXTINF:2,\na.ts\n".toByteArray()
        assertTrue(HlsDownloader(emptyMap()) { false }.variants("$base/m.m3u8").isEmpty())
    }

    @Test
    fun selectReturnsAudioRenditionOfChosenVariant() {
        routes["/s/master.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="x",DEFAULT=YES,URI="aud.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360,AUDIO="a"
            v.m3u8
        """.trimIndent().toByteArray()
        routes["/s/v.m3u8"] = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\n0.m4s\n".toByteArray()
        val sel = HlsDownloader(emptyMap()) { false }.select("$base/s/master.m3u8")
        assertEquals("$base/s/aud.m3u8", sel.audioUrl)
        assertEquals("mp4", sel.video.extension())
        assertEquals("m4a", sel.video.extension(audio = true))
    }

    @Test
    fun parallelFetchKeepsOrder() {
        val n = 12
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 0 until n) {
            routes["/p/$i.ts"] = "[$i]".toByteArray()
            delays["/p/$i.ts"] = ((n - i) * 15).toLong() // earlier segments finish last
            sb.append("#EXTINF:1,\n$i.ts\n")
        }
        routes["/p/index.m3u8"] = sb.toString().toByteArray()
        val dl = HlsDownloader(emptyMap(), parallelism = 4) { false }
        val out = ByteArrayOutputStream()
        val seen = ArrayList<Int>()
        var bytes = 0L
        dl.download(dl.resolve("$base/p/index.m3u8"), out) { done, total, b -> seen += done; bytes = b; assertEquals(n, total) }
        assertEquals((0 until n).joinToString("") { "[$it]" }, out.toString())
        assertEquals((1..n).toList(), seen)
        assertEquals(out.size().toLong(), bytes)
        assertTrue("expected concurrent fetches, got ${maxActive.get()}", maxActive.get() in 2..4)
    }

    @Test
    fun downloadsByteRangeSegments() {
        routes["/b/all.mp4"] = "INITAAAABBBCC".toByteArray()
        routes["/b/index.m3u8"] = """
            #EXTM3U
            #EXT-X-MAP:URI="all.mp4",BYTERANGE="4@0"
            #EXT-X-BYTERANGE:4@4
            #EXTINF:1,
            all.mp4
            #EXT-X-BYTERANGE:3
            #EXTINF:1,
            all.mp4
            #EXT-X-BYTERANGE:2@11
            #EXTINF:1,
            all.mp4
        """.trimIndent().toByteArray()
        val dl = HlsDownloader(emptyMap()) { false }
        val p = dl.resolve("$base/b/index.m3u8")
        assertEquals(8L, p.segments[1].offset)
        val out = ByteArrayOutputStream()
        dl.download(p, out) { _, _, _ -> }
        assertEquals("INITAAAABBBCC", out.toString())
    }

    @Test
    fun cancellationStopsQuickly() {
        routes["/c/0.ts"] = "x".toByteArray()
        delays["/c/0.ts"] = 10_000
        routes["/c/index.m3u8"] = "#EXTM3U\n#EXTINF:1,\n0.ts\n".toByteArray()
        val cancelled = AtomicBoolean()
        val dl = HlsDownloader(emptyMap()) { cancelled.get() }
        val p = dl.resolve("$base/c/index.m3u8")
        Thread { Thread.sleep(300); cancelled.set(true) }.start()
        val t0 = System.currentTimeMillis()
        try {
            dl.download(p, ByteArrayOutputStream()) { _, _, _ -> }
            fail("expected cancellation")
        } catch (_: InterruptedException) {
        }
        assertTrue(System.currentTimeMillis() - t0 < 2000)
    }

    @Test
    fun fileNamesAndProgressText() {
        assertEquals("Кот_ смешное_видео", HlsDownloadService.fileBase("  Кот/ смешное\nвидео. "))
        assertEquals(80, HlsDownloadService.fileBase("a".repeat(200)).length)
        assertTrue(HlsDownloadService.fileBase(null).matches(Regex("video_\\d{8}_\\d{6}")))
        assertEquals("12.3 МБ · 45% · 1.2 МБ/с", HlsDownloadService.formatProgress(12_897_485, 45, 1_258_291.0))
        assertEquals("0.5 МБ · 3% · 300 КБ/с", HlsDownloadService.formatProgress(524_288, 3, 307_200.0))
        assertEquals("0.0 МБ · 0%", HlsDownloadService.formatProgress(0, 0, 0.0))
    }
}

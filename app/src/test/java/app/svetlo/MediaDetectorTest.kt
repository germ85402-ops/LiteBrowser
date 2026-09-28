package app.svetlo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDetectorTest {
    private var now = 0L
    private val d = MediaDetector(clock = { now })

    @Test
    fun classifiesHlsInPathAndQuery() {
        val hls = listOf(
            "https://cdn.x/v/master.m3u8",
            "https://cdn.x/v/master.M3U8?token=1",
            "https://cdn.x/v/playlist.m3u8/clip",
            "https://cdn.x/api/stream?id=5&format=m3u8",
            "https://cdn.x/api/stream?mime=application%2Fx-mpegURL",
            "https://cdn.x/api/stream?type=hls",
        )
        hls.forEach { assertEquals(it, MediaKind.HLS, MediaDetector.classify(it)) }
        assertEquals(MediaKind.DASH, MediaDetector.classify("https://cdn.x/v/manifest.mpd"))
        assertEquals(MediaKind.DASH, MediaDetector.classify("https://cdn.x/api?format=mpd"))
        assertEquals(MediaKind.DIRECT, MediaDetector.classify("https://cdn.x/files/movie.mp4?sig=abc"))
        assertNull(MediaDetector.classify("https://cdn.x/page.html?format=m3u8x"))
        assertNull(MediaDetector.classify("blob:https://x/123"))
        assertNull(MediaDetector.classify("httpx://cdn.x/video.mp4"))
        assertNull(MediaDetector.classify("https:///video.mp4"))
    }

    @Test
    fun ignoresSegmentsChunksAndPreviews() {
        listOf(
            "https://cdn.x/a/seg-12.ts",
            "https://cdn.x/a/chunk.m4s",
            "https://cdn.x/a/video_seg-3.mp4",
            "https://cdn.x/a/frag12.mp4",
            "https://rr1.googlevideo.com/videoplayback.mp4?range=0-1000",
            "https://cdn.x/a/clip.mp4?bytestart=0&byteend=99",
            "https://cdn.x/thumbs/123.mp4",
            "https://cdn.x/a/clip_preview.webm",
        ).forEach { assertFalse(it, d.offer(it)) }
        assertEquals(0, d.count)
        assertTrue(d.offer("https://cdn.x/a/clip.mp4"))
    }

    @Test
    fun dedupesByPathAndIgnoresFilesUnderPlaylist() {
        assertTrue(d.offer("https://cdn.x/v/1/master.m3u8?t=1"))
        assertFalse(d.offer("https://cdn.x/v/1/master.m3u8?t=2"))
        assertFalse(d.offer("https://cdn.x/v/1/720/init.mp4"))
        assertFalse(d.offerDom("https://cdn.x/v/1/720/whatever"))
        assertTrue(d.offer("https://other.cdn/v/1/720/movie.mp4"))
        assertEquals(2, d.count)
    }

    @Test
    fun variantPlaylistsAfterMasterAreNotListed() {
        assertTrue(d.offer("https://cdn.x/v/9/master.m3u8"))
        now += 500
        assertFalse(d.offer("https://cdn.x/v/9/720p/index.m3u8"))
        assertFalse(d.offer("https://cdn.x/v/9/audio_ru.m3u8"))
        // A different video in another directory is listed.
        assertTrue(d.offer("https://cdn.x/v/10/master.m3u8"))
        // Outside the time window a playlist in the same tree counts as a new video.
        now += 60_000
        assertTrue(d.offer("https://cdn.x/v/9/other/master.m3u8"))
        assertEquals(3, d.count)
    }

    @Test
    fun playlistRemovesEarlierSegmentsFromDomScan() {
        assertTrue(d.offerDom("https://cdn.x/s/7/file"))
        assertTrue(d.offer("https://cdn.x/s/7/index.m3u8"))
        assertEquals(listOf(MediaKind.HLS), d.list().map { it.kind })
    }

    @Test
    fun labelsAndTitle() {
        d.offer("https://cdn.x/v/master.m3u8")
        d.offer("https://files.y/Big%20Buck.mp4")
        d.offer("https://cdn.z/dash/manifest.mpd")
        d.pageTitle = "Смешной кот"
        val items = d.list()
        assertEquals("Смешной кот\nПоток HLS · cdn.x", items[0].label)
        assertEquals("Big Buck.mp4\nФайл · files.y", items[1].label)
        assertTrue(items[0].isHls)
        assertEquals("Смешной кот", items[1].title)
        assertTrue(items[2].isSupported)
        assertTrue(items[2].label.contains("Поток DASH"))
        d.clear()
        assertEquals(0, d.count)
    }

    @Test
    fun mediaItemsExposePlayerMimeTypesAndStayBounded() {
        assertEquals("application/vnd.apple.mpegurl", MediaItem("https://cdn.x/video.m3u8", MediaKind.HLS).externalMimeType)
        assertEquals("application/dash+xml", MediaItem("https://cdn.x/video.mpd", MediaKind.DASH).externalMimeType)
        assertEquals("video/mp4", MediaItem("https://cdn.x/video.MP4?token=1", MediaKind.DIRECT).externalMimeType)
        assertEquals("audio/mpeg", MediaItem("https://cdn.x/track.mp3", MediaKind.DIRECT).externalMimeType)

        repeat(100) { assertTrue(d.offer("https://cdn.x/video-$it.mp4")) }
        assertEquals(80, d.count)
    }

    @Test
    fun rememberedPlaylistDirectoriesStayBounded() {
        val detector = MediaDetector()
        repeat(1000) { index ->
            detector.offer("https://cdn.example.com/playlist-$index/master.m3u8")
        }
        assertEquals(MediaDetector.MAX_STREAMS, detector.trackedStreamCount)
    }

    @Test
    fun domMediaWithoutAFileNameUsesThePageTitle() {
        val item = MediaItem("https://cdn.x/play?id=4", MediaKind.DIRECT, "Смешной кот")
        assertEquals("Смешной кот\nФайл · cdn.x", item.label)
    }
}

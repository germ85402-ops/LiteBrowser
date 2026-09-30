package app.svetlo

import app.svetlo.filter.PublicSuffix
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class SecurityRegressionTest {
    @Test fun permissionsDistinguishSchemeAndPort() {
        assertTrue(Origin.same("https://EXAMPLE.com/page", "https://example.com:443/other"))
        assertFalse(Origin.same("http://example.com", "https://example.com"))
        assertFalse(Origin.same("https://example.com:8443", "https://example.com"))
        assertNull(Origin.of("javascript:alert(1)"))
    }
    @Test fun privateSuffixesWildcardsAndExceptions() {
        val p = PublicSuffix("com\nco.uk\ngithub.io\n*.ck\n!www.ck")
        assertEquals("a.github.io", p.domain("cdn.a.github.io"))
        assertEquals("b.github.io", p.domain("b.github.io"))
        assertEquals("example.co.uk", p.domain("cdn.example.co.uk"))
        assertEquals("a.b.ck", p.domain("a.b.ck"))
        assertEquals("www.ck", p.domain("www.ck"))
    }
    @Test fun streamCredentialsStayOnTheirOriginIncludingAfterRedirect() {
        TestServer().use { s ->
            val foreign = s.base.replace("127.0.0.1", "localhost")
            s.routes["/start"] = "#EXTM3U\n#EXTINF:2,\n${s.base}/same\n#EXTINF:2,\n$foreign/foreign\n#EXTINF:2,\n${s.base}/redirect\n#EXT-X-ENDLIST".toByteArray()
            s.routes["/same"] = byteArrayOf(1)
            s.routes["/foreign"] = byteArrayOf(2)
            s.routes["/redirected"] = byteArrayOf(3)
            s.redirects["/redirect"] = "$foreign/redirected"
            val dl = HlsDownloader(mapOf("Cookie" to "secret=token", "Authorization" to "Bearer secret", "X-Svetlo-Origin" to s.base)) { false }
            dl.download(dl.resolve("${s.base}/start"), ByteArrayOutputStream()) { _, _, _ -> }
            assertEquals("secret=token", s.receivedHeaders["/same"]?.get("cookie"))
            assertNull(s.receivedHeaders["/foreign"]?.get("cookie"))
            assertNull(s.receivedHeaders["/redirected"]?.get("authorization"))
            assertNull(s.receivedHeaders["/same"]?.get("x-svetlo-origin"))
        }
    }
    @Test fun hlsLiveIsRejectedInsteadOfSavingAShortFragment() {
        try {
            HlsDownloader.parseMedia("https://example.com/live.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n")
            fail("live playlist should be rejected")
        } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("трансляция")) }
    }
    @Test fun pausedDownloadCanBeCancelled() {
        val cancelled = AtomicBoolean(false)
        val dl = HlsDownloader(emptyMap(), isPaused = { true }) { cancelled.get() }
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { try { dl.fetchText("https://example.com") } catch (_: InterruptedException) { done.countDown() } }
        worker.start()
        cancelled.set(true)
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }
}

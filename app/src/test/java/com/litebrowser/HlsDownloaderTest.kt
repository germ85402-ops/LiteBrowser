package com.litebrowser

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsDownloaderTest {
    private lateinit var server: ServerSocket
    private val routes = HashMap<String, ByteArray>()
    private val base get() = "http://127.0.0.1:${server.localPort}"

    // Tiny HTTP/1.0 server: android.jar on the test classpath hides com.sun.net.httpserver.
    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1].substringBefore('?')
                    while (reader.readLine().orEmpty().isNotEmpty()) { /* skip headers */ }
                    val body = routes[path]
                    val out = it.getOutputStream()
                    if (body == null) out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    else {
                        out.write("HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                        out.write(body)
                    }
                    out.flush()
                }
            }
        }.apply { isDaemon = true }.start()
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
        dl.download(playlist, out) { done, _ -> last = done }
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
        dl.download(p, out) { _, _ -> }
        assertEquals("INITA", out.toString())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsSampleAes() {
        routes["/d.m3u8"] = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\nx.ts\n".toByteArray()
        HlsDownloader(emptyMap()) { false }.resolve("$base/d.m3u8")
    }
}

package app.svetlo

import org.junit.Assert.assertEquals
import org.junit.Test

class MseRecorderTest {
    private fun mp4Init(handler: String): ByteArray {
        val hdlr = ByteArray(8) + "hdlr".toByteArray() + ByteArray(8) + handler.toByteArray()
        return byteArrayOf(0, 0, 0, 24) + "ftypiso6".toByteArray() + ByteArray(12) + hdlr
    }

    private val webmHeader = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    @Test
    fun usesSourceBufferType() {
        assertEquals(Triple("mp4", "video/mp4", false), MseRecorder.format("video/mp4; codecs=\"avc1.64001f\"", ByteArray(0)))
        assertEquals(Triple("m4a", "audio/mp4", true), MseRecorder.format("audio/mp4; codecs=\"mp4a.40.2\"", ByteArray(0)))
        assertEquals(Triple("webm", "video/webm", false), MseRecorder.format("video/webm; codecs=\"vp9\"", ByteArray(0)))
        assertEquals(Triple("weba", "audio/webm", true), MseRecorder.format("audio/webm; codecs=\"opus\"", ByteArray(0)))
    }

    @Test
    fun sniffsUnknownTypeFromInitSegment() {
        assertEquals("audio/mp4", MseRecorder.sniffType(mp4Init("soun")))
        assertEquals("video/mp4", MseRecorder.sniffType(mp4Init("vide")))
        assertEquals("audio/webm", MseRecorder.sniffType(webmHeader + "....A_OPUS....".toByteArray()))
        assertEquals("video/webm", MseRecorder.sniffType(webmHeader + "..V_VP9..A_OPUS".toByteArray()))
        assertEquals(Triple("m4a", "audio/mp4", true), MseRecorder.format("", mp4Init("soun")))
    }
}

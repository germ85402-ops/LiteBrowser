package app.svetlo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Remux compatible MP4 video + AAC audio without re-encoding or bundling ffmpeg. */
object MediaMerge {
    fun merge(ctx: Context, entry: DownloadEntry): String {
        val video = entry.contentUri ?: error("Нет файла видео")
        val audio = entry.extraUris.firstOrNull() ?: error("Нет отдельной аудиодорожки")
        val temp = File.createTempFile("merge-", ".mp4", ctx.cacheDir)
        val extractors = ArrayList<MediaExtractor>()
        val descriptors = ArrayList<android.os.ParcelFileDescriptor>()
        var muxer: MediaMuxer? = null
        var started = false
        var target: OutputTarget? = null
        try {
            val m = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val selections = ArrayList<Pair<MediaExtractor, Int>>()
            listOf(video to "video/", audio to "audio/").forEach { (uri, prefix) ->
                val fd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: error("Файл недоступен")
                descriptors += fd
                val ex = MediaExtractor()
                extractors += ex
                ex.setDataSource(fd.fileDescriptor)
                val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString("mime")?.startsWith(prefix) == true } ?: error("Дорожка не найдена")
                val format = ex.getTrackFormat(track)
                val mime = format.getString("mime")
                require(mime in setOf("video/avc", "video/hevc", "video/mp4v-es", "audio/mp4a-latm")) { "Этот кодек нельзя объединить в MP4" }
                ex.selectTrack(track)
                selections += ex to m.addTrack(format)
            }
            m.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            selections.forEach { (ex, index) ->
                val first = ex.sampleTime.coerceAtLeast(0)
                while (true) {
                    buffer.clear()
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    require(size <= buffer.capacity()) { "Кадр слишком большой" }
                    require(ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Дорожка защищена" }
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, (ex.sampleTime - first).coerceAtLeast(0), flags)
                    m.writeSampleData(index, buffer, info)
                    ex.advance()
                }
            }
            m.stop()
            started = false
            m.release()
            muxer = null
            val name = entry.name.substringBeforeLast('.') + " (со звуком).mp4"
            val out = OutputTarget(ctx, name, "video/mp4")
            target = out
            temp.inputStream().use { it.copyTo(out.stream) }
            out.commit()
            val id = Downloads.recordLocal(out.displayName, entry.source, out.mime)
            Downloads.finishLocal(id, out.contentUri, true, null, temp.length())
            return out.displayName
        } catch (e: Exception) {
            target?.abort()
            throw e
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
            descriptors.forEach { runCatching { it.close() } }
            temp.delete()
        }
    }
}

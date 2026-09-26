package app.svetlo

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Writes files to Download/Svetlo on every supported API level. */
class OutputTarget(private val ctx: Context, name: String, val mime: String) {
    val stream: OutputStream
    private var uri: Uri? = null
    private var file: File? = null

    /** Final display name (MediaStore / this class may append " (1)" on collisions). */
    var displayName: String = name
        private set

    /** content:// Uri openable by other apps; set by [commit], null if unknown. */
    var contentUri: Uri? = null
        private set

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Svetlo")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл")
            uri = u
            stream = ctx.contentResolver.openOutputStream(u) ?: error("Не удалось открыть файл")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Svetlo")
            dir.mkdirs()
            val f = unique(dir, name)
            file = f
            displayName = f.name
            stream = FileOutputStream(f)
        }
    }

    /** Closes and publishes the file. On API < 29 blocks briefly (≤3 s) waiting for the media scan. */
    fun commit() {
        stream.close()
        uri?.let {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(it, values, null, null)
            contentUri = it
            runCatching {
                ctx.contentResolver.query(it, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { n -> displayName = n }
                }
            }
        }
        file?.let {
            val done = CountDownLatch(1)
            MediaScannerConnection.scanFile(ctx, arrayOf(it.absolutePath), arrayOf(mime)) { _, scanned ->
                contentUri = scanned
                done.countDown()
            }
            done.await(3, TimeUnit.SECONDS)
        }
    }

    fun abort() {
        runCatching { stream.close() }
        uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
        file?.delete()
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (f.exists()) f = File(dir, "$base (${n++})$ext")
        return f
    }
}

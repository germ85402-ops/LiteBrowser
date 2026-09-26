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

/** Writes files to Download/Svetlo on every supported API level. */
class OutputTarget(private val ctx: Context, name: String, mime: String) {
    val stream: OutputStream
    private var uri: Uri? = null
    private var file: File? = null

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
            val f = File(dir, name)
            file = f
            stream = FileOutputStream(f)
        }
    }

    fun commit() {
        stream.close()
        uri?.let {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(it, values, null, null)
        }
        file?.let { MediaScannerConnection.scanFile(ctx, arrayOf(it.absolutePath), null, null) }
    }

    fun abort() {
        runCatching { stream.close() }
        uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
        file?.delete()
    }
}

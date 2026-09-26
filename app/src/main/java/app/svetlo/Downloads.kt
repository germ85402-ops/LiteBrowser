package app.svetlo

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Glue between the download sources (DownloadManager, app-saved files, stream service) and [DownloadRegistry]. */
object Downloads {
    private const val DM = "dm-"
    private const val SVC = "svc-"
    private val localSeq = AtomicInteger()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "downloads").apply { isDaemon = true } }
    private val lastSample = HashMap<Long, Pair<Long, Long>>()
    private val speeds = HashMap<Long, Double>()

    enum class Kind { VIDEO, AUDIO, IMAGE, DOC, OTHER }

    fun recordSystem(ctx: Context, systemId: Long, name: String, url: String, mime: String?) {
        DownloadRegistry.init(ctx)
        DownloadRegistry.put(
            DownloadEntry(
                id = DM + systemId, name = name, source = url, status = DownloadStatus.RUNNING,
                createdAt = System.currentTimeMillis(), mime = mime, systemId = systemId,
            ),
        )
    }

    /** Registers a file the app writes itself (blob:/data: URLs etc.); complete it with [finishLocal]. */
    fun recordLocal(name: String, source: String, mime: String?): String {
        val id = "app-${System.currentTimeMillis()}-${localSeq.incrementAndGet()}"
        DownloadRegistry.put(
            DownloadEntry(
                id = id, name = name, source = source.take(2048), status = DownloadStatus.RUNNING,
                createdAt = System.currentTimeMillis(), mime = mime,
            ),
        )
        return id
    }

    fun finishLocal(id: String, contentUri: Uri?, ok: Boolean, message: String?, bytes: Long = 0) {
        DownloadRegistry.update(id) {
            it.copy(
                status = if (ok) DownloadStatus.DONE else DownloadStatus.FAILED,
                contentUri = contentUri?.toString() ?: it.contentUri,
                message = message, bytes = bytes, total = bytes, percent = if (ok) 100 else it.percent, speed = 0.0,
            )
        }
    }

    fun hasActiveSystem() = DownloadRegistry.list().any { it.active && it.id.startsWith(DM) }

    /** Reads live state of every "dm-" entry from DownloadManager. Blocking; see [refreshSystemAsync]. */
    fun refreshSystem(ctx: Context) {
        val entries = DownloadRegistry.list().filter { it.id.startsWith(DM) && it.systemId >= 0 }
        if (entries.isEmpty()) return
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return
        val seen = HashSet<Long>()
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(*entries.map { it.systemId }.toLongArray()))?.use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iBytes = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iReason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val iLocal = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val iMime = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                while (c.moveToNext()) {
                    val sid = c.getLong(iId)
                    seen += sid
                    apply(
                        dm, sid, c.getInt(iStatus), c.getLong(iBytes), c.getLong(iTotal), c.getInt(iReason),
                        if (iLocal >= 0) c.getString(iLocal) else null, if (iMime >= 0) c.getString(iMime) else null,
                    )
                }
            } ?: return
        }.onFailure { return }
        for (e in entries) {
            if (e.systemId in seen) continue
            // Removed outside the app: notification cancel, Files app, system Downloads.
            when {
                e.active -> DownloadRegistry.update(e.id) { it.copy(status = DownloadStatus.CANCELLED, speed = 0.0, message = null) }
                e.status == DownloadStatus.DONE -> DownloadRegistry.update(e.id) {
                    it.copy(status = DownloadStatus.FAILED, message = "Файл удалён", contentUri = null)
                }
                else -> Unit
            }
        }
    }

    fun refreshSystemAsync(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { refreshSystem(app) }
    }

    private fun apply(dm: DownloadManager, sid: Long, status: Int, bytes: Long, total: Long, reason: Int, local: String?, mime: String?) {
        val speed = synchronized(this) {
            if (status != DownloadManager.STATUS_RUNNING) {
                lastSample.remove(sid); speeds.remove(sid); 0.0
            } else {
                val now = SystemClock.elapsedRealtime()
                val prev = lastSample[sid]
                if (prev == null || now - prev.first >= 800) {
                    lastSample[sid] = now to bytes
                    if (prev != null) {
                        val inst = (bytes - prev.second).coerceAtLeast(0) * 1000.0 / (now - prev.first)
                        val old = speeds[sid] ?: 0.0
                        speeds[sid] = if (old == 0.0) inst else old * 0.6 + inst * 0.4
                    }
                }
                speeds[sid] ?: 0.0
            }
        }
        val cur = DownloadRegistry.get(DM + sid) ?: return
        val pct = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        val base = cur.copy(bytes = bytes, total = total, percent = pct, speed = speed, mime = cur.mime ?: mime)
        val next = when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> base.copy(
                status = DownloadStatus.DONE, percent = 100, message = null, speed = 0.0,
                bytes = if (total > 0) total else bytes, total = if (total > 0) total else bytes,
                contentUri = runCatching { dm.getUriForDownloadedFile(sid)?.toString() }.getOrNull() ?: local ?: cur.contentUri,
            )
            DownloadManager.STATUS_FAILED -> base.copy(status = DownloadStatus.FAILED, message = failReason(reason), speed = 0.0)
            DownloadManager.STATUS_PAUSED -> base.copy(status = DownloadStatus.RUNNING, message = pauseReason(reason), speed = 0.0)
            DownloadManager.STATUS_PENDING -> base.copy(status = DownloadStatus.QUEUED, message = null)
            else -> base.copy(status = DownloadStatus.RUNNING, message = null)
        }
        if (next != cur) DownloadRegistry.put(next)
    }

    private fun pauseReason(r: Int) = when (r) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Ожидание сети"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Ожидание Wi‑Fi"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Повторная попытка…"
        else -> "Приостановлено"
    }

    private fun failReason(r: Int) = when (r) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "недостаточно места"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "хранилище недоступно"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "файл уже существует"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "слишком много перенаправлений"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "обрыв соединения"
        DownloadManager.ERROR_FILE_ERROR -> "ошибка записи файла"
        in 400..599 -> "сервер ответил $r"
        else -> "не удалось скачать"
    }

    private fun uriOf(ctx: Context, e: DownloadEntry): Uri? {
        if (e.id.startsWith(DM) && e.systemId >= 0) {
            runCatching { ctx.getSystemService(DownloadManager::class.java)?.getUriForDownloadedFile(e.systemId) }.getOrNull()?.let { return it }
        }
        return e.contentUri?.let(Uri::parse)
    }

    private fun mimeOf(ctx: Context, e: DownloadEntry): String {
        e.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { return it }
        if (e.systemId >= 0) {
            runCatching { ctx.getSystemService(DownloadManager::class.java)?.getMimeTypeForDownloadedFile(e.systemId) }.getOrNull()
                ?.takeIf { it != "application/octet-stream" }?.let { return it }
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "*/*"
    }

    fun open(ctx: Context, e: DownloadEntry) {
        val uri = uriOf(ctx, e) ?: return toast(ctx, "Файл не найден")
        val mime = mimeOf(ctx, e)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Players that claim video/* often can't play .ts etc.; ask each time rather than use a remembered default.
        val intent = if (mime.startsWith("video/") && mime != "video/mp4") {
            Intent.createChooser(view, e.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else view
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(ctx, "Нет приложения, чтобы открыть этот файл")
        } catch (_: SecurityException) {
            toast(ctx, "Нет доступа к файлу")
        }
    }

    fun share(ctx: Context, e: DownloadEntry) {
        val uri = uriOf(ctx, e) ?: return toast(ctx, "Файл не найден")
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(ctx, e))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(e.name, uri)
        try {
            ctx.startActivity(Intent.createChooser(send, e.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(ctx, "Нет приложения для отправки")
        }
    }

    fun cancel(ctx: Context, e: DownloadEntry) {
        when {
            e.id.startsWith(DM) && e.systemId >= 0 -> {
                runCatching { ctx.getSystemService(DownloadManager::class.java)?.remove(e.systemId) }
                DownloadRegistry.update(e.id) { it.copy(status = DownloadStatus.CANCELLED, speed = 0.0, message = null) }
            }
            e.id.startsWith(SVC) && e.jobId >= 0 -> HlsDownloadService.cancel(ctx, e.jobId)
        }
    }

    /** Removes the entry from the list; with [deleteFile] also deletes the downloaded file(s). */
    fun delete(ctx: Context, e: DownloadEntry, deleteFile: Boolean) {
        if (e.active) cancel(ctx, e)
        if (deleteFile) {
            if (e.id.startsWith(DM) && e.systemId >= 0) {
                runCatching { ctx.getSystemService(DownloadManager::class.java)?.remove(e.systemId) }
            } else {
                (listOfNotNull(e.contentUri) + e.extraUris).forEach { u ->
                    runCatching { ctx.contentResolver.delete(Uri.parse(u), null, null) }
                }
            }
        }
        DownloadRegistry.remove(e.id)
    }

    private fun toast(ctx: Context, t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    // Pure formatting helpers below are covered by DownloadsFormatTest.

    fun kind(name: String, mime: String?): Kind {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            m.startsWith("video/") || m.contains("mpegurl") || m == "application/dash+xml" ||
                ext in setOf("mp4", "m4v", "mkv", "webm", "ts", "mov", "avi", "3gp", "m3u8", "mpd") -> Kind.VIDEO
            m.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> Kind.AUDIO
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif") -> Kind.IMAGE
            m.startsWith("text/") || m == "application/pdf" || m.contains("document") || m.contains("msword") ||
                m.contains("spreadsheet") || m.contains("presentation") ||
                ext in setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "rtf", "epub", "fb2", "csv") -> Kind.DOC
            else -> Kind.OTHER
        }
    }

    /** "512 Б", "34 КБ", "1.2 МБ", "27 МБ", "1.5 ГБ". */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} Б"
        if (bytes < 1024 * 1024) return "${minOf(1023, (bytes + 512) / 1024)} КБ"
        val units = arrayOf("МБ", "ГБ", "ТБ")
        var v = bytes / 1048576.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f", v).removeSuffix(".0") + " " + units[i]
    }

    /** "850 КБ/с", "1.2 МБ/с"; empty when unknown. */
    fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec <= 0 -> ""
        bytesPerSec < 1048576 -> String.format(Locale.US, "%.0f КБ/с", maxOf(1.0, bytesPerSec / 1024))
        else -> String.format(Locale.US, "%.1f МБ/с", bytesPerSec / 1048576)
    }

    private val MONTHS = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    /** "14:20" today, "вчера 14:20", "3 мар 14:20" this year, "3 мар 2024" otherwise. */
    fun formatWhen(time: Long, now: Long = System.currentTimeMillis()): String {
        val t = Calendar.getInstance().apply { timeInMillis = time }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val y = (n.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val hm = String.format(Locale.US, "%d:%02d", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
        val day = "${t.get(Calendar.DAY_OF_MONTH)} ${MONTHS[t.get(Calendar.MONTH)]}"
        return when {
            same(t, n) -> hm
            same(t, y) -> "вчера $hm"
            t.get(Calendar.YEAR) == n.get(Calendar.YEAR) -> "$day $hm"
            else -> "$day ${t.get(Calendar.YEAR)}"
        }
    }

    /** Second line of a list row. */
    fun statusLine(e: DownloadEntry, now: Long = System.currentTimeMillis()): String = when (e.status) {
        DownloadStatus.QUEUED -> e.message ?: "В очереди"
        DownloadStatus.RUNNING -> {
            val size = when {
                e.total > 0 -> "${formatSize(e.bytes)} из ${formatSize(e.total)}"
                e.bytes > 0 -> formatSize(e.bytes)
                else -> null
            }
            listOfNotNull(
                e.message ?: progressPercent(e)?.let { "$it%" },
                size,
                formatSpeed(e.speed).takeIf { e.message == null && it.isNotEmpty() },
            ).joinToString(" · ").ifEmpty { "Загрузка…" }
        }
        DownloadStatus.DONE -> listOfNotNull("Готово", e.total.takeIf { it > 0 }?.let(::formatSize), formatWhen(e.createdAt, now)).joinToString(" · ")
        DownloadStatus.FAILED -> if (e.message.isNullOrBlank()) "Ошибка загрузки" else "Ошибка: ${e.message}"
        DownloadStatus.CANCELLED -> "Отменено"
    }

    /** Percent for the progress bar, or null when the size is unknown (indeterminate). */
    fun progressPercent(e: DownloadEntry): Int? = when {
        e.total > 0 -> (e.bytes * 100 / e.total).toInt().coerceIn(0, 100)
        e.percent > 0 -> e.percent.coerceIn(0, 100)
        else -> null
    }
}

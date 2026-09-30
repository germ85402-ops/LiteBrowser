package app.svetlo

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service for HLS/DASH downloads. Jobs run up to [PARALLEL_JOBS] at a time; each has its own
 * notification and cancel action, while a group-summary notification keeps the service in foreground.
 * Every job is mirrored in [DownloadRegistry] as "svc-<jobId>".
 */
class HlsDownloadService : Service() {
    private class Job(
        val id: Int,
        val url: String,
        val headers: Map<String, String>,
        val title: String?,
        val variantUrl: String?,
        val audioUrl: String?,
        val kind: MediaKind,
    ) {
        @Volatile var cancelled = false
        @Volatile var paused = false
        val noteId get() = 1000 + id
        val entryId get() = "svc-$id"
    }

    private val executor = Executors.newFixedThreadPool(PARALLEL_JOBS)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadRegistry.init(this)
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE || intent?.action == ACTION_RESUME) {
            val job = jobs[intent.getIntExtra(EXTRA_JOB, -1)]
            if (job != null) {
                job.paused = intent.action == ACTION_PAUSE
                DownloadRegistry.update(job.entryId) { it.copy(status = if (job.paused) DownloadStatus.PAUSED else DownloadStatus.RUNNING, speed = 0.0) }
                nm.notify(job.noteId, progressNote(job, job.title ?: "Видео", if (job.paused) "Приостановлено" else "Продолжаю…", 0))
            } else if (jobs.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            val job = jobs[intent.getIntExtra(EXTRA_JOB, -1)]
            if (job != null) {
                job.cancelled = true
                nm.notify(job.noteId, builder().setContentTitle(job.title ?: "Видео").setContentText("Отмена…")
                    .setSmallIcon(android.R.drawable.stat_sys_download).setGroup(GROUP).build())
            } else if (jobs.isEmpty()) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        // Every startForegroundService() call must be answered with startForeground().
        goForeground()
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (jobs.isEmpty()) stopAll()
            return START_NOT_STICKY
        }
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>) ?: hashMapOf()
        val job = Job(
            nextId.incrementAndGet(), url, headers, intent.getStringExtra(EXTRA_TITLE),
            intent.getStringExtra(EXTRA_VARIANT), intent.getStringExtra(EXTRA_AUDIO),
            if (intent.getStringExtra(EXTRA_KIND) == MediaKind.DASH.name) MediaKind.DASH else MediaKind.HLS,
        )
        jobs[job.id] = job
        DownloadRegistry.put(
            DownloadEntry(
                id = job.entryId, name = fileBase(job.title), source = url, status = DownloadStatus.QUEUED,
                createdAt = System.currentTimeMillis(), jobId = job.id,
            ),
        )
        nm.notify(job.noteId, progressNote(job, job.title ?: "Видео", "В очереди…", 0))
        goForeground()
        executor.execute { runJob(job) }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = jobs.size
        val note = builder()
            .setContentTitle("Скачивание видео")
            .setContentText(if (n <= 1) "Идёт загрузка" else "Загрузок: $n")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SUMMARY_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SUMMARY_ID, note)
        }
    }

    private fun runJob(job: Job) {
        var video: OutputTarget? = null
        var audio: OutputTarget? = null
        var videoSaved = false
        try {
            if (job.cancelled) throw InterruptedException("cancelled")
            val dl = HlsDownloader(job.headers, isPaused = { job.paused }) { job.cancelled }
            val videoPl: HlsDownloader.Playlist
            val audioPl: (() -> HlsDownloader.Playlist)?
            if (job.kind == MediaKind.DASH) {
                val sel = DashDownloader(dl).select(job.url, job.variantUrl, job.audioUrl)
                videoPl = sel.video
                audioPl = sel.audio?.let { a -> { a } }
            } else {
                val sel = dl.select(job.url, job.variantUrl, job.audioUrl)
                videoPl = sel.video
                audioPl = sel.audioUrl?.let { au -> { dl.resolve(au) } }
            }
            val base = fileBase(job.title)
            val out = OutputTarget(this, "$base.${videoPl.extension()}", videoPl.mime())
            video = out
            val name = out.displayName
            DownloadRegistry.update(job.entryId) { it.copy(name = name, mime = out.mime, status = DownloadStatus.RUNNING) }
            val vp = Progress(job, name, "")
            dl.download(videoPl, video.stream, vp::update)
            video.commit()
            videoSaved = true

            var audioName: String? = null
            val audioError = audioPl?.let { load ->
                runCatching {
                    val ap = load()
                    audio = OutputTarget(this, "$base (аудио).${ap.extension(audio = true)}", ap.mime(audio = true)).also { t ->
                        dl.download(ap, t.stream, Progress(job, name, "Звук: ")::update)
                        t.commit()
                        audioName = t.displayName
                    }
                }.exceptionOrNull()
            }
            if (audioError != null) {
                audio?.abort()
                if (job.cancelled) throw audioError
            }
            val text = when {
                audioName != null -> "Звук сохранён отдельным файлом «$audioName». Совместимые MP4/AAC можно объединить в менеджере загрузок."
                audioError != null -> "Видео сохранено без звука: звуковую дорожку скачать не удалось (${describe(audioError)})."
                else -> "Сохранено в Download/Svetlo"
            }
            val audioUri = audio?.takeIf { audioName != null }?.contentUri?.toString()
            DownloadRegistry.update(job.entryId) {
                it.copy(
                    status = DownloadStatus.DONE, name = name, mime = out.mime, contentUri = out.contentUri?.toString(),
                    extraUris = listOfNotNull(audioUri), message = text, percent = 100, speed = 0.0,
                )
            }
            finish(job, name, text, true, video.contentUri, video.mime, toast = "Скачано: $name")
        } catch (e: Throwable) {
            if (!videoSaved) video?.abort()
            audio?.abort()
            val cancelled = job.cancelled || e is InterruptedException
            val msg = if (cancelled) "Загрузка отменена" else "Ошибка: ${describe(e)}"
            DownloadRegistry.update(job.entryId) {
                it.copy(status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED, message = msg, speed = 0.0)
            }
            finish(job, job.title ?: "Видео", msg, false, null, null, toast = msg)
        } finally {
            jobs.remove(job.id)
            main.post {
                if (jobs.isEmpty()) stopAll() else goForeground()
            }
        }
    }

    private fun stopAll() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Throttled notification updates with a smoothed speed estimate. */
    private inner class Progress(private val job: Job, private val name: String, private val prefix: String) {
        private var lastNote = 0L
        private var lastT = SystemClock.elapsedRealtime()
        private var lastBytes = 0L
        private var speed = 0.0

        fun update(done: Int, total: Int, bytes: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastT >= 1000) {
                val inst = (bytes - lastBytes) * 1000.0 / (now - lastT)
                speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                lastT = now
                lastBytes = bytes
            }
            if (now - lastNote < 700 && done != total) return
            lastNote = now
            val pct = if (total > 0) done * 100 / total else 0
            val s = speed
            DownloadRegistry.update(job.entryId) {
                it.copy(
                    status = if (job.paused) DownloadStatus.PAUSED else DownloadStatus.RUNNING, bytes = bytes, percent = pct, speed = s,
                    message = if (prefix.isEmpty()) null else "Загрузка звука",
                )
            }
            nm.notify(job.noteId, progressNote(job, name, prefix + formatProgress(bytes, pct, speed), pct))
        }
    }

    private fun progressNote(job: Job, title: String, text: String, pct: Int): Notification {
        val cancel = PendingIntent.getService(
            this, job.id,
            Intent(this, HlsDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB, job.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return builder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setGroup(GROUP)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, pct == 0)
            .addAction(Notification.Action.Builder(null, "Отмена", cancel).build())
            .build()
    }

    private fun finish(job: Job, title: String, text: String, success: Boolean, uri: Uri?, mime: String?, toast: String) {
        main.post { Toast.makeText(this, toast, Toast.LENGTH_LONG).show() }
        val b = builder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setGroup(GROUP)
            .setAutoCancel(true)
        if (success) {
            b.setContentIntent(PendingIntent.getActivity(this, job.id, openIntent(uri, mime), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        nm.notify(job.noteId, b.build())
    }

    private fun openIntent(uri: Uri?, mime: String?): Intent {
        if (uri == null) return Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // .ts often has no default handler; a chooser shows a message instead of silently doing nothing.
        return if (mime == "video/mp4") view else Intent.createChooser(view, "Открыть видео").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)

    override fun onDestroy() {
        jobs.values.forEach { it.cancelled = true }
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val GROUP = "app.svetlo.downloads"
        private const val SUMMARY_ID = 1
        private const val PARALLEL_JOBS = 2
        private const val EXTRA_URL = "url"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_VARIANT = "variant"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_JOB = "job"
        private const val ACTION_PAUSE = "app.svetlo.PAUSE_DOWNLOAD"
        private const val ACTION_RESUME = "app.svetlo.RESUME_DOWNLOAD"
        private const val ACTION_CANCEL = "app.svetlo.CANCEL_DOWNLOAD"

        // Seeded from time so job notifications of a previous process aren't silently replaced.
        private val nextId = AtomicInteger(((System.currentTimeMillis() / 1000) % 100_000).toInt() * 10)

        /**
         * Queues an HLS or DASH ([kind]) download. [url] is the playlist/MPD as detected; [variantUrl]/[audioUrl]
         * come from [StreamDownloader.variants] when the user picked a quality, otherwise the best one is chosen.
         * [title] becomes the file name (sanitised), falling back to video_yyyyMMdd_HHmmss.
         */
        fun start(
            ctx: Context,
            url: String,
            headers: Map<String, String>,
            title: String? = null,
            variantUrl: String? = null,
            audioUrl: String? = null,
            kind: MediaKind = MediaKind.HLS,
        ) {
            val i = Intent(ctx, HlsDownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_HEADERS, HashMap(headers))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VARIANT, variantUrl)
                .putExtra(EXTRA_AUDIO, audioUrl)
                .putExtra(EXTRA_KIND, kind.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun pause(ctx: Context, jobId: Int, paused: Boolean): Boolean = runCatching {
            ctx.startService(Intent(ctx, HlsDownloadService::class.java).setAction(if (paused) ACTION_PAUSE else ACTION_RESUME).putExtra(EXTRA_JOB, jobId))
        }.isSuccess

        /** Cancels a running/queued job (see [DownloadEntry.jobId]). Returns false if the service couldn't be reached. */
        fun cancel(ctx: Context, jobId: Int): Boolean = runCatching {
            ctx.startService(Intent(ctx, HlsDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB, jobId))
        }.isSuccess

        internal fun fileBase(title: String?, now: Date = Date()): String {
            val clean = title.orEmpty()
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]"""), "_")
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '.', '_')
                .take(80)
                .trimEnd(' ', '.')
            return clean.ifEmpty { "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) }
        }

        internal fun formatProgress(bytes: Long, pct: Int, bytesPerSec: Double): String {
            val mb = String.format(Locale.US, "%.1f МБ", bytes / 1048576.0)
            val speed = when {
                bytesPerSec <= 0 -> null
                bytesPerSec < 1048576 -> String.format(Locale.US, "%.0f КБ/с", bytesPerSec / 1024)
                else -> String.format(Locale.US, "%.1f МБ/с", bytesPerSec / 1048576)
            }
            return listOfNotNull(mb, "$pct%", speed).joinToString(" · ")
        }

        internal fun describe(e: Throwable): String = when (e) {
            is HlsDownloader.HttpException -> when (e.code) {
                401, 403 -> "доступ запрещён (HTTP ${e.code}), ссылка могла устареть — обновите страницу"
                404, 410 -> "файл не найден на сервере (HTTP ${e.code})"
                else -> "ошибка сервера (HTTP ${e.code})"
            }
            is UnknownHostException, is ConnectException -> "нет соединения с сервером"
            is SocketTimeoutException -> "сервер не отвечает"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

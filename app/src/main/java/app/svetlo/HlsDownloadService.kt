package app.svetlo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Foreground service so HLS downloads survive the app going to background. */
class HlsDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>) ?: hashMapOf()

        ensureChannel()
        val note = progressNote("Подготовка…", 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTE_ID, note)
        }
        cancelled = false
        pending.incrementAndGet()
        executor.execute { runJob(url, headers) }
        return START_NOT_STICKY
    }

    private fun runJob(url: String, headers: Map<String, String>) {
        val nm = getSystemService(NotificationManager::class.java)
        var target: OutputTarget? = null
        try {
            val dl = HlsDownloader(headers) { cancelled }
            val playlist = dl.resolve(url)
            val isMp4 = playlist.init != null
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "video_$stamp." + if (isMp4) "mp4" else "ts"
            target = OutputTarget(this, name, if (isMp4) "video/mp4" else "video/mp2t")
            var lastPct = -1
            dl.download(playlist, target.stream) { done, total ->
                val pct = done * 100 / total
                if (pct != lastPct) {
                    lastPct = pct
                    nm.notify(NOTE_ID, progressNote("$name — $pct%", done, total))
                }
            }
            target.commit()
            finishNote("Скачано: Download/Svetlo/$name")
        } catch (e: Exception) {
            target?.abort()
            finishNote(if (cancelled) "Загрузка отменена" else "Ошибка: ${e.message}")
        } finally {
            if (pending.decrementAndGet() == 0) main.post { stopForegroundCompat(); stopSelf() }
        }
    }

    private fun finishNote(text: String) {
        main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % 100000).toInt() + 1000, builder().setContentTitle("Svetlo")
                .setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true).build())
    }

    private fun progressNote(text: String, done: Int, total: Int): Notification {
        val cancel = android.app.PendingIntent.getService(
            this, 0, Intent(this, HlsDownloadService::class.java).setAction(ACTION_CANCEL),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return builder()
            .setContentTitle("Скачивание видео")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .addAction(Notification.Action.Builder(null, "Отмена", cancel).build())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTE_ID = 1
        private const val EXTRA_URL = "url"
        private const val EXTRA_HEADERS = "headers"
        private const val ACTION_CANCEL = "cancel"

        fun start(ctx: Context, url: String, headers: HashMap<String, String>) {
            val i = Intent(ctx, HlsDownloadService::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_HEADERS, headers)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

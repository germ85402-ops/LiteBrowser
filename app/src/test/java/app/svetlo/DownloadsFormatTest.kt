package app.svetlo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DownloadsFormatTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    private val now = at(2026, 9, 26, 18, 5)

    @Test
    fun sizes() {
        assertEquals("0 Б", Downloads.formatSize(0))
        assertEquals("512 Б", Downloads.formatSize(512))
        assertEquals("34 КБ", Downloads.formatSize(34 * 1024 + 100))
        assertEquals("1023 КБ", Downloads.formatSize(1024 * 1024 - 1))
        assertEquals("1 МБ", Downloads.formatSize(1024 * 1024))
        assertEquals("1.2 МБ", Downloads.formatSize((1.2 * 1048576).toLong()))
        assertEquals("12.3 МБ", Downloads.formatSize((12.3 * 1048576).toLong()))
        assertEquals("27 МБ", Downloads.formatSize(27L * 1048576))
        assertEquals("1.5 ГБ", Downloads.formatSize((1.5 * 1073741824).toLong()))
    }

    @Test
    fun speeds() {
        assertEquals("", Downloads.formatSpeed(0.0))
        assertEquals("1 КБ/с", Downloads.formatSpeed(10.0))
        assertEquals("850 КБ/с", Downloads.formatSpeed(850 * 1024.0))
        assertEquals("1.2 МБ/с", Downloads.formatSpeed(1.2 * 1048576))
    }

    @Test
    fun dates() {
        assertEquals("14:20", Downloads.formatWhen(at(2026, 9, 26, 14, 20), now))
        assertEquals("9:05", Downloads.formatWhen(at(2026, 9, 26, 9, 5), now))
        assertEquals("вчера 14:20", Downloads.formatWhen(at(2026, 9, 25, 14, 20), now))
        assertEquals("3 мар 8:00", Downloads.formatWhen(at(2026, 3, 3, 8, 0), now))
        assertEquals("31 дек 2025", Downloads.formatWhen(at(2025, 12, 31, 23, 0), now))
        assertEquals("вчера 23:00", Downloads.formatWhen(at(2025, 12, 31, 23, 0), at(2026, 1, 1, 10, 0)))
    }

    private fun entry(status: DownloadStatus, bytes: Long = 0, total: Long = 0, pct: Int = 0, speed: Double = 0.0, msg: String? = null) =
        DownloadEntry("x", "clip.mp4", "https://e.com/clip.mp4", status, at(2026, 9, 25, 14, 20),
            bytes = bytes, total = total, percent = pct, speed = speed, message = msg)

    @Test
    fun statusLines() {
        val mb = 1048576L
        assertEquals("45% · 12.3 МБ из 27 МБ · 1.2 МБ/с",
            Downloads.statusLine(entry(DownloadStatus.RUNNING, (12.3 * mb).toLong(), 27 * mb, speed = 1.2 * mb), now))
        assertEquals("Ожидание сети · 3 МБ из 27 МБ",
            Downloads.statusLine(entry(DownloadStatus.RUNNING, 3 * mb, 27 * mb, speed = 5000.0, msg = "Ожидание сети"), now))
        assertEquals("30% · 5 МБ", Downloads.statusLine(entry(DownloadStatus.RUNNING, 5 * mb, pct = 30), now))
        assertEquals("Загрузка…", Downloads.statusLine(entry(DownloadStatus.RUNNING), now))
        assertEquals("В очереди", Downloads.statusLine(entry(DownloadStatus.QUEUED), now))
        assertEquals("Готово · 27 МБ · вчера 14:20", Downloads.statusLine(entry(DownloadStatus.DONE, 27 * mb, 27 * mb), now))
        assertEquals("Готово · вчера 14:20", Downloads.statusLine(entry(DownloadStatus.DONE), now))
        assertEquals("Ошибка: обрыв соединения", Downloads.statusLine(entry(DownloadStatus.FAILED, msg = "обрыв соединения"), now))
        assertEquals("Ошибка загрузки", Downloads.statusLine(entry(DownloadStatus.FAILED), now))
        assertEquals("Отменено", Downloads.statusLine(entry(DownloadStatus.CANCELLED), now))
        assertEquals(null, Downloads.progressPercent(entry(DownloadStatus.RUNNING)))
    }

    @Test
    fun kinds() {
        assertEquals(Downloads.Kind.VIDEO, Downloads.kind("a.ts", null))
        assertEquals(Downloads.Kind.VIDEO, Downloads.kind("a", "video/mp4"))
        assertEquals(Downloads.Kind.AUDIO, Downloads.kind("song.MP3", null))
        assertEquals(Downloads.Kind.IMAGE, Downloads.kind("x.bin", "image/png"))
        assertEquals(Downloads.Kind.DOC, Downloads.kind("report.pdf", null))
        assertEquals(Downloads.Kind.OTHER, Downloads.kind("app.apk", "application/vnd.android.package-archive"))
    }
}

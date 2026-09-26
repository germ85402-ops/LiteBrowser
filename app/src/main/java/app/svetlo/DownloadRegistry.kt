package app.svetlo

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * One row of the in-app download list. Downloads handled by the system DownloadManager keep only
 * [systemId]; their live state is read from DownloadManager when the list is shown.
 */
data class DownloadEntry(
    val id: String,
    val name: String,
    val source: String,
    val status: DownloadStatus,
    val createdAt: Long,
    val mime: String? = null,
    val contentUri: String? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val percent: Int = 0,
    val speed: Double = 0.0,
    val message: String? = null,
    val systemId: Long = -1,
    /** Service job id used to cancel a running stream download, -1 if not cancellable. */
    val jobId: Int = -1,
    /** Separate files belonging to this entry, e.g. an audio track saved next to the video. */
    val extraUris: List<String> = emptyList(),
) {
    val active get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("source", source).put("status", status.name).put("at", createdAt)
        .put("mime", mime).put("uri", contentUri).put("bytes", bytes).put("total", total).put("pct", percent)
        .put("msg", message).put("sys", systemId).put("extra", JSONArray(extraUris))

    companion object {
        fun fromJson(o: JSONObject): DownloadEntry {
            // A process death leaves running stream jobs without a worker; they can't resume.
            val saved = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.FAILED)
            val status = if (saved == DownloadStatus.QUEUED || saved == DownloadStatus.RUNNING) DownloadStatus.FAILED else saved
            val extra = o.optJSONArray("extra")
            return DownloadEntry(
                id = o.getString("id"), name = o.optString("name"), source = o.optString("source"),
                status = status, createdAt = o.optLong("at"),
                mime = o.optString("mime").ifEmpty { null }.takeIf { !o.isNull("mime") },
                contentUri = o.optString("uri").ifEmpty { null }.takeIf { !o.isNull("uri") },
                bytes = o.optLong("bytes"), total = o.optLong("total"), percent = o.optInt("pct"),
                message = if (status != saved) "Загрузка прервана" else o.optString("msg").ifEmpty { null }.takeIf { !o.isNull("msg") },
                systemId = o.optLong("sys", -1),
                extraUris = List(extra?.length() ?: 0) { extra!!.getString(it) },
            )
        }
    }
}

/** Process-wide list of downloads, persisted to a small JSON file. Thread-safe; listeners run on the main thread. */
object DownloadRegistry {
    private const val MAX = 200
    private val entries = LinkedHashMap<String, DownloadEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null
    private var notifyPosted = false
    private var lastSave = 0L

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "downloads.json")
        file = f
        runCatching {
            if (!f.exists()) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) DownloadEntry.fromJson(arr.getJSONObject(i)).let { entries[it.id] = it }
        }
    }

    @Synchronized
    fun list(): List<DownloadEntry> = entries.values.sortedByDescending { it.createdAt }

    @Synchronized
    fun get(id: String): DownloadEntry? = entries[id]

    /** Adds or replaces an entry. Frequent progress updates are persisted at most once a second. */
    fun put(e: DownloadEntry) {
        synchronized(this) {
            entries[e.id] = e
            if (entries.size > MAX) {
                entries.values.filter { !it.active }.sortedBy { it.createdAt }.take(entries.size - MAX).forEach { entries.remove(it.id) }
            }
        }
        save(force = !e.active)
        changed()
    }

    fun update(id: String, f: (DownloadEntry) -> DownloadEntry) {
        val cur = get(id) ?: return
        put(f(cur))
    }

    fun remove(id: String) {
        synchronized(this) { entries.remove(id) }
        save(force = true)
        changed()
    }

    fun clearFinished() {
        synchronized(this) { entries.values.filter { !it.active }.map { it.id }.forEach { entries.remove(it) } }
        save(force = true)
        changed()
    }

    fun addListener(l: () -> Unit) { listeners += l }

    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun changed() {
        synchronized(this) { if (notifyPosted) return; notifyPosted = true }
        main.postDelayed({
            synchronized(this) { notifyPosted = false }
            listeners.forEach { it() }
        }, 100)
    }

    private fun save(force: Boolean) {
        val f = file ?: return
        val text = synchronized(this) {
            val now = System.currentTimeMillis()
            if (!force && now - lastSave < 1000) return
            lastSave = now
            JSONArray().also { a -> entries.values.forEach { a.put(it.toJson()) } }.toString()
        }
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        }
    }
}

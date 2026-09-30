package app.svetlo

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

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
    val active get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("source", source).put("status", status.name).put("at", createdAt)
        .put("mime", mime).put("uri", contentUri).put("bytes", bytes).put("total", total).put("pct", percent)
        .put("msg", message).put("sys", systemId).put("job", jobId).put("extra", JSONArray(extraUris))

    companion object {
        fun fromJson(o: JSONObject, interrupted: Boolean = true): DownloadEntry {
            // A process death leaves running stream jobs without a worker; they can't resume.
            val saved = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.FAILED)
            val status = if (interrupted && o.optLong("sys", -1) < 0 && (saved == DownloadStatus.QUEUED || saved == DownloadStatus.RUNNING || saved == DownloadStatus.PAUSED)) DownloadStatus.FAILED else saved
            val extra = o.optJSONArray("extra")
            return DownloadEntry(
                id = o.getString("id"), name = o.optString("name"), source = o.optString("source"),
                status = status, createdAt = o.optLong("at"),
                mime = o.optString("mime").ifEmpty { null }.takeIf { !o.isNull("mime") },
                contentUri = o.optString("uri").ifEmpty { null }.takeIf { !o.isNull("uri") },
                bytes = o.optLong("bytes"), total = o.optLong("total"), percent = o.optInt("pct"),
                message = if (status != saved) "Загрузка прервана" else o.optString("msg").ifEmpty { null }.takeIf { !o.isNull("msg") },
                systemId = o.optLong("sys", -1), jobId = o.optInt("job", -1),
                extraUris = List(extra?.length() ?: 0) { extra!!.getString(it) },
            )
        }
    }
}

/** Process-wide list of downloads, persisted to a small JSON file. Thread-safe; listeners run on the main thread. */
object DownloadRegistry {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var helper: android.database.sqlite.SQLiteOpenHelper
    private var initialized = false
    private var notifyPosted = false
    private val db get() = helper.writableDatabase

    @Synchronized fun init(ctx: Context) {
        if (initialized) return
        helper = object : android.database.sqlite.SQLiteOpenHelper(ctx.applicationContext, "downloads.db", null, 1) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY, payload TEXT NOT NULL, active INTEGER NOT NULL, created INTEGER NOT NULL)")
            }
            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, old: Int, new: Int) = Unit
        }
        helper.setWriteAheadLoggingEnabled(true)
        val database = db
        database.beginTransaction()
        try {
            val legacy = File(ctx.filesDir, "downloads.json")
            if (legacy.exists() && android.database.DatabaseUtils.queryNumEntries(database, "entries") == 0L) {
                runCatching {
                    val array = JSONArray(legacy.readText())
                    for (i in 0 until array.length()) write(DownloadEntry.fromJson(array.getJSONObject(i)))
                }
            }
            // Only startup of the main process reconciles jobs whose workers died.
            val process = Incognito.processName(ctx.applicationContext as android.app.Application)
            if (!process.endsWith(":incognito")) {
                list().filter { it.active && it.id.startsWith("svc-") }.forEach { write(it.copy(status = DownloadStatus.FAILED, message = "Загрузка прервана", jobId = -1)) }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        initialized = true
    }

    fun list(): List<DownloadEntry> = db.rawQuery("SELECT payload FROM entries ORDER BY created DESC", null).use { c ->
        val result = ArrayList<DownloadEntry>()
        while (c.moveToNext()) runCatching { DownloadEntry.fromJson(JSONObject(c.getString(0)), interrupted = false) }.getOrNull()?.let(result::add)
        result
    }
    fun get(id: String): DownloadEntry? = db.rawQuery("SELECT payload FROM entries WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) DownloadEntry.fromJson(JSONObject(c.getString(0)), interrupted = false) else null
    }
    private fun write(e: DownloadEntry) {
        val values = android.content.ContentValues().apply {
            put("id", e.id); put("payload", e.toJson().toString()); put("active", if (e.active) 1 else 0); put("created", e.createdAt)
        }
        db.insertWithOnConflict("entries", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun put(e: DownloadEntry) {
        db.beginTransaction()
        try {
            write(e)
            db.execSQL("DELETE FROM entries WHERE active=0 AND id NOT IN (SELECT id FROM entries ORDER BY created DESC LIMIT 200)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
    fun update(id: String, f: (DownloadEntry) -> DownloadEntry) {
        db.beginTransaction()
        try {
            get(id)?.let { write(f(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
    fun remove(id: String) { db.delete("entries", "id=?", arrayOf(id)); changed() }
    fun clearFinished() { db.delete("entries", "active=0", null); changed() }
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() {
        synchronized(this) { if (notifyPosted) return; notifyPosted = true }
        main.postDelayed({
            synchronized(this) { notifyPosted = false }
            listeners.forEach { it() }
        }, 100)
    }
}

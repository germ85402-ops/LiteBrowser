package com.litebrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.util.concurrent.Executors

data class Entry(val url: String, val title: String, val time: Long, val bookmark: Boolean = false)

object BrowserDb {
    private lateinit var helper: SQLiteOpenHelper
    private val writer = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        helper = object : SQLiteOpenHelper(ctx, "browser.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY, title TEXT, visits INTEGER, last INTEGER)")
                db.execSQL("CREATE INDEX history_last ON history(last)")
                db.execSQL("CREATE TABLE bookmarks(url TEXT PRIMARY KEY, title TEXT, created INTEGER)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    }

    private val db get() = helper.writableDatabase

    private fun recordable(url: String) = url.startsWith("http://") || url.startsWith("https://")

    fun addVisit(url: String, title: String?) {
        if (!recordable(url)) return
        writer.execute {
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("url", url); put("title", title ?: ""); put("visits", 1); put("last", now)
            }
            if (db.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                db.execSQL(
                    "UPDATE history SET visits = visits + 1, last = ?, title = CASE WHEN ? <> '' THEN ? ELSE title END WHERE url = ?",
                    arrayOf(now, title ?: "", title ?: "", url),
                )
            }
        }
    }

    fun updateTitle(url: String, title: String) {
        if (!recordable(url) || title.isBlank()) return
        writer.execute {
            db.execSQL("UPDATE history SET title = ? WHERE url = ?", arrayOf(title, url))
            db.execSQL("UPDATE bookmarks SET title = ? WHERE url = ? AND title = ''", arrayOf(title, url))
        }
    }

    private fun query(sql: String, args: Array<String>, bookmark: Boolean = false): List<Entry> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Entry>(c.count)
            while (c.moveToNext()) out += Entry(c.getString(0), c.getString(1) ?: "", c.getLong(2), bookmark)
            out
        }

    private fun like(q: String) = "%" + q.replace("%", "").replace("_", "") + "%"

    fun history(filter: String = "", limit: Int = 1000) = query(
        "SELECT url, title, last FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY last DESC LIMIT $limit",
        arrayOf(like(filter), like(filter)),
    )

    fun bookmarks(filter: String = "") = query(
        "SELECT url, title, created FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY created DESC",
        arrayOf(like(filter), like(filter)), bookmark = true,
    )

    /** Omnibox matches: bookmarks first, then most visited history. */
    fun search(q: String, limit: Int): List<Entry> {
        val b = query(
            "SELECT url, title, created FROM bookmarks WHERE url LIKE ? OR title LIKE ? LIMIT $limit",
            arrayOf(like(q), like(q)), bookmark = true,
        )
        val h = query(
            "SELECT url, title, last FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visits DESC, last DESC LIMIT $limit",
            arrayOf(like(q), like(q)),
        )
        return (b + h).distinctBy { it.url }.take(limit)
    }

    /** Most visited sites (at least 2 visits), one entry per host. */
    fun topSites(limit: Int, hidden: Set<String>): List<Entry> {
        val rows = query("SELECT url, title, visits FROM history ORDER BY visits DESC, last DESC LIMIT 200", emptyArray())
        val seen = HashSet<String>()
        return rows.filter { e ->
            val host = Uri.parse(e.url).host?.removePrefix("www.") ?: return@filter false
            e.time >= 2 && host !in hidden && seen.add(host)
        }.take(limit)
    }

    fun deleteHistory(url: String) = writer.execute { db.delete("history", "url = ?", arrayOf(url)) }
    fun clearHistory() = writer.execute { db.delete("history", null, null) }

    fun isBookmarked(url: String) =
        db.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url)).use { it.count > 0 }

    fun addBookmark(url: String, title: String) {
        val cv = ContentValues().apply { put("url", url); put("title", title); put("created", System.currentTimeMillis()) }
        db.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(url: String) = db.delete("bookmarks", "url = ?", arrayOf(url))
}

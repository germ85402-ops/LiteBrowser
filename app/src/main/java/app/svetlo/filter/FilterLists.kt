package app.svetlo.filter

import android.content.Context
import app.svetlo.data.Prefs
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Filter list subscriptions: bundled, built-in downloadable and user-added. */
object FilterLists {
    class Sub(val id: String, val title: String, val url: String, val defaultOn: Boolean, val custom: Boolean = false)

    private const val ASSET = "adhosts.txt"
    private const val UPDATE_INTERVAL_MS = 4L * 24 * 3600 * 1000
    const val PGL = "pgl"

    private val builtin = listOf(
        Sub("ruadlist", "EasyList + RuAdList", "https://easylist-downloads.adblockplus.org/ruadlist+easylist.txt", true),
        Sub("easyprivacy", "EasyPrivacy — трекеры и аналитика", "https://easylist.to/easylist/easyprivacy.txt", true),
        Sub(PGL, "Peter Lowe — рекламные домены", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext", true),
        Sub("adguard_ru", "AdGuard Русский фильтр", "https://filters.adtidy.org/extension/ublock/filters/1.txt", false),
        Sub("adguard_annoy", "AdGuard Раздражители (cookie-баннеры, попапы)", "https://filters.adtidy.org/extension/ublock/filters/14.txt", false),
    )

    fun all(): List<Sub> = builtin + custom()

    private fun custom(): List<Sub> {
        val arr = JSONArray(Prefs.sp.getString("custom_lists", "[]"))
        return (0 until arr.length()).map { i ->
            val url = arr.getString(i)
            Sub("c_" + Integer.toHexString(url.hashCode()), url.substringAfter("://").take(60), url, true, custom = true)
        }
    }

    fun addCustom(url: String) {
        val arr = JSONArray(Prefs.sp.getString("custom_lists", "[]"))
        if ((0 until arr.length()).none { arr.getString(it) == url }) arr.put(url)
        Prefs.sp.edit().putString("custom_lists", arr.toString()).apply()
    }

    fun removeCustom(ctx: Context, sub: Sub) {
        val arr = JSONArray(Prefs.sp.getString("custom_lists", "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getString(i) != sub.url) out.put(arr.getString(i))
        Prefs.sp.edit().putString("custom_lists", out.toString()).apply()
        file(ctx, sub).delete()
    }

    fun isEnabled(s: Sub) = Prefs.sp.getBoolean("fl_on_${s.id}", s.defaultOn)
    fun setEnabled(s: Sub, on: Boolean) = Prefs.sp.edit().putBoolean("fl_on_${s.id}", on).apply()
    fun updatedAt(s: Sub) = Prefs.sp.getLong("fl_upd_${s.id}", 0)
    fun ruleCount(s: Sub) = Prefs.sp.getInt("fl_rules_${s.id}", 0)
    fun isDownloaded(ctx: Context, s: Sub) = file(ctx, s).exists() || s.id == PGL

    private fun file(ctx: Context, s: Sub) = File(File(ctx.filesDir, "filters").apply { mkdirs() }, "${s.id}.txt")
    fun userRulesFile(ctx: Context) = File(ctx.filesDir, "user_rules.txt")

    fun buildEngine(ctx: Context): FilterEngine {
        FilterEngine.loadPublicSuffix(ctx.assets.open("public_suffix_list.dat").bufferedReader().use { it.readText() })
        val e = FilterEngine()
        val edit = Prefs.sp.edit()
        for (s in all()) {
            if (!isEnabled(s)) continue
            val f = file(ctx, s)
            val text = when {
                f.exists() -> runCatching { f.readText() }.getOrNull()
                s.id == PGL -> ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
                else -> null
            } ?: continue
            val before = e.ruleCount
            e.add(text)
            edit.putInt("fl_rules_${s.id}", e.ruleCount - before)
        }
        edit.apply()
        userRulesFile(ctx).takeIf { it.exists() }?.let { e.add(it.readText()) }
        return e.freeze()
    }

    fun needsUpdate(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        return all().any { isEnabled(it) && (!file(ctx, it).exists() && it.id != PGL || now - updatedAt(it) > UPDATE_INTERVAL_MS) }
    }

    /** Downloads enabled lists; returns the number of lists that failed. */
    fun updateAll(ctx: Context, force: Boolean): Int {
        val now = System.currentTimeMillis()
        var failed = 0
        for (s in all()) {
            if (!isEnabled(s)) continue
            if (!force && file(ctx, s).exists() && now - updatedAt(s) < UPDATE_INTERVAL_MS) continue
            if (!download(ctx, s)) failed++
        }
        return failed
    }

    fun download(ctx: Context, s: Sub): Boolean = runCatching {
        val conn = URL(s.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Svetlo/1.0")
        try {
            require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val text = conn.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    require(out.length + n <= 12 * 1024 * 1024) { "filter list too large" }
                    out.append(buffer, 0, n)
                }
                out.toString()
            }
            require(text.lineSequence().count() > 20 && !text.trimStart().startsWith("<")) { "not a filter list" }
            val f = file(ctx, s)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            require(tmp.renameTo(f))
            Prefs.sp.edit().putLong("fl_upd_${s.id}", System.currentTimeMillis()).apply()
        } finally {
            conn.disconnect()
        }
    }.isSuccess
}

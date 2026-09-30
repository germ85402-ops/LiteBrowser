package app.svetlo

import app.svetlo.data.BrowserDb
import app.svetlo.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** User-controlled export contains bookmarks and preferences, never cookies or permission grants. */
object Backup {
    private val bools = setOf("adblock", "suggestions", "bottom_bar", "auto_hide_bar", "pull_to_refresh", "dark_pages", "block_3p_cookies", "javascript", "reader_serif", "pip")
    private val ints = setOf("text_zoom", "reader_font", "reader_theme")
    private val strings = setOf("search_engine", "custom_search")
    fun export(): String {
        val bookmarks = JSONArray()
        BrowserDb.bookmarks().forEach { bookmarks.put(JSONObject().put("url", it.url).put("title", it.title)) }
        val settings = JSONObject()
        Prefs.sp.all.filterKeys { it in bools || it in ints || it in strings }.forEach { (k, v) -> settings.put(k, v) }
        return JSONObject().put("format", "svetlo-backup").put("version", 1).put("bookmarks", bookmarks).put("settings", settings).toString(2)
    }
    fun read(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= 4 * 1024 * 1024) { "Файл слишком большой" }
            out.write(buffer, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
    fun validate(text: String): String {
        val o = JSONObject(text)
        require(o.optString("format") == "svetlo-backup" && o.optInt("version") == 1) { "Неизвестный формат копии" }
        val b = o.getJSONArray("bookmarks")
        require(b.length() <= 10000) { "Слишком много закладок" }
        for (i in 0 until b.length()) {
            val entry = b.getJSONObject(i)
            require(entry.getString("url").length <= 8192 && Origin.of(entry.getString("url")) != null) { "Неверный адрес закладки" }
            require(entry.optString("title").length <= 4096) { "Слишком длинный заголовок" }
        }
        val settings = o.optJSONObject("settings") ?: JSONObject()
        for (key in settings.keys()) {
            val value = settings.get(key)
            if (key in bools) require(value is Boolean)
            if (key in ints) require(value is Number)
            if (key in strings) require(value is String && value.length < 8192)
            if (key == "custom_search" && value is String) require(value.isBlank() || Prefs.validCustomSearch(value))
        }
        return text
    }
    fun restore(text: String) {
        val o = JSONObject(validate(text))
        val b = o.getJSONArray("bookmarks")
        BrowserDb.importBookmarks((0 until b.length()).map { b.getJSONObject(it).let { row -> row.getString("url") to row.optString("title") } })
        val settings = o.optJSONObject("settings") ?: JSONObject()
        val edit = Prefs.sp.edit()
        for (k in settings.keys()) {
            when {
                k in bools -> edit.putBoolean(k, settings.getBoolean(k))
                k == "text_zoom" -> edit.putInt(k, settings.getInt(k).coerceIn(80, 175))
                k == "reader_font" -> edit.putInt(k, settings.getInt(k).coerceIn(14, 30))
                k == "reader_theme" -> edit.putInt(k, settings.getInt(k).coerceIn(-1, 2))
                k in strings -> edit.putString(k, settings.getString(k))
            }
        }
        check(edit.commit())
    }
}

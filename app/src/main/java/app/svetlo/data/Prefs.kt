package app.svetlo.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

enum class SearchEngine(val title: String, private val search: String, private val suggest: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"),
    YANDEX("Яндекс", "https://yandex.ru/search/?text=%s", "https://suggest.yandex.ru/suggest-ff.cgi?part=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/ac/?type=list&q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://api.bing.com/osjson.aspx?query=%s");

    fun searchUrl(q: String) = search.replace("%s", Uri.encode(q))
    fun suggestUrl(q: String) = suggest.replace("%s", Uri.encode(q))
}

object Prefs {
    lateinit var sp: SharedPreferences
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun put(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var adblock: Boolean
        get() = bool("adblock", true)
        set(v) = put("adblock", v)
    var suggestions: Boolean
        get() = bool("suggestions", true)
        set(v) = put("suggestions", v)
    var bottomBar: Boolean
        get() = bool("bottom_bar", false)
        set(v) = put("bottom_bar", v)
    var autoHideBar: Boolean
        get() = bool("auto_hide_bar", true)
        set(v) = put("auto_hide_bar", v)
    var pullToRefresh: Boolean
        get() = bool("pull_to_refresh", true)
        set(v) = put("pull_to_refresh", v)
    var darkPages: Boolean
        get() = bool("dark_pages", true)
        set(v) = put("dark_pages", v)
    var blockThirdPartyCookies: Boolean
        get() = bool("block_3p_cookies", true)
        set(v) = put("block_3p_cookies", v)
    var javascript: Boolean
        get() = bool("javascript", true)
        set(v) = put("javascript", v)
    var textZoom: Int
        get() = sp.getInt("text_zoom", 100)
        set(v) = sp.edit().putInt("text_zoom", v).apply()
    var searchEngine: SearchEngine
        get() = runCatching { SearchEngine.valueOf(sp.getString("search_engine", null)!!) }.getOrDefault(SearchEngine.GOOGLE)
        set(v) = sp.edit().putString("search_engine", v.name).apply()
    var onboarded: Boolean
        get() = bool("onboarded", false)
        set(v) = put("onboarded", v)

    private val HOST_LIKE = Regex("""^([a-z0-9-]+\.)+[a-z]{2,}(:\d+)?(/.*)?$|^localhost(:\d+)?(/.*)?$|^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)

    fun looksLikeUrl(input: String): Boolean {
        val t = input.trim()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") ||
            !t.contains(' ') && HOST_LIKE.matches(t)
    }

    fun toUrl(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
            looksLikeUrl(t) -> "https://$t"
            else -> searchEngine.searchUrl(t)
        }
    }
}

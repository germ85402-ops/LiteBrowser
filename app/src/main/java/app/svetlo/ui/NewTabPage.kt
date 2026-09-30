package app.svetlo.ui

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.svetlo.R
import app.svetlo.data.BrowserDb
import app.svetlo.data.Prefs
import app.svetlo.filter.AdBlock
import org.json.JSONArray
import java.text.NumberFormat

/** Native start page: search pill, speed dial and ad-blocking stats. */
class NewTabPage(
    private val act: Activity,
    val view: View,
    private val onOpen: (String) -> Unit,
    onSearch: () -> Unit,
    private val incognito: Boolean = false,
) {
    private val tiles = view.findViewById<GridLayout>(R.id.ntpTiles)
    private val stats = view.findViewById<TextView>(R.id.ntpStatsCount)

    init {
        val logo = SpannableString("Svetlo")
        // "Svet" = light: the brand's meaningful root gets the accent.
        logo.setSpan(ForegroundColorSpan(act.color(R.color.c_accent)), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.findViewById<TextView>(R.id.ntpLogo).text = logo
        view.findViewById<View>(R.id.ntpSearch).setOnClickListener { onSearch() }
        view.findViewById<View>(R.id.ntpStats).setOnClickListener {
            act.startActivity(android.content.Intent(act, AdblockActivity::class.java))
        }
        if (incognito) {
            view.findViewById<View>(R.id.ntpIncognito).visibility = View.VISIBLE
            tiles.visibility = View.GONE
            view.findViewById<View>(R.id.ntpSitesTitle).visibility = View.GONE
            view.findViewById<View>(R.id.ntpStats).visibility = View.GONE
            view.findViewById<TextView>(R.id.ntpIncognitoText).text = if (app.svetlo.Incognito.isolated) {
                act.getString(app.svetlo.R.string.label_0ae8d8f423) +
                    act.getString(app.svetlo.R.string.label_17ac6760bf) +
                    act.getString(app.svetlo.R.string.label_82744d0f16)
            } else {
                act.getString(app.svetlo.R.string.label_ec102be15f)
            }
        }
    }

    private fun hidden(): Set<String> = Prefs.sp.getStringSet("ntp_hidden", emptySet())!!

    fun refresh() {
        if (incognito) return
        stats.text = NumberFormat.getIntegerInstance().format(AdBlock.totalBlocked.get())
        tiles.removeAllViews()
        val hidden = hidden()
        val sites = LinkedHashMap<String, Pair<String, String>>() // host -> (title, url)
        val saved = pinned()
        saved.forEach { url ->
            val host = hostOf(url)
            if (host !in hidden) sites.putIfAbsent(host, shortTitle("", url) to url)
        }
        // Keep one slot for the visible add shortcut. A full set of saved sites can use all eight.
        val siteLimit = MAX_SITE_TILES - if (saved.size < MAX_SITE_TILES) 1 else 0
        val autoLimit = (siteLimit - sites.size).coerceAtLeast(0)
        BrowserDb.topSites(autoLimit, hidden + sites.keys).forEach { e ->
            sites.putIfAbsent(hostOf(e.url), shortTitle(e.title, e.url) to e.url)
        }
        DEFAULTS.forEach { (title, url) ->
            val h = hostOf(url)
            if (sites.size < siteLimit && h !in hidden && h !in sites) sites[h] = title to url
        }
        sites.forEach { (host, v) -> tiles.addView(tile(host, v.first, v.second)) }
        if (saved.size < MAX_SITE_TILES) tiles.addView(addShortcutTile())
    }

    private fun pinned(): List<String> = runCatching {
        val array = JSONArray(Prefs.sp.getString(PINNED_KEY, "[]"))
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { url ->
                val uri = Uri.parse(url)
                uri.scheme in WEB_SCHEMES && !uri.host.isNullOrBlank()
            }
        }.distinctBy(::hostOf).take(MAX_SITE_TILES)
    }.getOrDefault(emptyList())

    private fun savePinned(urls: List<String>) {
        val array = JSONArray()
        urls.take(MAX_SITE_TILES).forEach { array.put(it) }
        Prefs.sp.edit().putString(PINNED_KEY, array.toString()).apply()
    }

    private fun shortTitle(title: String, url: String): String {
        val t = title.split(" - ", " — ", " | ", ": ").firstOrNull()?.trim().orEmpty()
        return if (t.isEmpty() || t.length > 18) hostOf(url).substringBefore('.').replaceFirstChar { it.uppercase() } else t
    }

    private fun tile(host: String, title: String, url: String): View {
        val v = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, act.dp(10), 0, act.dp(10))
            background = act.themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
            contentDescription = title
            isFocusable = true
        }
        v.addView(ImageView(act).apply { setImageDrawable(LetterIcon(url, title)) }, LinearLayout.LayoutParams(act.dp(52), act.dp(52)))
        v.addView(TextView(act).apply {
            text = title
            textSize = 12f
            setTextColor(act.color(R.color.c_text))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(act.dp(4), act.dp(8), act.dp(4), 0)
        })
        v.setOnClickListener { onOpen(url) }
        v.setOnLongClickListener {
            AlertDialog.Builder(act).setTitle(title).setItems(arrayOf(act.getString(app.svetlo.R.string.label_34624db8cf))) { _, _ ->
                if (pinned().any { hostOf(it) == host }) savePinned(pinned().filterNot { hostOf(it) == host })
                else Prefs.sp.edit().putStringSet("ntp_hidden", hidden() + host).apply()
                refresh()
            }.show()
            true
        }
        v.layoutParams = tileLayoutParams()
        return v
    }

    private fun addShortcutTile(): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, act.dp(10), 0, act.dp(10))
        background = act.themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
        contentDescription = act.getString(app.svetlo.R.string.label_d8702293cc)
        isFocusable = true
        addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = android.content.res.ColorStateList.valueOf(act.color(R.color.c_accent))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(act.color(R.color.c_accent_soft))
            }
            setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
        }, LinearLayout.LayoutParams(act.dp(52), act.dp(52)))
        addView(TextView(act).apply {
            text = act.getString(R.string.add_site)
            textSize = 12f
            setTextColor(act.color(R.color.c_accent))
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(act.dp(4), act.dp(8), act.dp(4), 0)
        })
        setOnClickListener { showAddShortcutDialog() }
        layoutParams = tileLayoutParams()
    }

    private fun tileLayoutParams() = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
        ).apply { width = 0 }

    private fun showAddShortcutDialog() {
        val input = EditText(act).apply {
            hint = "example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
        }
        val dialog = AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.add_site))
            .setMessage(act.getString(app.svetlo.R.string.label_d1dd3bea02))
            .setView(input)
            .setNegativeButton(act.getString(app.svetlo.R.string.label_0ec753be8d), null)
            .setPositiveButton(act.getString(app.svetlo.R.string.label_559a87f7cc), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            if (!Prefs.looksLikeUrl(text)) {
                input.error = act.getString(app.svetlo.R.string.label_4882aa0535)
                return@setOnClickListener
            }
            val url = Prefs.toUrl(text)
            val uri = Uri.parse(url)
            val host = uri.host?.let(::hostOf)
            if (uri.scheme !in WEB_SCHEMES || host.isNullOrBlank()) {
                input.error = act.getString(app.svetlo.R.string.label_af8ef203ba)
                return@setOnClickListener
            }
            if (pinned().any { hostOf(it) == host } || host in currentHosts()) {
                input.error = act.getString(app.svetlo.R.string.label_00c4063bf3)
                return@setOnClickListener
            }
            savePinned(pinned() + url)
            Prefs.sp.edit().putStringSet("ntp_hidden", hidden() - host).apply()
            dialog.dismiss()
            refresh()
        }
    }

    private fun currentHosts(): Set<String> {
        val hidden = hidden()
        val saved = pinned()
        val result = saved.map { hostOf(it) }.filterNot { it in hidden }.toCollection(LinkedHashSet())
        val siteLimit = MAX_SITE_TILES - if (saved.size < MAX_SITE_TILES) 1 else 0
        val autoLimit = (siteLimit - result.size).coerceAtLeast(0)
        BrowserDb.topSites(autoLimit, hidden + result).forEach { result += hostOf(it.url) }
        DEFAULTS.forEach { (title, url) ->
            val host = hostOf(url)
            if (result.size < siteLimit && host !in hidden) result += host
        }
        return result
    }

    companion object {
        private const val MAX_SITE_TILES = 8
        private const val PINNED_KEY = "ntp_pinned"
        private val WEB_SCHEMES = setOf("http", "https")
        private val DEFAULTS = listOf(
            "Яндекс" to "https://ya.ru/",
            "YouTube" to "https://m.youtube.com/",
            "ВКонтакте" to "https://vk.com/",
            "Википедия" to "https://ru.wikipedia.org/",
            "Дзен" to "https://dzen.ru/",
            "Ozon" to "https://www.ozon.ru/",
            "Авито" to "https://www.avito.ru/",
            "GitHub" to "https://github.com/",
        )
    }
}

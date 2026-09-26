package com.litebrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.litebrowser.R
import com.litebrowser.data.BrowserDb
import com.litebrowser.data.Prefs
import com.litebrowser.filter.AdBlock
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
        val logo = SpannableString("LiteBrowser")
        logo.setSpan(ForegroundColorSpan(act.color(R.color.c_accent)), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.findViewById<TextView>(R.id.ntpLogo).text = logo
        view.findViewById<View>(R.id.ntpSearch).setOnClickListener { onSearch() }
        view.findViewById<View>(R.id.ntpStats).setOnClickListener {
            act.startActivity(android.content.Intent(act, AdblockActivity::class.java))
        }
        if (incognito) {
            view.findViewById<View>(R.id.ntpIncognito).visibility = View.VISIBLE
            tiles.visibility = View.GONE
            view.findViewById<View>(R.id.ntpStats).visibility = View.GONE
            view.findViewById<TextView>(R.id.ntpIncognitoText).text = if (com.litebrowser.Incognito.isolated) {
                "Браузер не сохранит историю, cookie, данные сайтов и введённые в формы данные. " +
                    "Всё удалится, когда вы закроете последнюю вкладку инкогнито.\n\n" +
                    "Загруженные файлы и закладки сохранятся. Сайты, провайдер и работодатель по-прежнему могут видеть ваши действия."
            } else {
                "Браузер не сохранит историю посещений и вкладки. На этой версии Android cookie общие с обычными вкладками."
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
        BrowserDb.topSites(8, hidden).forEach { e ->
            sites.putIfAbsent(hostOf(e.url), shortTitle(e.title, e.url) to e.url)
        }
        DEFAULTS.forEach { (title, url) ->
            val h = hostOf(url)
            if (sites.size < 8 && h !in hidden && h !in sites) sites[h] = title to url
        }
        sites.forEach { (host, v) -> tiles.addView(tile(host, v.first, v.second)) }
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
            AlertDialog.Builder(act).setTitle(title).setItems(arrayOf("Убрать с экрана")) { _, _ ->
                Prefs.sp.edit().putStringSet("ntp_hidden", hidden() + host).apply()
                refresh()
            }.show()
            true
        }
        v.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
        ).apply { width = 0 }
        return v
    }

    companion object {
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

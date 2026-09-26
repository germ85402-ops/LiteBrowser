package com.litebrowser.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.litebrowser.R
import com.litebrowser.data.BrowserDb
import com.litebrowser.data.Entry
import com.litebrowser.data.Prefs
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Omnibox dropdown: typed text, bookmarks/history matches and search engine suggestions. */
class Suggestions(
    private val act: Activity,
    private val list: ListView,
    private val onPick: (String) -> Unit,
    private val onFill: (String) -> Unit,
) {
    private enum class Kind { GO, SEARCH, HISTORY, BOOKMARK, CLIPBOARD }
    private class Item(val kind: Kind, val title: String, val sub: String?, val value: String)

    private var items: List<Item> = emptyList()
    private var seq = 0
    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val adapter = Adapter()

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { onPick(it.value) } }
    }

    /** Suggestions for an empty omnibox: copied link and recent pages. */
    fun zeroSuggest(clip: String?, withHistory: Boolean) {
        seq++
        val out = ArrayList<Item>()
        if (clip != null) {
            val url = Prefs.looksLikeUrl(clip)
            out += Item(Kind.CLIPBOARD, if (url) "Скопированная ссылка" else "Скопированный текст", clip, clip)
        }
        if (withHistory) BrowserDb.history(limit = 6).forEach { e -> out += Item(Kind.HISTORY, e.title.ifBlank { e.url }, e.url, e.url) }
        items = out
        adapter.notifyDataSetChanged()
        list.visibility = if (out.isEmpty()) View.GONE else View.VISIBLE
    }

    fun query(text: String, clip: String? = null, withHistory: Boolean = true) {
        val q = text.trim()
        val my = ++seq
        if (q.isEmpty()) { zeroSuggest(clip, withHistory); return }
        val local = BrowserDb.search(q, 4)
        update(q, local, emptyList())
        if (!Prefs.suggestions || Prefs.looksLikeUrl(q)) return
        val url = Prefs.searchEngine.suggestUrl(q)
        net.execute {
            if (my != seq) return@execute
            val remote = fetch(url)
            main.post { if (my == seq && list.visibility == View.VISIBLE) update(q, local, remote) }
        }
    }

    fun hide() {
        seq++
        list.visibility = View.GONE
    }

    private fun update(q: String, local: List<Entry>, remote: List<String>) {
        val out = ArrayList<Item>()
        out += if (Prefs.looksLikeUrl(q)) Item(Kind.GO, q, "Перейти на сайт", Prefs.toUrl(q))
        else Item(Kind.SEARCH, q, "Поиск в ${Prefs.searchEngine.title}", q)
        local.forEach { e ->
            out += Item(if (e.bookmark) Kind.BOOKMARK else Kind.HISTORY, e.title.ifBlank { e.url }, e.url, e.url)
        }
        remote.filter { !it.equals(q, true) }.take(6).forEach { out += Item(Kind.SEARCH, it, null, it) }
        items = out
        adapter.notifyDataSetChanged()
        list.visibility = View.VISIBLE
    }

    private fun fetch(url: String): List<String> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: act.layoutInflater.inflate(R.layout.item_suggestion, parent, false)
            val it = items[position]
            v.findViewById<ImageView>(R.id.sIcon).setImageResource(
                when (it.kind) {
                    Kind.GO -> R.drawable.ic_globe
                    Kind.SEARCH -> R.drawable.ic_search
                    Kind.HISTORY -> R.drawable.ic_history_small
                    Kind.BOOKMARK -> R.drawable.ic_bookmark_small
                    Kind.CLIPBOARD -> R.drawable.ic_clipboard
                },
            )
            v.findViewById<TextView>(R.id.sTitle).text = it.title
            v.findViewById<TextView>(R.id.sSub).apply {
                text = it.sub
                visibility = if (it.sub == null) View.GONE else View.VISIBLE
            }
            v.findViewById<View>(R.id.sFill).apply {
                visibility = if (it.kind == Kind.GO) View.INVISIBLE else View.VISIBLE
                setOnClickListener { _ -> onFill(it.value) }
            }
            return v
        }
    }
}

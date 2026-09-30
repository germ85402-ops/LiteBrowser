package app.svetlo.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import app.svetlo.R
import app.svetlo.data.BrowserDb
import app.svetlo.data.Entry
import app.svetlo.data.Prefs
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Omnibox dropdown: typed text, bookmarks/history matches and search engine suggestions. */
class Suggestions(
    private val act: Activity,
    private val list: ListView,
    private val onPick: (String) -> Unit,
    private val onFill: (String) -> Unit,
    /** Optional deterministic fetcher for tests. The callback becomes false when a query is stale. */
    private val remoteFetcher: ((String, () -> Boolean) -> List<String>)? = null,
) {
    private enum class Kind { GO, SEARCH, HISTORY, BOOKMARK, CLIPBOARD }
    private class Item(val kind: Kind, val title: String, val sub: String?, val value: String)

    private var items: List<Item> = emptyList()
    private val seq = AtomicInteger()
    private val requestLock = Any()
    private val net = Executors.newSingleThreadExecutor { task -> Thread(task, "svetlo-suggestions").apply { isDaemon = true } }
    private val localIo = Executors.newSingleThreadExecutor { task -> Thread(task, "svetlo-local-suggestions").apply { isDaemon = true } }
    private var localTask: Future<*>? = null
    private val main = Handler(Looper.getMainLooper())
    private var pendingRequest: Runnable? = null
    private var requestTask: Future<*>? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val adapter = Adapter()

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { onPick(it.value) } }
    }

    /** Suggestions for an empty omnibox: copied link and recent pages. */
    fun zeroSuggest(clip: String?, withHistory: Boolean) {
        invalidateRemote()
        showZeroSuggest(clip, withHistory)
    }

    private fun showZeroSuggest(clip: String?, withHistory: Boolean) {
        val out = ArrayList<Item>()
        if (clip != null) {
            val url = Prefs.looksLikeUrl(clip)
            out += Item(Kind.CLIPBOARD, if (url) act.getString(app.svetlo.R.string.label_968e81913d) else act.getString(app.svetlo.R.string.label_cce8a296b7), clip, clip)
        }
        if (withHistory) {
            val request = seq.get()
            localTask = localIo.submit {
                val history = runCatching { BrowserDb.history(limit = 6) }.getOrDefault(emptyList())
                main.post {
                    if (seq.get() != request) return@post
                    items = out + history.map { e -> Item(Kind.HISTORY, e.title.ifBlank { e.url }, e.url, e.url) }
                    adapter.notifyDataSetChanged()
                    list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
        items = out
        adapter.notifyDataSetChanged()
        list.visibility = if (out.isEmpty()) View.GONE else View.VISIBLE
    }

    fun query(
        text: String,
        clip: String? = null,
        withHistory: Boolean = true,
        allowRemote: Boolean = true,
    ) {
        val request = invalidateRemote()
        val q = text.trim()
        if (q.isEmpty()) { showZeroSuggest(clip, withHistory); return }
        val local = java.util.concurrent.atomic.AtomicReference<List<Entry>>(emptyList())
        update(q, local.get(), emptyList())
        localTask = localIo.submit {
            val found = runCatching { BrowserDb.search(q, 4, includeHistory = withHistory) }.getOrDefault(emptyList())
            main.post { if (seq.get() == request) { local.set(found); update(q, found, emptyList()) } }
        }
        if (!allowRemote || !Prefs.suggestions || Prefs.customSearch.isNotBlank() || Prefs.looksLikeUrl(q)) return
        val url = Prefs.searchEngine.suggestUrl(q)
        val pending = Runnable {
            pendingRequest = null
            val task = net.submit {
                val isCurrent = { seq.get() == request && !Thread.currentThread().isInterrupted }
                if (!isCurrent()) return@submit
                val remote = remoteFetcher?.invoke(url, isCurrent) ?: fetch(url, request)
                main.post { if (seq.get() == request && list.visibility == View.VISIBLE) update(q, local.get(), remote) }
            }
            synchronized(requestLock) {
                if (seq.get() == request) requestTask = task else task.cancel(true)
            }
        }
        pendingRequest = pending
        main.postDelayed(pending, DEBOUNCE_MS)
    }

    fun hide() {
        invalidateRemote()
        list.visibility = View.GONE
    }

    /** Cancels in-flight work when the owning activity is destroyed. */
    fun close() {
        invalidateRemote()
        net.shutdownNow()
        localIo.shutdownNow()
    }

    private fun invalidateRemote(): Int {
        val request = seq.incrementAndGet()
        localTask?.cancel(true)
        localTask = null
        pendingRequest?.let(main::removeCallbacks)
        pendingRequest = null
        val connection = synchronized(requestLock) {
            requestTask?.cancel(true)
            requestTask = null
            activeConnection.also { activeConnection = null }
        }
        runCatching { connection?.disconnect() }
        return request
    }

    private fun update(q: String, local: List<Entry>, remote: List<String>) {
        val out = ArrayList<Item>()
        out += if (Prefs.looksLikeUrl(q)) Item(Kind.GO, q, act.getString(app.svetlo.R.string.label_940f16ef2e), Prefs.toUrl(q))
        else Item(Kind.SEARCH, q, if (Prefs.customSearch.isNotBlank()) act.getString(app.svetlo.R.string.label_2d2e962e6c) else "Поиск в ${Prefs.searchEngine.title}", q)
        local.forEach { e ->
            out += Item(if (e.bookmark) Kind.BOOKMARK else Kind.HISTORY, e.title.ifBlank { e.url }, e.url, e.url)
        }
        remote.filter { !it.equals(q, true) }.take(6).forEach { out += Item(Kind.SEARCH, it, null, it) }
        items = out
        adapter.notifyDataSetChanged()
        list.visibility = View.VISIBLE
    }

    private fun fetch(url: String, request: Int): List<String> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2500
        conn.readTimeout = 2500
        conn.useCaches = false
        val accepted = synchronized(requestLock) {
            if (seq.get() != request || Thread.currentThread().isInterrupted) false
            else { activeConnection = conn; true }
        }
        if (!accepted) { conn.disconnect(); return emptyList() }
        try {
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    if (seq.get() != request || Thread.currentThread().isInterrupted) return emptyList()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_RESPONSE_BYTES) return emptyList()
                    out.write(buffer, 0, count)
                }
            }
            if (seq.get() != request) return emptyList()
            val arr = JSONArray(String(out.toByteArray(), StandardCharsets.UTF_8)).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            synchronized(requestLock) { if (activeConnection === conn) activeConnection = null }
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

    private companion object {
        const val DEBOUNCE_MS = 180L
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

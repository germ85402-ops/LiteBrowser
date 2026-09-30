package app.svetlo

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Stable, versioned URL history. WebView Bundles stay in memory, never on disk. */
object TabSessions {
    private val io = Executors.newSingleThreadExecutor()
    fun save(ctx: Context, json: String) {
        val dir = ctx.noBackupFilesDir
        io.execute {
            val file = AtomicFile(File(dir, "tabs.json"))
            var out: java.io.FileOutputStream? = null
            try {
                out = file.startWrite()
                out.write(json.toByteArray())
                file.finishWrite(out)
            } catch (_: Exception) { out?.let(file::failWrite) }
        }
    }
    fun read(ctx: Context): JSONObject? = runCatching {
        val file = AtomicFile(File(ctx.noBackupFilesDir, "tabs.json"))
        val result = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        result.takeIf { it.optInt("version") == 1 }
    }.getOrNull()
    fun capture(tab: Tab) {
        val history = tab.web?.copyBackForwardList() ?: return
        if (history.size > 1) {
            val prefix = tab.sessionHistory.take(tab.nativeOffset)
            tab.sessionHistory = (prefix + (0 until history.size).map { history.getItemAtIndex(it).url }).toMutableList()
            tab.sessionIndex = prefix.size + history.currentIndex
        } else if (tab.sessionHistory.isEmpty() && history.size == 1) {
            tab.sessionHistory.add(history.getItemAtIndex(0).url)
            tab.sessionIndex = 0
        }
    }
    fun snapshot(tabs: List<Tab>, current: Tab?): String {
        val list = tabs.filter { it.url.isNotBlank() && it.url != "about:blank" }
        val array = JSONArray()
        list.forEach { t ->
            capture(t)
            val urls = t.sessionHistory.ifEmpty { listOf(t.url) }
            val index = t.sessionIndex
            val start = (index - 99).coerceAtLeast(0)
            array.put(JSONObject().put("u", t.url).put("t", t.title).put("desktop", t.desktop)
                .put("history", JSONArray(urls.drop(start).take(100))).put("index", (index - start).coerceAtLeast(0)))
        }
        return JSONObject().put("version", 1).put("tabs", array).put("current", list.indexOf(current)).toString()
    }
}

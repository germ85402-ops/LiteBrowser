package app.svetlo

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Text and sanitized article markup stay local; external images are blocked when opened offline. */
object ReadingList {
    data class Item(val token: String, val title: String, val url: String)
    private fun dir(ctx: Context) = File(ctx.filesDir, "reading").apply { mkdirs() }
    fun save(ctx: Context, article: Article) {
        val token = MessageDigest.getInstance("SHA-256").digest(article.url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = AtomicFile(File(dir(ctx), token))
        val out = f.startWrite()
        try {
            out.write(JSONObject().put("url", article.url).put("title", article.title).put("html", article.html)
                .put("byline", article.byline).put("site", article.site).put("lang", article.lang).toString().toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) { f.failWrite(out); throw e }
    }
    fun list(ctx: Context): List<Item> = dir(ctx).listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) }
        .sortedByDescending { it.lastModified() }.mapNotNull { f -> runCatching { val o = JSONObject(f.readText()); Item(f.name, o.getString("title"), o.getString("url")) }.getOrNull() }
    fun read(ctx: Context, token: String?): Article? = runCatching {
        val f = token?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { File(dir(ctx), it) } ?: return null
        val o = JSONObject(f.readText())
        Article(o.getString("url"), o.getString("title"), null, null, o.getString("html"), o.optString("lang"))
    }.getOrNull()
    fun remove(ctx: Context, token: String) { if (token.matches(Regex("[a-f0-9]{64}"))) File(dir(ctx), token).delete() }
}

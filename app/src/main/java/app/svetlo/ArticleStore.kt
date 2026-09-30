package app.svetlo

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** File tokens allow large articles to cross processes without Binder size limits. */
object ArticleStore {
    fun write(ctx: Context, a: Article): String {
        require(a.html.length <= 4 * 1024 * 1024) { "Статья слишком большая" }
        val dir = folder(ctx).apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }?.forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        File(dir, token).writeText(JSONObject().put("url", a.url).put("title", a.title).put("html", a.html)
            .put("byline", a.byline).put("site", a.site).put("lang", a.lang).toString())
        return token
    }
    fun read(ctx: Context, token: String?): Article? = runCatching {
        val f = file(ctx, token) ?: return null
        if (f.length() > 8 * 1024 * 1024) return null
        val o = JSONObject(f.readText())
        fun nullable(key: String) = o.optString(key).takeIf { !o.isNull(key) && it.isNotBlank() }
        Article(o.getString("url"), o.getString("title"), nullable("byline"), nullable("site"), o.getString("html"), nullable("lang"))
    }.getOrNull()
    fun remove(ctx: Context, token: String?) { file(ctx, token)?.delete() }
    fun folder(ctx: Context) = File(ctx.cacheDir, if (Incognito.isIncognitoProcess(ctx.applicationContext as android.app.Application)) "articles-incognito" else "articles")
    private fun file(ctx: Context, token: String?): File? = token?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }?.let { File(folder(ctx), it) }
}

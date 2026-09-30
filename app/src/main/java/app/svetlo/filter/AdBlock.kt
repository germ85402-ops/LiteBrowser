package app.svetlo.filter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.svetlo.data.Prefs
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** App-wide ad blocking state on top of [FilterEngine]. Holds only the application context. */
@android.annotation.SuppressLint("StaticFieldLeak")
object AdBlock {
    @Volatile private var engine = FilterEngine().freeze()
    private val ready = CountDownLatch(1)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val whitelist: MutableSet<String> = Collections.synchronizedSet(HashSet())

    val totalBlocked = AtomicLong()
    val ruleCount get() = engine.ruleCount
    @Volatile var updating = false
        private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
        totalBlocked.set(Prefs.sp.getLong("blocked_total", 0))
        whitelist.addAll(Prefs.sp.getStringSet("whitelist", emptySet())!!)
        rebuild()
        if (!Prefs.isPrivate && FilterLists.needsUpdate(app)) update(force = false)
    }

    fun saveStats() = Prefs.sp.edit().putLong("blocked_total", totalBlocked.get()).apply()

    fun rebuild(done: (() -> Unit)? = null) = worker.execute {
        engine = FilterLists.buildEngine(app)
        ready.countDown()
        done?.let { main.post(it) }
    }

    /** Downloads lists, rebuilds the engine and reports the number of failed lists on the main thread. */
    fun update(force: Boolean, done: ((failed: Int) -> Unit)? = null) = worker.execute {
        updating = true
        val failed = FilterLists.updateAll(app, force)
        engine = FilterLists.buildEngine(app)
        updating = false
        ready.countDown()
        done?.let { main.post { it(failed) } }
    }

    fun isWhitelisted(host: String?) = host != null && synchronized(whitelist) { FilterEngine.inHostSet(whitelist, host) }

    fun whitelist(): List<String> = synchronized(whitelist) { whitelist.sorted() }

    fun setWhitelisted(host: String, allowed: Boolean) {
        val h = host.removePrefix("www.")
        synchronized(whitelist) {
            if (allowed) whitelist.add(h) else whitelist.removeAll { FilterEngine.domainMatches(host, it) }
            Prefs.sp.edit().putStringSet("whitelist", HashSet(whitelist)).apply()
        }
    }

    private fun active(pageHost: String?): Boolean {
        if (!Prefs.adblock || isWhitelisted(pageHost)) return false
        // The first page load after start may race the initial engine build.
        if (ready.count > 0 && Looper.myLooper() != Looper.getMainLooper()) ready.await(3, TimeUnit.SECONDS)
        return true
    }

    fun shouldBlock(req: WebResourceRequest, pageHost: String?): Boolean =
        active(pageHost) && engine.matchRequest(req.url.toString(), inferType(req), pageHost)

    fun blocksNavigation(url: String, openerHost: String?): Boolean =
        active(openerHost) && engine.matchNavigation(url, openerHost)

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private val IMG = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
    private val FONT = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIA = setOf("mp4", "webm", "mp3", "m4a", "ogg", "m3u8", "ts", "m4s")

    private fun inferType(req: WebResourceRequest): Int {
        val accept = req.requestHeaders["Accept"] ?: req.requestHeaders["accept"] ?: ""
        val ext = (req.url.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return when {
            accept.startsWith("text/css") -> FilterEngine.T_STYLESHEET
            accept.startsWith("image/") -> FilterEngine.T_IMAGE
            accept.startsWith("text/html") -> FilterEngine.T_SUBDOCUMENT
            ext == "js" || ext == "mjs" -> FilterEngine.T_SCRIPT
            ext == "css" -> FilterEngine.T_STYLESHEET
            ext in IMG -> FilterEngine.T_IMAGE
            ext in FONT -> FilterEngine.T_FONT
            ext in MEDIA -> FilterEngine.T_MEDIA
            else -> FilterEngine.T_UNKNOWN
        }
    }

    private val scripts by lazy { PageScripts(app.assets.open("adblock.js").bufferedReader().use { it.readText() }) }

    /**
     * Script injected after each page commit (onPageCommitVisible / onPageFinished): element hiding CSS,
     * scriptlets (best-effort at this point, page scripts already run), the procedural cosmetic engine
     * and site-specific helpers. Every part is guarded against running twice in one document.
     */
    fun pageScript(host: String?): String? {
        if (host == null || !active(host)) return null
        val sb = StringBuilder()
        scripts.page(engine.cssFor(host), engine.extrasFor(host))?.let { sb.append(it) }
        if (FilterEngine.domainMatches(host, "youtube.com")) sb.append(YOUTUBE_JS)
        return sb.toString().ifEmpty { null }
    }

    /**
     * Scriptlets only, for injection as early as possible (onPageStarted via evaluateJavascript), before
     * page scripts define what the scriptlets trap. Delivery into the new document is not guaranteed;
     * [pageScript] repeats the same block and `window.__svS` makes whichever lands first win.
     */
    fun earlyScript(host: String?): String? {
        if (host == null || !active(host)) return null
        return scripts.scriptlets(engine.extrasFor(host).scriptlets)
    }

    /** YouTube serves ads from its own video hosts, so they are skipped client-side instead of blocked. */
    private const val YOUTUBE_JS = """(function(){if(window.__lbyt)return;window.__lbyt=1;
var st=document.createElement('style');st.textContent='ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ad-slot-renderer,ytm-ad-slot-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytd-promoted-sparkles-web-renderer,ytm-paid-content-overlay-renderer,.ytp-ad-overlay-container,#player-ads,#masthead-ad,ytm-statement-banner-renderer{display:none!important}';
(document.head||document.documentElement).appendChild(st);var ad=false,wasMuted=false,wasRate=1;
setInterval(function(){var p=document.querySelector('.ad-showing,.ad-interrupting');var v=document.querySelector('video');
var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytm-skip-ad-button,.ytp-ad-skip-button-slot button');
if(b)b.click();
if(p&&v){if(!ad){wasMuted=v.muted;wasRate=v.playbackRate}ad=true;v.muted=true;v.playbackRate=16;if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.1)v.currentTime=v.duration-0.1;}
else if(ad&&v){ad=false;v.muted=wasMuted;v.playbackRate=wasRate;}},300);})();"""
}

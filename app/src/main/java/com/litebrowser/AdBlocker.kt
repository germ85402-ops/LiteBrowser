package com.litebrowser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Host-based blocker: a request is blocked if its host or any parent domain is listed. */
object AdBlocker {
    private const val UPDATE_URL =
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"
    private const val FILE_NAME = "adhosts.txt"

    @Volatile
    private var hosts: Set<String> = emptySet()
    val blockedCount = AtomicInteger()
    val size: Int get() = hosts.size

    /** Hides common ad containers that are served from the page's own domain. */
    const val COSMETIC_JS = """(function(){
      if(document.getElementById('__lb_css'))return;
      var s=document.createElement('style');s.id='__lb_css';
      s.textContent='.adsbygoogle,ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],iframe[src*="doubleclick"],'+
      'iframe[src*="googlesyndication"],[class*="ad-banner"],[class*="adbanner"],[id*="ad-banner"],'+
      '[class*="advert"],[id*="advert"],.ad-container,.ad-slot,.ad-wrapper,.sponsored,[data-ad],[data-ad-slot]'+
      '{display:none!important;height:0!important;}';
      (document.head||document.documentElement).appendChild(s);
    })();"""

    fun init(ctx: Context) {
        thread(name = "adblock-load") { load(ctx.applicationContext) }
    }

    private fun load(ctx: Context) {
        val file = File(ctx.filesDir, FILE_NAME)
        val text = if (file.exists()) file.readText() else ctx.assets.open(FILE_NAME).bufferedReader().readText()
        hosts = parse(text)
    }

    fun parse(text: String): Set<String> {
        val set = HashSet<String>(8192)
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            // Accept both plain domain lists and hosts-file format ("0.0.0.0 domain").
            val host = line.split(' ', '\t').last().lowercase()
            if (host.contains('.') && host != "localhost") set.add(host)
        }
        return set
    }

    fun isBlocked(uri: Uri): Boolean {
        var h = uri.host?.lowercase() ?: return false
        val list = hosts
        while (true) {
            if (h in list) return true
            val i = h.indexOf('.')
            if (i < 0 || i == h.lastIndexOf('.')) return false
            h = h.substring(i + 1)
        }
    }

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun update(ctx: Context, done: (Result<Int>) -> Unit) {
        thread(name = "adblock-update") {
            done(runCatching {
                val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parse(text)
                require(parsed.size > 100) { "Список фильтров пустой" }
                File(ctx.filesDir, FILE_NAME).writeText(text)
                hosts = parsed
                parsed.size
            })
        }
    }
}

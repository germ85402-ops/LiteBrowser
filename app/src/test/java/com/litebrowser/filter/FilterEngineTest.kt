package com.litebrowser.filter

import com.litebrowser.filter.FilterEngine.Companion.T_IMAGE
import com.litebrowser.filter.FilterEngine.Companion.T_SCRIPT
import com.litebrowser.filter.FilterEngine.Companion.T_SUBDOCUMENT
import com.litebrowser.filter.FilterEngine.Companion.T_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FilterEngineTest {
    private fun engine(vararg rules: String) = FilterEngine().apply { add(rules.joinToString("\n")) }.freeze()

    @Test
    fun hostAnchor() {
        val e = engine("||ads.example.com^")
        assertTrue(e.matchRequest("https://ads.example.com/x.js", T_SCRIPT, "site.com"))
        assertTrue(e.matchRequest("https://cdn.ads.example.com/x.js", T_SCRIPT, "site.com"))
        assertFalse(e.matchRequest("https://example.com/ads.js", T_SCRIPT, "site.com"))
        assertFalse(e.matchRequest("https://bads.example.com/", T_SCRIPT, "site.com"))
    }

    @Test
    fun wildcardsAndSeparators() {
        val e = engine("/banner/*/img^", "|https://track.", "swf|", "-ad-300x250.")
        assertTrue(e.matchRequest("http://e.com/banner/foo/img?x=1", T_IMAGE, "e.com"))
        assertTrue(e.matchRequest("http://e.com/banner/foo/img", T_IMAGE, "e.com"))
        assertFalse(e.matchRequest("http://e.com/banner/foo/imgs", T_IMAGE, "e.com"))
        assertTrue(e.matchRequest("https://track.foo.com/p", T_UNKNOWN, "e.com"))
        assertFalse(e.matchRequest("https://x.com/?u=https://track.foo.com", T_UNKNOWN, "e.com"))
        assertTrue(e.matchRequest("https://x.com/movie.swf", T_UNKNOWN, "e.com"))
        assertFalse(e.matchRequest("https://x.com/movie.swf?x", T_UNKNOWN, "e.com"))
        assertTrue(e.matchRequest("https://x.com/img/top-ad-300x250.png", T_IMAGE, "x.com"))
    }

    @Test
    fun optionsTypesPartyAndDomains() {
        val e = engine(
            "||cdn.net/ads/\$script,third-party",
            "||tracker.io^\$domain=a.com|~b.a.com",
            "||frames.com^\$subdocument",
        )
        assertTrue(e.matchRequest("https://cdn.net/ads/a.js", T_SCRIPT, "news.com"))
        assertFalse(e.matchRequest("https://cdn.net/ads/a.js", T_SCRIPT, "www.cdn.net"))
        assertFalse(e.matchRequest("https://cdn.net/ads/a.png", T_IMAGE, "news.com"))
        assertTrue(e.matchRequest("https://tracker.io/p", T_IMAGE, "www.a.com"))
        assertFalse(e.matchRequest("https://tracker.io/p", T_IMAGE, "b.a.com"))
        assertFalse(e.matchRequest("https://tracker.io/p", T_IMAGE, "c.com"))
        assertTrue(e.matchRequest("https://frames.com/x", T_SUBDOCUMENT, "c.com"))
        assertFalse(e.matchRequest("https://frames.com/x.js", T_SCRIPT, "c.com"))
    }

    @Test
    fun exceptionsAndImportant() {
        val e = engine(
            "/ads/*", "@@||good.com/ads/ok.js",
            "||evil.com^\$important", "@@||evil.com^",
            "||x.com^", "@@||site.org^\$document",
        )
        assertTrue(e.matchRequest("https://good.com/ads/bad.js", T_SCRIPT, "good.com"))
        assertFalse(e.matchRequest("https://good.com/ads/ok.js", T_SCRIPT, "good.com"))
        assertTrue(e.matchRequest("https://evil.com/a", T_SCRIPT, "p.com"))
        assertTrue(e.matchRequest("https://x.com/a", T_SCRIPT, "p.com"))
        assertFalse(e.matchRequest("https://x.com/a", T_SCRIPT, "www.site.org"))
    }

    @Test
    fun hostsFormatsRegexAndPopups() {
        val e = engine("0.0.0.0 bad.com", "bad2.net", "/ban+er\\d/", "||popads.io^\$popup", "ads.js")
        assertTrue(e.matchRequest("https://sub.bad.com/", T_IMAGE, "p.com"))
        assertTrue(e.matchRequest("https://bad2.net/", T_IMAGE, "p.com"))
        assertTrue(e.matchRequest("https://p.com/bannner7.gif", T_IMAGE, "p.com"))
        assertTrue(e.matchNavigation("https://popads.io/x", "p.com"))
        assertFalse(e.matchRequest("https://popads.io/x.js", T_SCRIPT, "p.com"))
        assertTrue(e.matchNavigation("https://bad.com/", "p.com"))
        // "ads.js" is a URL substring rule, not a host.
        assertTrue(e.matchRequest("https://p.com/static/ads.js", T_SCRIPT, "p.com"))
    }

    @Test
    fun unsupportedModifiersAreSkipped() {
        val e = engine("||a.com^\$csp=script-src 'none'", "||b.com^\$redirect=noopjs", "||c.com^\$removeparam=utm")
        assertFalse(e.matchRequest("https://a.com/", T_SCRIPT, "p.com"))
        assertFalse(e.matchRequest("https://b.com/", T_SCRIPT, "p.com"))
        assertFalse(e.matchRequest("https://c.com/", T_SCRIPT, "p.com"))
    }

    @Test
    fun cosmetics() {
        val e = engine(
            "##.ad-box", "###banner", "site.com#@#.ad-box", "site.com##.promo",
            "~other.com##.sticky-ad", "news.*##.native", "##div:-abp-has(.x)", "##.bad{color:red}",
            "@@||free.com^\$generichide", "@@||clean.com^\$elemhide", "example.com#?#div:has-text(Ad)",
        )
        val any = e.cssFor("random.com")
        assertTrue(any.contains(".ad-box") && any.contains("#banner") && any.contains(".sticky-ad"))
        assertFalse(any.contains("abp-has") || any.contains("color:red") || any.contains("has-text"))
        val site = e.cssFor("www.site.com")
        assertFalse(site.contains(".ad-box"))
        assertTrue(site.contains(".promo") && site.contains("#banner"))
        assertFalse(e.cssFor("other.com").contains(".sticky-ad"))
        assertTrue(e.cssFor("www.news.co.uk").contains(".native"))
        val free = e.cssFor("free.com")
        assertFalse(free.contains(".ad-box"))
        assertEquals("", e.cssFor("clean.com"))
    }

    @Test
    fun thirdPartyUsesRegistrableDomain() {
        assertEquals("example.co.uk", FilterEngine.baseDomain("a.b.example.co.uk"))
        assertEquals("vk.ru", FilterEngine.baseDomain("m.vk.ru"))
        assertEquals("site.com.ru", FilterEngine.baseDomain("www.site.com.ru"))
    }

    /** Smoke/benchmark test against real lists when they are available locally (see README). */
    @Test
    fun realLists() {
        val dir = File(System.getProperty("filterlists.dir") ?: "/tmp/filterlists")
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }?.toList().orEmpty()
        assumeTrue(files.isNotEmpty())
        val rt = Runtime.getRuntime()
        System.gc()
        val memBefore = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val e = FilterEngine()
        files.forEach { e.add(it.readText()) }
        e.freeze()
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val memMb = (rt.totalMemory() - rt.freeMemory() - memBefore) / 1_048_576

        val ads = listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://an.yandex.ru/system/context.js",
            "https://mc.yandex.ru/metrika/tag.js",
        )
        val ok = listOf(
            "https://www.google.com/images/branding/logo.png",
            "https://yastatic.net/jquery/3.3.1/jquery.min.js",
            "https://ru.wikipedia.org/static/images/icons/wikipedia.png",
            "https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js",
        )
        ads.forEach { assertTrue(it, e.matchRequest(it, T_SCRIPT, "news.example.ru")) }
        ok.forEach { assertFalse(it, e.matchRequest(it, T_UNKNOWN, "news.example.ru")) }

        val urls = (ads + ok).flatMap { u -> (0 until 2500).map { "$u?v=$it" } }
        val t1 = System.nanoTime()
        urls.forEach { e.matchRequest(it, T_UNKNOWN, "news.example.ru") }
        val perReqUs = (System.nanoTime() - t1) / 1000 / urls.size
        val t2 = System.nanoTime()
        val css = e.cssFor("news.example.ru")
        val cssMs = (System.nanoTime() - t2) / 1_000_000
        println("FILTERS rules=${e.ruleCount} parse=${parseMs}ms heap~${memMb}MB match=${perReqUs}us css=${css.length / 1024}KB/${cssMs}ms")
    }
}

package app.svetlo.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralTest {
    private fun engine(vararg rules: String) = FilterEngine().apply { add(rules.joinToString("\n")) }.freeze()
    private fun js(sel: String) = Procedural.compile(sel)?.let { r ->
        val css = StringBuilder(); val out = ArrayList<String>(); r.emit(css, out); out.singleOrNull() ?: css.toString()
    }

    @Test
    fun compilesOperators() {
        assertEquals("""[["div.ad",["t","Реклама"]],0,"",0]""", js("div.ad:has-text(Реклама)"))
        assertEquals("""[["div",["t","/sponsored\\)/i"]],0,"",0]""", js("div:-abp-contains(/sponsored\\)/i)"))
        assertEquals("""[["p",["t","x"],["u",2]],1,"",0]""", js("p:contains(x):upward(2):remove()"))
        assertEquals("""[["p",["u",".wrap > div"]],0,"",0]""", js("p:upward(.wrap > div)"))
        assertEquals("""[["a",["u",3]],0,"",0]""", js("a:nth-ancestor(3)"))
        assertEquals("""[["",["x","//div[@id=\"a\"]/.."]],0,"",0]""", js(":xpath(//div[@id=\"a\"]/..)"))
        assertEquals("""[["div",["c","","position","^fixed$",""]],0,"",0]""", js("div:matches-css(position: fixed)"))
        assertEquals("""[["div",["c","::before","content","Ad","i"]],0,"",0]""", js("div:matches-css-before(content: /Ad/i)"))
        assertEquals("""[["div",["c","::after","width","^1.*px$",""]],0,"",0]""", js("div:matches-css(after, width: 1*px)"))
        assertEquals("""[["div",["l",20]],0,"",0]""", js("div:min-text-length(20):watch-attr(class)"))
        assertEquals("""[["div",["n",["",["t","ok"]]]],0,"",0]""", js("div:not(:has-text(ok))"))
        assertEquals("""[["li:not(.x)",["t","a"],["s","> span"]],0,"",0]""", js("li:not(.x):has-text(a) > span"))
        assertEquals("""[["li",["t","a"],["s"," b"]],0,"",0]""", js("li:has-text(a) b"))
        assertEquals("""[["div.a",["p","/^\\/search/"],["h","> b"]],0,"",0]""", js(":matches-path(/^\\/search/) div.a:has(> b)"))
        assertEquals("""[["li",["t","a"],["s",".y"]],0,"",0]""", js("li:has-text(a).y"))
        assertEquals("""[["div",["h",["> a",["t","ad"]]]],2,"color: red",0]""", js("div:has(> a:has-text(ad)):style(color: red)"))
        assertEquals("""[["div",["n",["",["h"," .ad"]]]],0,"","div:not(:has(.ad))"]""", js("div:if-not(.ad)"))
    }

    @Test
    fun nativeHasAndPlainStyles() {
        val css = StringBuilder(); val out = ArrayList<String>()
        Procedural.compile("div.box:-abp-has(> a[href*=\"ad\"])")!!.emit(css, out)
        assertEquals("@supports selector(:has(*)){div.box:has(> a[href*=\"ad\"]){display:none!important}}\n", css.toString())
        assertEquals("""[["div.box",["h","> a[href*=\"ad\"]"]],0,"","div.box:has(> a[href*=\"ad\"])"]""", out.single())
        assertEquals(".b{height:0!important}\n", js(".b:style(height:0!important)"))
        assertEquals("""[[".b"],1,"",0]""", js(".b:remove()"))
    }

    @Test
    fun rejectsUnsupported() {
        assertNull(js("div:others()"))
        assertNull(js("div:not(:matches-path(/x/))"))
        assertNull(js("div:remove():has-text(x)"))
        assertNull(js("div:style(background: url(http://x))"))
        assertNull(js(":has-text(x)"))
        assertNull(js("div:has-text(x"))
        assertNull(js("div:is(:has-text(x))"))
    }

    @Test
    fun engineRoutesExtendedSyntax() {
        val e = engine(
            "example.com##div:has-text(Ad)",
            "##div:has-text(Generic)", "#?#div:has-text(Generic2)", "~other.com##p:has-text(Neg)",
            "a.com,~b.a.com#?#.x:-abp-has(.y)",
            "news.*##.n:contains(Promo)",
            "example.com##.banner:style(height: 0 !important)",
            "example.com#\$#.top { display: none !important; }",
            "example.com#\$#.gone { remove: true; }",
            "example.com#\$?#li:has-text(x) { color: red }",
            "example.com##section:has(.sponsor)",
            "##div:has(.generic-native)",
            "clean.com##div:has-text(Ad)", "clean.com#@#div:has-text(Ad)",
            "ex.org##div:has-text(A)", "ex.org#@?#div:has-text(A)",
            "@@||free.net^\$elemhide", "free.net##div:has-text(A)",
        )
        val x = e.extrasFor("www.example.com")
        assertTrue(x.procedural.contains("""[["div",["t","Ad"]],0,"",0]"""))
        assertTrue(x.procedural.contains("""[[".gone"],1,"",0]"""))
        assertTrue(x.procedural.contains("""[["li",["t","x"]],2,"color: red",0]"""))
        assertTrue(x.css.contains(".banner{height: 0 !important}"))
        assertTrue(x.css.contains(".top{display: none !important;}"))
        assertTrue(x.css.contains("@supports selector(:has(*)){section:has(.sponsor)"))
        assertFalse(x.procedural.any { "Generic" in it || "Neg" in it })
        assertFalse(e.cssFor("www.example.com").contains("section:has"))
        assertTrue(e.cssFor("x.com").contains("div:has(.generic-native){display:none!important}\n"))
        assertEquals(1, e.extrasFor("a.com").procedural.size)
        assertTrue(e.extrasFor("b.a.com").procedural.isEmpty())
        assertTrue(e.extrasFor("other.com").procedural.isEmpty())
        assertEquals(1, e.extrasFor("www.news.co.uk").procedural.size)
        assertTrue(e.extrasFor("clean.com").procedural.isEmpty())
        assertTrue(e.extrasFor("ex.org").procedural.isEmpty())
        assertTrue(e.extrasFor("free.net").procedural.isEmpty())
        assertTrue(e.extrasFor("random.com") === FilterEngine.Extras.EMPTY)
    }
}

package app.svetlo.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ScriptletsTest {
    private fun engine(vararg rules: String) = FilterEngine().apply { add(rules.joinToString("\n")) }.freeze()
    private val scripts by lazy { PageScripts(File("src/main/assets/adblock.js").readText()) }

    @Test
    fun splitsArguments() {
        assertEquals(listOf("set-constant", "a.b", "true"), Scriptlets.splitArgs("set-constant, a.b,  true"))
        assertEquals(listOf("acs", "\$", "/a,b\\d/"), Scriptlets.splitArgs("acs, \$, /a\\,b\\d/"))
        assertEquals(listOf("set", "x", "a, b", "q\"t", "/\\d/"), Scriptlets.splitArgs("'set', 'x', 'a, b', \"q\\\"t\", '/\\d/'"))
        assertEquals(listOf("nostif", ""), Scriptlets.splitArgs("nostif, ''"))
        assertEquals(listOf("ra", "", "x"), Scriptlets.splitArgs("ra,,x"))
        assertNull(Scriptlets.splitArgs("'unterminated"))
    }

    @Test
    fun resolvesAliases() {
        assertEquals("abort-on-property-read", Scriptlets.canonicalName("aopr"))
        assertEquals("abort-on-property-read", Scriptlets.canonicalName("ubo-aopr.js"))
        assertEquals("set-constant", Scriptlets.canonicalName("set-constant.js"))
        assertEquals("no-setTimeout-if", Scriptlets.canonicalName("prevent-setTimeout"))
        assertEquals("prevent-addEventListener", Scriptlets.canonicalName("addEventListener-defuser.js"))
        assertEquals("adjust-setTimeout", Scriptlets.canonicalName("nano-stb"))
        assertEquals("no-window-open-if", Scriptlets.canonicalName("window.open-defuse"))
        assertNull(Scriptlets.canonicalName("trusted-set-constant"))
        assertNull(Scriptlets.canonicalName("some-unknown"))
        assertEquals(listOf("no-window-open-if", "!pop"), Scriptlets.parseCall("window.open-defuse.js, 0, pop"))
        assertEquals(listOf("noeval-if"), Scriptlets.parseCall("noeval"))
    }

    @Test
    fun engineCollectsScriptlets() {
        val e = engine(
            "example.com##+js(aopr, foo)",
            "example.com,~sub.example.com##+js(set, a.b.c, true)",
            "example.com#%#//scriptlet('abort-current-inline-script', 'document.write', 'ad\\'s')",
            "example.com#\$#abort-on-property-write bar; override-property-read x.y false",
            "example.com##+js(trusted-set-cookie, a, b)", "example.com##+js(unknown-thing)",
            "##+js(aopr, generic)",
            "example.com#@#+js(aopr, foo)",
            "other.com##+js(nostif, ads, 1000)", "other.com##+js(json-prune, ads)", "deep.other.com#@#+js()",
            "shop.*##+js(ra, onclick, a.x, stay)",
            "@@||allowed.org^\$document", "allowed.org##+js(aopr, z)",
        )
        val calls = e.extrasFor("example.com").scriptlets
        assertEquals(
            listOf(
                listOf("set-constant", "a.b.c", "true"),
                listOf("abort-current-script", "document.write", "ad's"),
                listOf("abort-on-property-write", "bar"),
                listOf("set-constant", "x.y", "false"),
            ),
            calls,
        )
        assertEquals(3, e.extrasFor("sub.example.com").scriptlets.size)
        assertEquals(2, e.extrasFor("www.other.com").scriptlets.size)
        assertTrue(e.extrasFor("deep.other.com").scriptlets.isEmpty())
        assertEquals(listOf(listOf("remove-attr", "onclick", "a.x", "stay")), e.extrasFor("shop.de").scriptlets)
        assertTrue(e.extrasFor("random.com").scriptlets.isEmpty())
        assertTrue(e.extrasFor("allowed.org").scriptlets.isEmpty())
    }

    @Test
    fun buildsPageScript() {
        val e = engine("site.com##+js(aopr, foo)", "site.com##div:has-text(Ad)", "site.com##.x:style(opacity:0)", "##.plain")
        val page = scripts.page(e.cssFor("site.com"), e.extrasFor("site.com"))!!
        assertTrue(page.contains("__lb_css") && page.contains(".plain") && page.contains(".x{opacity:0}"))
        assertTrue(page.contains("window.__svS") && page.contains("S['abort-on-property-read']"))
        assertFalse(page.contains("S['json-prune']"))
        assertTrue(page.contains("window.__svP") && page.contains("""[["div",["t","Ad"]],0,"",0]"""))
        val early = scripts.scriptlets(e.extrasFor("site.com").scriptlets)!!
        assertTrue(early.contains("S['abort-on-property-read']") && !early.contains("__svP") && !early.contains("__lb_css"))
        assertNull(scripts.scriptlets(emptyList()))
        assertNull(scripts.page("", FilterEngine.Extras.EMPTY))
    }

    /** Executes the generated JS in Node with a tiny DOM stub when Node is installed. */
    @Test
    fun runsInJsEngine() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
            ?: runCatching { ProcessBuilder("sh", "-c", "command -v node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()
        assumeTrue(!node.isNullOrEmpty())
        val e = engine(
            "t.com##+js(set, cfg.ads.enabled, false)", "t.com##+js(set, flagA, noopFunc)", "t.com##+js(aopr, adBlockDetect)",
            "t.com##+js(nostif, adblock)", "t.com##+js(json-prune, ads items.[].banner)", "t.com##+js(noeval-if, evil)",
            "t.com##+js(aopw, popTrigger)", "t.com##+js(nowoif)", "t.com##+js(set-local-storage-item, consent, true)",
            "t.com##div:has-text(Реклама)", "t.com##span:has-text(/sponsor/i):upward(1):remove()", "t.com##p:not(:has-text(keep))",
        )
        val page = scripts.page("", e.extrasFor("t.com"))!!
        val harness = """
            globalThis.window = globalThis;
            function El(tag, text, parent) { this.localName = tag; this.nodeType = 1; this.textContent = text; this.parentElement = parent || null;
              this.css = {}; var s = this; this.style = { setProperty: function(k, v, p) { s.css[k] = v + (p ? '!' : ''); }, getPropertyValue: function(k) { return (s.css[k] || '').replace('!', ''); }, getPropertyPriority: function() { return ''; } };
              this.removed = false; this.remove = function() { this.removed = true; }; this.matches = function() { return false; }; }
            var wrap = new El('section', '', null), ad = new El('div', 'Купите! Реклама', null), ok = new El('div', 'text', null),
              sp = new El('span', 'SPONSORED', wrap), p1 = new El('p', 'keep me', null), p2 = new El('p', 'drop', null);
            var byTag = { div: [ad, ok], span: [sp], p: [p1, p2] }, store = {};
            globalThis.document = { readyState: 'complete', currentScript: null, cookie: '', addEventListener: function() {},
              getElementById: function() { return null; }, querySelectorAll: function(s) { return byTag[s] || []; } };
            globalThis.MutationObserver = function() { this.observe = function() {}; this.takeRecords = function() { return []; }; };
            globalThis.requestAnimationFrame = function(f) { setTimeout(f, 0); };
            globalThis.HTMLScriptElement = function() {};
            globalThis.localStorage = { setItem: function(k, v) { store[k] = v; }, removeItem: function(k) { delete store[k]; } };
            window.open = function() { return 'opened'; }; window.addEventListener = function() {};
            $page
            $page
            function check(c, m) { if (!c) { console.log('FAIL ' + m); process.exit(1); } }
            window.cfg = {}; cfg.ads = { enabled: true }; check(cfg.ads.enabled === false, 'set-constant chain');
            check(typeof flagA === 'function' && flagA() === undefined, 'noopFunc');
            var threw = false; try { adBlockDetect; } catch (x) { threw = x instanceof ReferenceError; } check(threw, 'aopr');
            threw = false; try { window.popTrigger = 1; } catch (x) { threw = true; } check(threw, 'aopw');
            var o = JSON.parse('{"ads":[1],"items":[{"banner":1,"id":2}],"k":1}'); check(!o.ads && o.k === 1 && !('banner' in o.items[0]) && o.items[0].id === 2, 'json-prune');
            check(eval('1+1') === 2 && eval('"evil";3') === undefined, 'noeval-if');
            check(window.open('https://x') === null, 'nowoif');
            check(store.consent === 'true', 'local storage');
            check(ad.css.display === 'none!' && !ok.css.display, 'has-text hide');
            check(wrap.removed && !sp.removed, 'upward remove');
            check(!p1.css.display && p2.css.display === 'none!', 'not has-text');
            var fired = [];
            setTimeout(function() { fired.push('adblock'); }, 0);
            setTimeout(function() { fired.push('good'); }, 0);
            setTimeout(function() { check(fired.join() === 'good', 'nostif ' + fired.join()); console.log('OK'); }, 30);
        """.trimIndent()
        val f = File.createTempFile("svetlo", ".js").apply { writeText(harness); deleteOnExit() }
        val proc = ProcessBuilder(node, f.path).redirectErrorStream(true).start()
        assertTrue(proc.waitFor(20, TimeUnit.SECONDS))
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(out, "OK", out.trim())
    }
}

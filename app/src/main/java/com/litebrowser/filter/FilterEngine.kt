package com.litebrowser.filter

/**
 * Adblock Plus / uBlock Origin compatible filter engine (network rules + CSS element hiding).
 * Pure Kotlin without Android dependencies so it can be unit-tested on the JVM.
 *
 * Usage: create, call [add] for every list, then [freeze] once before matching.
 */
class FilterEngine {
    private class NetRule(
        val pattern: Pattern?,
        val types: Int,
        val party: Int,
        val include: Array<String>?,
        val exclude: Array<String>?,
        val important: Boolean,
    ) {
        fun applies(c: Ctx): Boolean {
            if (types and c.type == 0) return false
            if (party == THIRD && !c.third) return false
            if (party == FIRST && c.third) return false
            if (include != null || exclude != null) {
                val ph = c.pageHost ?: return include == null
                if (exclude != null && exclude.any { domainMatches(ph, it) }) return false
                if (include != null && include.none { domainMatches(ph, it) }) return false
            }
            return true
        }

        fun matches(c: Ctx) = applies(c) && (pattern == null || pattern.matches(c.url, c.hs, c.he))
    }

    private class Ctx(
        val url: String, val hs: Int, val he: Int, val host: String,
        val type: Int, val third: Boolean, val pageHost: String?,
    )

    /** ABP pattern with `*` wildcards, `^` separators and `|` / `||` anchors, or a regex. */
    private class Pattern(
        val hostAnchor: Boolean,
        val startAnchor: Boolean,
        val endAnchor: Boolean,
        val segs: Array<String>,
        val regex: Regex?,
    ) {
        fun matches(url: String, hs: Int, he: Int): Boolean {
            regex?.let { return it.containsMatchIn(url) }
            if (segs.isEmpty()) return true
            val first = segs[0]
            when {
                hostAnchor -> {
                    var s = hs
                    while (s in hs until he) {
                        val e = segAt(url, s, first)
                        if (e >= 0 && rest(url, e, 1)) return true
                        val dot = url.indexOf('.', s)
                        if (dot < 0 || dot >= he) break
                        s = dot + 1
                    }
                    return false
                }
                startAnchor -> {
                    val e = segAt(url, 0, first)
                    return e >= 0 && rest(url, e, 1)
                }
                else -> {
                    if (segs.size == 1 && endAnchor) return endsWith(url, 0, first)
                    // Leftmost occurrence of the first segment is always the best start for a glob.
                    val e = find(url, 0, first)
                    return e >= 0 && rest(url, e, 1)
                }
            }
        }

        private fun rest(url: String, from: Int, idx: Int): Boolean {
            var pos = from
            for (i in idx until segs.size) {
                if (i == segs.size - 1 && endAnchor) return endsWith(url, pos, segs[i])
                pos = find(url, pos, segs[i])
                if (pos < 0) return false
            }
            return !endAnchor || pos == url.length || segs.size == 1 && idx == 1 && pos == url.length
        }

        private fun endsWith(url: String, from: Int, seg: String): Boolean {
            for (p in from..url.length) if (segAt(url, p, seg) == url.length) return true
            return false
        }

        private fun find(url: String, from: Int, seg: String): Int {
            val c0 = seg[0]
            var i = from
            while (i <= url.length) {
                if (c0 != '^') {
                    i = url.indexOf(c0, i)
                    if (i < 0) return -1
                }
                val e = segAt(url, i, seg)
                if (e >= 0) return e
                i++
            }
            return -1
        }

        private fun segAt(url: String, start: Int, seg: String): Int {
            var u = start
            for (c in seg) {
                if (c == '^') {
                    if (u == url.length) continue
                    if (!isSeparator(url[u])) return -1
                    u++
                } else {
                    if (u >= url.length || url[u] != c) return -1
                    u++
                }
            }
            return u
        }
    }

    private val blockHosts = HashSet<String>()
    private val allowHosts = HashSet<String>()
    private val blockHostRules = HashMap<String, MutableList<NetRule>>()
    private val allowHostRules = HashMap<String, MutableList<NetRule>>()
    private val blockTokens = HashMap<String, MutableList<NetRule>>()
    private val allowTokens = HashMap<String, MutableList<NetRule>>()
    private val blockNoToken = ArrayList<NetRule>()
    private val allowNoToken = ArrayList<NetRule>()
    private val docAllow = HashSet<String>()
    private val elemhideAllow = HashSet<String>()
    private val generichideAllow = HashSet<String>()

    private val genericHide = LinkedHashSet<String>()
    private val genericHideExcept = HashSet<String>()
    private val specificHide = HashMap<String, MutableList<String>>()
    private val specificExcept = HashMap<String, MutableSet<String>>()
    private var genericCss = ""
    private val cssCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 24
    }

    var ruleCount = 0
        private set

    fun add(text: String) {
        for (raw in text.lineSequence()) addLine(raw.trim())
    }

    fun freeze(): FilterEngine {
        genericHide.removeAll(genericHideExcept)
        genericCss = buildCss(genericHide)
        return this
    }

    // ---------------------------------------------------------------- matching

    /** Returns true if a sub-resource request should be blocked. */
    fun matchRequest(url: String, type: Int, pageHost: String?): Boolean {
        val lurl = url.lowercase()
        val hs = lurl.indexOf("://").let { if (it < 0) return false else it + 3 }
        var he = hs
        while (he < lurl.length && lurl[he] !in "/?#:") he++
        if (he == hs) return false
        val host = lurl.substring(hs, he)
        if (pageHost != null && inHostSet(docAllow, pageHost)) return false
        val third = pageHost != null && baseDomain(host) != baseDomain(pageHost)
        val c = Ctx(lurl, hs, he, host, type, third, pageHost)
        val important = findRule(c, blockHosts, blockHostRules, blockTokens, blockNoToken) ?: return false
        if (important) return true
        return findRule(c, allowHosts, allowHostRules, allowTokens, allowNoToken) == null
    }

    /** Navigation of a top-level frame or pop-up to [url] opened from [openerHost]. */
    fun matchNavigation(url: String, openerHost: String?) = matchRequest(url, T_POPUP or T_DOCUMENT, openerHost)

    fun isSiteAllowed(host: String) = inHostSet(docAllow, host)

    /** Returns: null = no match, false = match, true = important match. */
    private fun findRule(
        c: Ctx, hosts: Set<String>, hostRules: Map<String, List<NetRule>>,
        tokens: Map<String, List<NetRule>>, noToken: List<NetRule>,
    ): Boolean? {
        var h = c.host
        while (true) {
            if (h in hosts) return false
            hostRules[h]?.let { list -> list.firstOrNull { it.applies(c) }?.let { return it.important } }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        val url = c.url
        var i = 0
        var seen: HashSet<String>? = null
        while (i < url.length) {
            if (!isTokenChar(url[i])) { i++; continue }
            var j = i
            while (j < url.length && isTokenChar(url[j])) j++
            if (j - i >= 2) {
                val tok = url.substring(i, j)
                val list = tokens[tok]
                if (list != null && (seen ?: HashSet<String>().also { seen = it }).add(tok)) {
                    list.firstOrNull { it.matches(c) }?.let { return it.important }
                }
            }
            i = j
        }
        return noToken.firstOrNull { it.matches(c) }?.important
    }

    // ---------------------------------------------------------------- cosmetics

    /** CSS that hides ad elements on [host], or an empty string. */
    @Synchronized
    fun cssFor(host: String): String {
        val h = host.lowercase()
        cssCache[h]?.let { return it }
        val css = computeCss(h)
        cssCache[h] = css
        return css
    }

    private fun computeCss(host: String): String {
        if (inHostSet(docAllow, host) || inHostSet(elemhideAllow, host)) return ""
        val except = HashSet<String>()
        val specific = LinkedHashSet<String>()
        forEachHostKey(host) { k ->
            specificExcept[k]?.let { except.addAll(it) }
            specificHide[k]?.let { specific.addAll(it) }
        }
        specific.removeAll(except)
        val generic = when {
            inHostSet(generichideAllow, host) -> ""
            except.none { it in genericHide } -> genericCss
            else -> buildCss(genericHide.filter { it !in except })
        }
        return generic + buildCss(specific)
    }

    /** Walks host suffixes and uBO entity keys (e.g. `google.*`). */
    private inline fun forEachHostKey(host: String, f: (String) -> Unit) {
        var h = host
        while (true) {
            f(h)
            val dot = h.indexOf('.')
            if (dot < 0) break
            f(h.substring(0, dot) + ".*")
            h = h.substring(dot + 1)
        }
    }

    private fun buildCss(selectors: Collection<String>): String {
        if (selectors.isEmpty()) return ""
        // One invalid selector voids its whole rule, so keep groups small.
        val sb = StringBuilder(selectors.size * 32)
        selectors.chunked(20).forEach { chunk ->
            chunk.joinTo(sb, ",")
            sb.append("{display:none!important}\n")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- parsing

    private fun addLine(line: String) {
        if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line[0] == '#' && !line.startsWith("##") && !line.startsWith("#@#")) return

        // hosts-file format: "0.0.0.0 domain"
        if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1")) {
            val host = line.split(' ', '\t').filter { it.isNotEmpty() }.getOrNull(1)?.lowercase() ?: return
            if (isPlainHost(host)) { blockHosts.add(host); ruleCount++ }
            return
        }

        val cos = findCosmeticSeparator(line)
        if (cos != null) {
            val (idx, sepLen, exception) = cos
            if (idx < 0) return // unsupported extended syntax
            addCosmetic(line.substring(0, idx), line.substring(idx + sepLen).trim(), exception)
            return
        }

        val lower = line.lowercase()
        if (isPlainHost(lower)) { blockHosts.add(lower); ruleCount++; return }
        addNetwork(line)
    }

    /** Returns (index, separatorLength, isException); index -1 means unsupported cosmetic syntax. */
    private fun findCosmeticSeparator(line: String): Triple<Int, Int, Boolean>? {
        val i = line.indexOf('#')
        if (i < 0) return null
        val prefix = line.substring(0, i)
        if (prefix.any { it == '/' || it == '|' || it == '$' || it == '^' || it == '=' }) return null
        val rest = line.substring(i)
        return when {
            rest.startsWith("#@#") -> Triple(i, 3, true)
            rest.startsWith("##+") || rest.startsWith("##^") -> Triple(-1, 0, false)
            rest.startsWith("##") -> Triple(i, 2, false)
            rest.startsWith("#?#") || rest.startsWith("#$#") || rest.startsWith("#%#") ||
                rest.startsWith("#@?#") || rest.startsWith("#@$#") || rest.startsWith("#@%#") ||
                rest.startsWith("#$?#") || rest.startsWith("#@$?#") -> Triple(-1, 0, false)
            else -> null
        }
    }

    private fun addCosmetic(domains: String, sel: String, exception: Boolean) {
        if (!isSupportedSelector(sel)) return
        ruleCount++
        if (domains.isEmpty()) {
            if (exception) genericHideExcept.add(sel) else genericHide.add(sel)
            return
        }
        val inc = ArrayList<String>()
        val exc = ArrayList<String>()
        domains.split(',').forEach {
            val d = it.trim().lowercase()
            if (d.startsWith("~")) exc.add(d.substring(1)) else if (d.isNotEmpty()) inc.add(d)
        }
        if (exception) {
            inc.forEach { specificExcept.getOrPut(it) { HashSet() }.add(sel) }
            return
        }
        if (inc.isEmpty()) genericHide.add(sel) else inc.forEach { specificHide.getOrPut(it) { ArrayList(2) }.add(sel) }
        exc.forEach { specificExcept.getOrPut(it) { HashSet() }.add(sel) }
    }

    private fun addNetwork(line: String) {
        var s = line
        val exception = s.startsWith("@@")
        if (exception) s = s.substring(2)
        var opts: String? = null
        val isRegex = s.length > 2 && s.startsWith("/") && s.endsWith("/")
        if (!isRegex) {
            val d = s.lastIndexOf('$')
            if (d >= 0) {
                opts = s.substring(d + 1)
                s = s.substring(0, d)
            }
        }

        var types = 0
        var negTypes = 0
        var party = 0
        var include: Array<String>? = null
        var exclude: Array<String>? = null
        var important = false
        var elemhide = false
        var generichide = false
        var document = false
        opts?.split(',')?.forEach { raw ->
            val o = raw.trim().lowercase()
            val neg = o.startsWith("~")
            val name = if (neg) o.substring(1) else o
            when {
                name == "third-party" || name == "3p" -> party = if (neg) FIRST else THIRD
                name == "first-party" || name == "1p" -> party = if (neg) THIRD else FIRST
                name.startsWith("domain=") || name.startsWith("from=") -> {
                    val inc = ArrayList<String>()
                    val exc = ArrayList<String>()
                    name.substringAfter('=').split('|').forEach { d ->
                        if (d.startsWith("~")) exc.add(d.substring(1)) else if (d.isNotEmpty()) inc.add(d)
                    }
                    include = inc.takeIf { it.isNotEmpty() }?.toTypedArray()
                    exclude = exc.takeIf { it.isNotEmpty() }?.toTypedArray()
                }
                name == "important" -> important = true
                name == "match-case" || name == "empty" || name == "mp4" -> Unit
                name == "elemhide" || name == "ehide" -> elemhide = true
                name == "generichide" || name == "ghide" -> generichide = true
                name == "document" || name == "doc" -> if (neg) negTypes = negTypes or T_DOCUMENT else {
                    types = types or T_DOCUMENT; document = true
                }
                name == "all" -> types = types or T_ALL
                else -> {
                    // Unknown modifiers (csp, redirect, removeparam, ...) change semantics; skip the rule.
                    val t = TYPE_NAMES[name] ?: return
                    if (neg) negTypes = negTypes or t else types = types or t
                }
            }
        }

        if (elemhide || generichide || document && exception) {
            if (!exception) return
            val host = anchoredHost(s) ?: return
            if (document) docAllow.add(host)
            if (elemhide) elemhideAllow.add(host)
            if (generichide) generichideAllow.add(host)
            ruleCount++
            return
        }

        if (types == 0) types = T_ALL_RESOURCES
        types = types and negTypes.inv()
        if (types == 0) return
        if (exception && types and T_ALL_RESOURCES == 0) return // pop-up only exceptions are irrelevant here

        val lower = s.lowercase()
        if (lower.isEmpty() || lower == "*" || lower == "|" || lower == "||") {
            if (include == null) return // would match everything
            add(exception, null, NetRule(null, types, party, include, exclude, important))
            return
        }

        val host = anchoredHost(lower)
        if (host != null) {
            val plain = types == T_ALL_RESOURCES && party == 0 && include == null && exclude == null && !important
            if (plain) (if (exception) allowHosts else blockHosts).add(host)
            else (if (exception) allowHostRules else blockHostRules)
                .getOrPut(host) { ArrayList(1) }.add(NetRule(null, types, party, include, exclude, important))
            ruleCount++
            return
        }

        val pattern = parsePattern(lower, isRegex) ?: return
        add(exception, if (isRegex) null else pickToken(lower), NetRule(pattern, types, party, include, exclude, important))
    }

    private fun add(exception: Boolean, token: String?, rule: NetRule) {
        ruleCount++
        if (token == null) (if (exception) allowNoToken else blockNoToken).add(rule)
        else (if (exception) allowTokens else blockTokens).getOrPut(token) { ArrayList(2) }.add(rule)
    }

    private fun parsePattern(p: String, isRegex: Boolean): Pattern? {
        if (isRegex) {
            val re = runCatching { Regex(p.substring(1, p.length - 1)) }.getOrNull() ?: return null
            return Pattern(false, false, false, emptyArray(), re)
        }
        var body = p
        var hostAnchor = false
        var startAnchor = false
        var endAnchor = false
        if (body.startsWith("||")) { hostAnchor = true; body = body.substring(2) }
        else if (body.startsWith("|")) { startAnchor = true; body = body.substring(1) }
        if (body.endsWith("|")) { endAnchor = true; body = body.dropLast(1) }
        if (body.startsWith("*")) { hostAnchor = false; startAnchor = false }
        if (body.endsWith("*")) endAnchor = false
        val segs = body.split('*').filter { it.isNotEmpty() }.toTypedArray()
        if (segs.isEmpty()) return null
        return Pattern(hostAnchor, startAnchor, endAnchor, segs, null)
    }

    /** Longest literal token guaranteed to appear as a whole token in every matching URL. */
    private fun pickToken(p: String): String? {
        var body = p
        var anchoredStart = false
        var anchoredEnd = false
        if (body.startsWith("||")) { anchoredStart = true; body = body.substring(2) }
        else if (body.startsWith("|")) { anchoredStart = true; body = body.substring(1) }
        if (body.endsWith("|")) { anchoredEnd = true; body = body.dropLast(1) }
        var best: String? = null
        var bestScore = 0
        var i = 0
        while (i < body.length) {
            if (!isTokenChar(body[i])) { i++; continue }
            var j = i
            while (j < body.length && isTokenChar(body[j])) j++
            val okStart = if (i == 0) anchoredStart else body[i - 1] != '*'
            val okEnd = if (j == body.length) anchoredEnd else body[j] != '*'
            if (okStart && okEnd && j - i >= 2) {
                val tok = body.substring(i, j)
                val score = if (tok in COMMON_TOKENS) 1 else 10 + tok.length
                if (score > bestScore) { bestScore = score; best = tok }
            }
            i = j
        }
        return best
    }

    private fun anchoredHost(p: String): String? {
        if (!p.startsWith("||")) return null
        var h = p.substring(2).lowercase()
        if (h.endsWith("^|")) h = h.dropLast(2) else if (h.endsWith("^")) h = h.dropLast(1)
        return h.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' } }
    }

    companion object {
        const val T_SCRIPT = 1
        const val T_IMAGE = 2
        const val T_STYLESHEET = 4
        const val T_SUBDOCUMENT = 8
        const val T_XHR = 16
        const val T_MEDIA = 32
        const val T_FONT = 64
        const val T_OBJECT = 128
        const val T_WEBSOCKET = 256
        const val T_PING = 512
        const val T_OTHER = 1024
        const val T_POPUP = 2048
        const val T_DOCUMENT = 4096
        const val T_ALL_RESOURCES = 2047
        const val T_ALL = T_ALL_RESOURCES or T_POPUP or T_DOCUMENT

        /** WebView does not expose the request type; unknown requests may be any of these. */
        const val T_UNKNOWN = T_SCRIPT or T_XHR or T_OTHER or T_OBJECT or T_PING or T_WEBSOCKET or T_MEDIA or T_FONT

        private const val FIRST = 1
        private const val THIRD = 2

        private val TYPE_NAMES = mapOf(
            "script" to T_SCRIPT, "image" to T_IMAGE, "stylesheet" to T_STYLESHEET, "css" to T_STYLESHEET,
            "subdocument" to T_SUBDOCUMENT, "frame" to T_SUBDOCUMENT, "xmlhttprequest" to T_XHR, "xhr" to T_XHR,
            "media" to T_MEDIA, "font" to T_FONT, "object" to T_OBJECT, "object-subrequest" to T_OBJECT,
            "websocket" to T_WEBSOCKET, "ping" to T_PING, "beacon" to T_PING, "other" to T_OTHER,
            "popup" to T_POPUP,
        )

        private val COMMON_TOKENS = setOf("http", "https", "www", "com", "net", "org", "ru", "js", "html", "php", "static", "cdn")
        private val FILE_EXT = setOf("js", "css", "php", "html", "htm", "gif", "png", "jpg", "jpeg", "swf", "json", "xml", "txt", "mp4", "aspx", "asp", "cgi", "svg", "webp")
        private val SLDS = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "ltd", "plc", "mil", "nic", "msk", "spb")
        private val PROCEDURAL = listOf(
            ":-abp-", ":has-text", ":contains(", ":matches-css", ":xpath", ":upward", ":remove", ":style(",
            ":min-text-length", ":watch-attr", ":matches-path", ":others", ":nth-ancestor", ":matches-attr",
            ":matches-prop", ":if(", ":if-not", "[-ext-", ":-ext-", ":matches-media", ":shadow",
        )

        private fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

        private fun isSeparator(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        private fun isPlainHost(s: String): Boolean {
            if (s.length < 4 || !s.contains('.') || s.startsWith('.') || s.endsWith('.')) return false
            if (!s.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }) return false
            val tld = s.substringAfterLast('.')
            return tld.length >= 2 && tld.all { it in 'a'..'z' } && tld !in FILE_EXT
        }

        private fun isSupportedSelector(sel: String) =
            sel.isNotEmpty() && sel.length < 500 && '{' !in sel && '}' !in sel && PROCEDURAL.none { sel.contains(it) }

        fun domainMatches(host: String, d: String): Boolean {
            if (d.endsWith(".*")) {
                val base = d.dropLast(1) // "google."
                return host.startsWith(base) || host.contains(".$base")
            }
            return host == d || host.length > d.length && host.endsWith(d) && host[host.length - d.length - 1] == '.'
        }

        fun inHostSet(set: Set<String>, host: String): Boolean {
            var h = host
            while (true) {
                if (h in set) return true
                val dot = h.indexOf('.')
                if (dot < 0) return false
                h = h.substring(dot + 1)
            }
        }

        /** Approximate registrable domain (eTLD+1) without shipping the public suffix list. */
        fun baseDomain(h: String): String {
            val last = h.lastIndexOf('.')
            if (last <= 0) return h
            val second = h.lastIndexOf('.', last - 1)
            if (second < 0) return h
            if (h.length - last - 1 == 2 && h.substring(second + 1, last) in SLDS) {
                return h.substring(h.lastIndexOf('.', second - 1) + 1)
            }
            return h.substring(second + 1)
        }
    }
}

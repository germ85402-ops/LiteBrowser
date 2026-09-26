package app.svetlo.filter

/** Parsing of uBO `+js(...)`, AdGuard `//scriptlet(...)` and ABP `#$#` snippet calls into canonical form. */
internal object Scriptlets {
    private val ALIASES: Map<String, String> = HashMap<String, String>().apply {
        fun a(canonical: String, vararg names: String) {
            put(canonical.lowercase(), canonical)
            names.forEach { put(it.lowercase(), canonical) }
        }
        a("set-constant", "set", "override-property-read")
        a("abort-on-property-read", "aopr")
        a("abort-on-property-write", "aopw")
        a("abort-current-script", "acs", "abort-current-inline-script", "acis")
        a("abort-on-stack-trace", "aost")
        a("no-setTimeout-if", "nostif", "prevent-setTimeout", "setTimeout-defuser", "std")
        a("no-setInterval-if", "nosiif", "prevent-setInterval", "setInterval-defuser", "sid")
        a("adjust-setTimeout", "nano-setTimeout-booster", "nano-stb")
        a("adjust-setInterval", "nano-setInterval-booster", "nano-sib")
        a("prevent-addEventListener", "aeld", "addEventListener-defuser", "prevent-listener")
        a("json-prune")
        a("remove-attr", "ra")
        a("remove-class", "rc")
        a("no-window-open-if", "nowoif", "window.open-defuse", "prevent-window-open")
        a("prevent-fetch", "no-fetch-if")
        a("prevent-xhr", "no-xhr-if")
        a("set-cookie")
        a("set-local-storage-item")
        a("noeval-if", "noeval", "prevent-eval-if")
        a("disable-newtab-links")
    }

    /** Canonical library name, or null for unknown and `trusted-*` scriptlets. */
    fun canonicalName(raw: String): String? {
        val n = raw.trim().lowercase().removePrefix("ubo-").removePrefix("abp-").removeSuffix(".js")
        if (n.startsWith("trusted-")) return null
        return ALIASES[n]
    }

    /**
     * Parses the inside of `+js(...)` or `//scriptlet(...)`. Returns an empty list for `+js()`
     * (exception that disables all scriptlets), null when unsupported.
     */
    fun parseCall(inner: String): List<String>? {
        val args = splitArgs(inner) ?: return null
        if (args.isEmpty() || args.size == 1 && args[0].isEmpty()) return emptyList()
        val name = canonicalName(args[0]) ?: return null
        var rest = args.drop(1)
        val raw = args[0].trim().lowercase().removePrefix("ubo-").removeSuffix(".js")
        // Legacy window.open-defuse(1|0, pattern) / prevent-window-open(1|0, pattern).
        if (name == "no-window-open-if" && (raw == "window.open-defuse" || raw == "prevent-window-open") &&
            rest.firstOrNull() in setOf("0", "1", "true", "false")
        ) {
            val neg = rest[0] == "0" || rest[0] == "false"
            rest = listOf((if (neg) "!" else "") + rest.getOrElse(1) { "" })
        }
        while (rest.isNotEmpty() && rest.last().isEmpty()) rest = rest.dropLast(1)
        return listOf(name) + rest
    }

    /** `name arg 'quoted arg'; name2 ...` (ABP snippet filter body). */
    fun parseAbp(body: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        val cur = ArrayList<String>()
        val tok = StringBuilder()
        var inTok = false
        var quote = false
        fun endTok() { if (inTok) cur.add(tok.toString()); tok.setLength(0); inTok = false }
        fun endCall() {
            endTok()
            if (cur.isNotEmpty()) canonicalName(cur[0])?.let { out.add(listOf(it) + cur.drop(1)) }
            cur.clear()
        }
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { tok.append(body[i + 1]); inTok = true; i++ }
                quote -> if (c == '\'') quote = false else tok.append(c)
                c == '\'' -> { quote = true; inTok = true }
                c == ';' -> endCall()
                c.isWhitespace() -> endTok()
                else -> { tok.append(c); inTok = true }
            }
            i++
        }
        endCall()
        return out
    }

    /**
     * Comma-separated arguments; unquoted args are trimmed and `\,` is a literal comma; args wrapped in
     * ', " or ` keep their content verbatim except for an escaped quote character.
     */
    fun splitArgs(s: String): List<String>? {
        val out = ArrayList<String>()
        var i = 0
        val n = s.length
        while (true) {
            while (i < n && s[i].isWhitespace()) i++
            val sb = StringBuilder()
            if (i < n && (s[i] == '\'' || s[i] == '"' || s[i] == '`')) {
                val q = s[i++]
                while (i < n && s[i] != q) {
                    if (s[i] == '\\' && i + 1 < n && s[i + 1] == q) { sb.append(q); i += 2 } else sb.append(s[i++])
                }
                if (i >= n) return null
                i++
                while (i < n && s[i].isWhitespace()) i++
                if (i < n && s[i] != ',') return null
                out.add(sb.toString())
            } else {
                while (i < n && s[i] != ',') {
                    if (s[i] == '\\' && i + 1 < n && s[i + 1] == ',') { sb.append(','); i += 2 } else sb.append(s[i++])
                }
                out.add(sb.toString().trim())
            }
            if (i >= n) break
            i++ // comma
        }
        return out
    }

    fun encode(call: List<String>) = call.joinToString("\u0000")
    fun decode(s: String) = s.split('\u0000')
}

/** Builds the JavaScript injected into pages from the sectioned assets/adblock.js library. */
internal class PageScripts(library: String) {
    private val sections: Map<String, String> = HashMap<String, String>().apply {
        var name: String? = null
        val sb = StringBuilder()
        fun end() { name?.let { put(it, sb.toString()) }; sb.setLength(0) }
        for (line in library.lineSequence()) {
            if (line.startsWith("//#")) { end(); name = line.substring(3).trim() }
            else if (name != null && !line.startsWith("//")) sb.append(line).append('\n')
        }
        end()
    }

    /** Scriptlets block guarded by `window.__svS`, or null when nothing applies. */
    fun scriptlets(calls: List<List<String>>): String? {
        val known = calls.filter { it.isNotEmpty() && it[0] in sections }
        if (known.isEmpty()) return null
        val sb = StringBuilder("(function(){if(window.__svS)return;window.__svS=1;var S={};\n")
        sb.append(sections["util"].orEmpty())
        known.map { it[0] }.distinct().forEach { sb.append(sections[it]) }
        sb.append("var R=[")
        known.forEachIndexed { k, c ->
            if (k > 0) sb.append(',')
            c.joinTo(sb, ",", "[", "]") { Procedural.jsString(it) }
        }
        sb.append("];for(var i=0;i<R.length;i++)try{S[R[i][0]].apply(null,R[i].slice(1))}catch(e){}})();\n")
        return sb.toString()
    }

    /** Procedural engine block guarded by `window.__svP`, or null. */
    fun procedural(rules: List<String>): String? {
        if (rules.isEmpty()) return null
        val engine = sections["proc"] ?: return null
        return engine.trimEnd().removeSuffix(";") + "([" + rules.joinToString(",") + "]);\n"
    }

    /** Style element with hiding CSS, created once per document. */
    fun style(css: String): String? {
        if (css.isEmpty()) return null
        return "(function(){if(document.getElementById('__lb_css'))return;var s=document.createElement('style');" +
            "s.id='__lb_css';s.textContent=" + Procedural.jsString(css) +
            ";(document.head||document.documentElement).appendChild(s);})();\n"
    }

    fun page(css: String, extras: FilterEngine.Extras): String? {
        val parts = listOfNotNull(style(css + extras.css), scriptlets(extras.scriptlets), procedural(extras.procedural))
        return if (parts.isEmpty()) null else parts.joinToString("")
    }
}

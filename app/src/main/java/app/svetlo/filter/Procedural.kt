package app.svetlo.filter

/**
 * Compiles uBO/AdGuard/ABP procedural cosmetic selectors into the compact array form executed by
 * the "proc" section of assets/adblock.js:
 * selector = ["css", task...]; task = [op, args...]; rule = [selector, action, styleArg, nativeCss|0].
 */
internal object Procedural {
    const val HIDE = 0
    const val REMOVE = 1
    const val STYLE = 2

    private const val TOP = 0
    private const val HAS = 1
    private const val NOT = 2

    class Sel(val css: String, val tasks: List<List<Any>>, val native: String?)

    class Rule(val sel: Sel, val action: Int, val style: String) {
        /** Appends CSS (plain or `@supports`-guarded native `:has`) and/or a JS rule literal. */
        fun emit(css: StringBuilder, js: MutableList<String>) {
            val decl = if (action == STYLE) style else "display:none!important"
            if (sel.tasks.isEmpty() && action != REMOVE) {
                css.append(sel.css).append('{').append(decl).append("}\n")
                return
            }
            if (sel.native != null && action != REMOVE) {
                css.append("@supports selector(:has(*)){").append(sel.native).append('{').append(decl).append("}}\n")
            }
            js.add("[" + toJs(sel) + "," + action + "," + jsString(style) + "," + (sel.native?.let(::jsString) ?: "0") + "]")
        }
    }

    private val UNSUPPORTED = setOf(
        "others", "shadow", "matches-attr", "matches-prop", "matches-media", "-abp-properties",
        "if-matches-path", "-ext-has", "-ext-contains", "-ext-matches-css",
    )
    private val NAMES = setOf(
        "has", "-abp-has", "if", "if-not", "not", "has-text", "contains", "-abp-contains", "matches-css",
        "matches-css-before", "matches-css-after", "upward", "nth-ancestor", "xpath", "min-text-length",
        "watch-attr", "remove", "style", "matches-path",
    )
    /** Markers that route a domain-specific rule to the procedural engine. */
    val MARKERS = listOf(
        ":has(", ":-abp-has(", ":if(", ":if-not(", ":has-text(", ":contains(", ":-abp-contains(", ":matches-css",
        ":upward(", ":nth-ancestor(", ":xpath(", ":min-text-length(", ":watch-attr(", ":remove(", ":style(", ":matches-path(",
    )

    fun isProcedural(sel: String) = MARKERS.any { sel.contains(it) }

    fun compile(src: String): Rule? {
        val action = IntArray(1)
        val style = arrayOf("")
        val sel = runCatching { parse(src, TOP, action, style) }.getOrNull() ?: return null
        return Rule(sel, action[0], style[0])
    }

    private fun parse(s: String, ctx: Int, action: IntArray?, style: Array<String>?): Sel? {
        val pending = StringBuilder()
        val native = StringBuilder()
        var nativeOk = true
        var prefix: String? = null
        var path: String? = null
        val tasks = ArrayList<List<Any>>()
        var bracket = 0
        var quote = '\u0000'
        var i = 0

        fun lit(t: CharSequence) { pending.append(t); native.append(t) }
        fun flush() {
            if (prefix == null) prefix = pending.toString()
            else spath(pending)?.let { tasks.add(listOf("s", it)) }
            pending.setLength(0)
        }
        fun add(t: List<Any>, nat: String?) {
            flush()
            tasks.add(t)
            if (nat == null) nativeOk = false else native.append(nat)
        }

        while (i < s.length) {
            val c = s[i]
            if (quote != '\u0000') {
                if (c == '\\' && i + 1 < s.length) { lit(s.substring(i, i + 2)); i += 2; continue }
                if (c == quote) quote = '\u0000'
                lit(c.toString()); i++; continue
            }
            when {
                c == '\\' && i + 1 < s.length -> { lit(s.substring(i, i + 2)); i += 2; continue }
                c == '[' -> bracket++
                c == ']' -> bracket--
                (c == '"' || c == '\'') && bracket > 0 -> quote = c
                c == ':' && bracket == 0 && i + 1 < s.length && s[i + 1] != ':' -> {
                    var j = i + 1
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-')) j++
                    val name = s.substring(i + 1, j)
                    if (name in UNSUPPORTED) return null
                    val hasArg = j < s.length && s[j] == '('
                    if (!hasArg || name !in NAMES) {
                        if (hasArg) {
                            val end = closeParen(s, j)
                            if (end < 0) return null
                            val arg = s.substring(j + 1, end)
                            if (isProcedural(arg)) return null // e.g. :is(:has-text(x))
                            lit(s.substring(i, end + 1)); i = end + 1
                        } else { lit(s.substring(i, j)); i = j }
                        continue
                    }
                    val end = closeParen(s, j)
                    if (end < 0) return null
                    val arg = s.substring(j + 1, end)
                    i = end + 1
                    when (name) {
                        "not" -> if (!isProcedural(arg)) lit(":not($arg)") else {
                            val sub = parse(arg, NOT, null, null) ?: return null
                            add(listOf("n", sub), sub.native?.let { ":not($it)" })
                        }
                        "has", "-abp-has", "if" -> {
                            val sub = parse(arg, HAS, null, null) ?: return null
                            add(listOf("h", if (sub.tasks.isEmpty()) sub.css else sub), sub.native?.let { ":has($it)" })
                        }
                        "if-not" -> {
                            val sub = parse(arg, HAS, null, null) ?: return null
                            val has = listOf<Any>("h", if (sub.tasks.isEmpty()) sub.css else sub)
                            add(listOf("n", Sel("", listOf(has), null)), sub.native?.let { ":not(:has($it))" })
                        }
                        "has-text", "contains", "-abp-contains" -> {
                            if (arg.isEmpty()) return null
                            add(listOf("t", arg), null)
                        }
                        "min-text-length" -> add(listOf("l", arg.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null), null)
                        "matches-css", "matches-css-before", "matches-css-after" -> {
                            var a = arg
                            var pseudo = when (name) { "matches-css-before" -> "::before"; "matches-css-after" -> "::after"; else -> "" }
                            Regex("^\\s*(before|after)\\s*,").find(a)?.let { pseudo = "::" + it.groupValues[1]; a = a.substring(it.range.last + 1) }
                            val colon = a.indexOf(':')
                            if (colon <= 0) return null
                            val prop = a.substring(0, colon).trim()
                            val (src, flags) = cssValuePattern(a.substring(colon + 1).trim())
                            add(listOf("c", pseudo, prop, src, flags), null)
                        }
                        "upward", "nth-ancestor" -> {
                            val n = arg.trim().toIntOrNull()
                            val v: Any = when {
                                n != null && n in 1..255 -> n
                                name == "upward" && n == null && arg.isNotBlank() -> arg.trim()
                                else -> return null
                            }
                            add(listOf("u", v), null)
                        }
                        "xpath" -> { if (arg.isBlank()) return null; add(listOf("x", arg.trim()), null) }
                        "watch-attr" -> Unit
                        // Page condition, may precede the CSS prefix; evaluated before other steps.
                        "matches-path" -> { if (ctx != TOP || arg.isEmpty()) return null; path = arg; nativeOk = false }
                        "remove", "style" -> {
                            if (action == null || style == null || s.substring(i).isNotBlank()) return null
                            if (name == "remove") action[0] = REMOVE
                            else {
                                val st = arg.trim()
                                if (st.isEmpty() || !isSafeStyle(st)) return null
                                action[0] = STYLE; style[0] = st
                            }
                        }
                    }
                    continue
                }
            }
            lit(c.toString()); i++
        }
        if (prefix == null) { prefix = pending.toString(); pending.setLength(0) } else flush()

        val p = prefix!!.trim()
        val css = when (ctx) {
            HAS -> when {
                p.isEmpty() -> if (tasks.isEmpty()) return null else " *"
                p[0] == '>' || p[0] == '+' || p[0] == '~' -> p
                else -> " $p"
            }
            TOP -> if (p.isEmpty() && tasks.firstOrNull()?.get(0) != "x") return null else p
            else -> p
        }
        path?.let { tasks.add(0, listOf("p", it)) }
        val nat = native.toString().trim()
        return Sel(css, tasks, if (nativeOk && nat.isNotEmpty() && (ctx != TOP || tasks.isNotEmpty())) nat else null)
    }

    /** Relative CSS left after a procedural step: combinator path or compound filter on the same element. */
    private fun spath(raw: CharSequence): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return when {
            t[0] == '>' || t[0] == '+' || t[0] == '~' -> t.toString()
            raw[0].isWhitespace() -> " $t"
            else -> t.toString()
        }
    }

    /** Index of the `)` closing the `(` at [open]; backslash-escaped parens are skipped. */
    private fun closeParen(s: String, open: Int): Int {
        var depth = 0
        var i = open
        var bracket = 0
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                '(' -> if (bracket == 0) depth++
                ')' -> if (bracket == 0 && --depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** `/re/flags` stays a regex; a plain value is an anchored literal with `*` wildcards. */
    private fun cssValuePattern(v: String): Pair<String, String> {
        Regex("^/(.+)/([imsu]*)$").find(v)?.let { return it.groupValues[1] to it.groupValues[2] }
        val sb = StringBuilder("^")
        for (ch in v) when (ch) {
            '*' -> sb.append(".*")
            in "\\^$.|?+()[]{}/" -> sb.append('\\').append(ch)
            else -> sb.append(ch)
        }
        return sb.append('$').toString() to ""
    }

    fun isSafeStyle(st: String) = '{' !in st && '}' !in st && '\\' !in st && "/*" !in st && !st.contains("url(", ignoreCase = true) &&
        !st.contains("expression", ignoreCase = true) && '<' !in st

    fun toJs(sel: Sel): String {
        val sb = StringBuilder("[").append(jsString(sel.css))
        for (t in sel.tasks) {
            sb.append(",[")
            t.forEachIndexed { k, a ->
                if (k > 0) sb.append(',')
                when (a) {
                    is Sel -> sb.append(toJs(a))
                    is Int -> sb.append(a)
                    else -> sb.append(jsString(a.toString()))
                }
            }
            sb.append(']')
        }
        return sb.append(']').toString()
    }

    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '<' -> sb.append("\\u003c")
            ch < ' ' || ch == '\u2028' || ch == '\u2029' -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }
}

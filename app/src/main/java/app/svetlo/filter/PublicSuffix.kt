package app.svetlo.filter

import java.net.IDN

/** Public Suffix List rules, including private hosting domains, wildcards and exceptions. */
class PublicSuffix(text: String) {
    private val exact = HashSet<String>()
    private val wildcards = HashSet<String>()
    private val exceptions = HashSet<String>()
    init {
        text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("//") }.forEach {
            when {
                it.startsWith("!") -> exceptions.add(ascii(it.drop(1)))
                it.startsWith("*.") -> wildcards.add(ascii(it.drop(2)))
                else -> exact.add(ascii(it))
            }
        }
    }
    fun domain(host: String): String {
        val h = ascii(host.trimEnd('.'))
        if (h.contains(':') || h.all { it.isDigit() || it == '.' }) return h
        val parts = h.split('.')
        var suffix = 1
        for (i in parts.indices) {
            val tail = parts.drop(i).joinToString(".")
            val n = parts.size - i
            if (tail in exceptions) { suffix = n - 1; break }
            if (tail in exact) suffix = maxOf(suffix, n)
            if (i > 0 && tail in wildcards) suffix = maxOf(suffix, n + 1)
        }
        return parts.takeLast((suffix + 1).coerceAtMost(parts.size)).joinToString(".")
    }
    private fun ascii(s: String) = runCatching { IDN.toASCII(s).lowercase(java.util.Locale.ROOT) }.getOrDefault(s.lowercase(java.util.Locale.ROOT))
}

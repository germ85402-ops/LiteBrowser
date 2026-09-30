package app.svetlo

import java.net.URI
import java.util.Locale

/** Security decisions are scoped to an origin, including its scheme and effective port. */
object Origin {
    fun of(url: String): String? = runCatching {
        val u = URI(url)
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase(Locale.ROOT) ?: return null
        val port = if (u.port >= 0) u.port else if (scheme == "https") 443 else 80
        "$scheme://$host:$port"
    }.getOrNull()

    fun same(a: String, b: String) = of(a)?.let { it == of(b) } == true
}

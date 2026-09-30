package app.svetlo

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny HTTP/1.0 server (thread per connection, Range + per-path delays) for downloader tests:
 * android.jar on the test classpath hides com.sun.net.httpserver.
 */
class TestServer : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val receivedHeaders = ConcurrentHashMap<String, Map<String, String>>()
    val redirects = ConcurrentHashMap<String, String>()
    val routes = ConcurrentHashMap<String, ByteArray>()
    val delays = ConcurrentHashMap<String, Long>()
    val requests = ConcurrentHashMap<String, AtomicInteger>()
    private val active = AtomicInteger()
    val maxActive = AtomicInteger()
    val base get() = "http://127.0.0.1:${server.localPort}"

    init {
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { serve(s) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) = s.use {
        val reader = it.getInputStream().bufferedReader()
        val path = reader.readLine().split(' ')[1].substringBefore('?')
        var range: String? = null
        val headers = HashMap<String, String>()
        while (true) {
            val h = reader.readLine().orEmpty()
            if (h.isEmpty()) break
            headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim()
            if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(':').trim().removePrefix("bytes=")
        }
        receivedHeaders[path] = headers
        requests.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        try {
            delays[path]?.let(Thread::sleep)
        } finally {
            active.decrementAndGet()
        }
        val body = routes[path]
        val out = it.getOutputStream()
        if (redirects.containsKey(path)) out.write("HTTP/1.0 302 Found\r\nLocation: ${redirects[path]}\r\nContent-Length: 0\r\n\r\n".toByteArray())
        else if (body == null) out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
        else if (range != null) {
            val from = range.substringBefore('-').toInt()
            val to = range.substringAfter('-').toInt()
            val part = body.copyOfRange(from, to + 1)
            out.write("HTTP/1.0 206 Partial Content\r\nContent-Range: bytes $from-$to/${body.size}\r\nContent-Length: ${part.size}\r\n\r\n".toByteArray())
            out.write(part)
        } else {
            out.write("HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            out.write(body)
        }
        out.flush()
    }

    override fun close() = server.close()
}

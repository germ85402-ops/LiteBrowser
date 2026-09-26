package com.litebrowser

import android.net.Uri
import java.util.Collections

data class MediaItem(val url: String, val isHls: Boolean) {
    val label: String
        get() {
            val u = Uri.parse(url)
            val name = u.lastPathSegment ?: u.host ?: url
            return (if (isHls) "[HLS] " else "") + name + "\n" + (u.host ?: "")
        }
}

/** Collects media URLs seen on the current page (network requests + DOM scan). */
class MediaDetector {
    private val items = Collections.synchronizedMap(LinkedHashMap<String, MediaItem>())

    fun clear() = items.clear()

    fun list(): List<MediaItem> = synchronized(items) { items.values.toList() }

    val count: Int get() = items.size

    /** Returns true if a new item was added. */
    fun offer(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val path = Uri.parse(url).path?.lowercase() ?: return false
        val item = when {
            path.endsWith(".m3u8") -> MediaItem(url, true)
            DIRECT_EXT.any { path.endsWith(it) } -> MediaItem(url, false)
            else -> return false
        }
        // Same file is often requested repeatedly with different Range/query params.
        val key = url.substringBefore('?')
        return items.putIfAbsent(key, item) == null
    }

    /** Adds a URL found in a <video>/<audio> element regardless of extension. */
    fun offerDom(url: String): Boolean {
        if (offer(url)) return true
        if (!url.startsWith("http")) return false
        return items.putIfAbsent(url.substringBefore('?'), MediaItem(url, false)) == null
    }

    companion object {
        private val DIRECT_EXT = listOf(".mp4", ".webm", ".mkv", ".mov", ".3gp", ".m4v", ".mp3", ".m4a", ".ogg")

        const val SCAN_JS = """(function(){var r=[];
          document.querySelectorAll('video,video source,audio,audio source').forEach(function(e){
            var s=e.currentSrc||e.src; if(s&&s.indexOf('blob:')!==0)r.push(s);});
          return JSON.stringify(r);})()"""
    }
}

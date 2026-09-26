package app.svetlo

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

class PageError(val url: String, val title: String, val text: String)

class Tab(var url: String, var title: String = "") {
    var web: WebView? = null
    var favicon: Bitmap? = null
    var thumbnail: Bitmap? = null
    var progress = 100
    var desktop = false
    var loaded = false
    var parent: Tab? = null
    var cameFromNtp = false
    var isPopup = false
    var popupChecked = false
    var openerHost: String? = null
    /** WebView history restored from disk, applied when the tab is first shown. */
    var savedState: Bundle? = null
    var error: PageError? = null
    /** SourceBuffer MIME types the page created; non-empty means the page streams through MSE. */
    val mseTypes: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    /** Next page load should start MSE recording from its first segment. */
    var mseArm = false

    @Volatile var pageHost: String? = null
    val blocked = AtomicInteger()
    val media = MediaDetector()

    val isNtp get() = url == NTP
    val host: String? get() = if (isNtp) null else Uri.parse(url).host?.lowercase()

    fun displayTitle() = when {
        isNtp -> "Новая вкладка"
        title.isNotBlank() -> title
        else -> host ?: url
    }

    companion object {
        const val NTP = "about:newtab"
    }
}

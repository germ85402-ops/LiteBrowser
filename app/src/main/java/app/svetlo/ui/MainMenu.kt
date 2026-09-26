package app.svetlo.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.PopupWindow
import app.svetlo.MainActivity
import app.svetlo.R
import app.svetlo.Tab
import app.svetlo.data.BrowserDb

/** Main overflow menu: quick action row plus a list of items. */
class MainMenu(private val act: MainActivity) {
    fun show(anchor: View, tab: Tab, bottom: Boolean) {
        val v = act.layoutInflater.inflate(R.layout.menu_main, null)
        val pw = PopupWindow(v, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(act.getDrawable(R.drawable.bg_popup))
        pw.elevation = act.dp(12).toFloat()
        pw.animationStyle = if (bottom) R.style.MenuAnimBottom else R.style.MenuAnimTop

        val page = !tab.isNtp
        fun item(id: Int, enabled: Boolean = true, action: () -> Unit) {
            val view = v.findViewById<View>(id)
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.35f
            view.setOnClickListener { pw.dismiss(); action() }
        }

        item(R.id.mForward, tab.web?.canGoForward() == true) { tab.web?.goForward() }
        v.findViewById<ImageButton>(R.id.mBookmark).setImageResource(
            if (page && BrowserDb.isBookmarked(tab.url)) R.drawable.ic_star else R.drawable.ic_star_border,
        )
        item(R.id.mBookmark, page) { act.toggleBookmark() }
        item(R.id.mVideos, page) { act.showVideos() }
        item(R.id.mInfo, page) { act.showSiteInfo() }
        v.findViewById<ImageButton>(R.id.mRefresh).setImageResource(
            if (tab.progress < 100 && page) R.drawable.ic_close else R.drawable.ic_refresh,
        )
        item(R.id.mRefresh, page) { act.reloadOrStop() }
        item(R.id.mNewTab) { act.newTab(null) }
        item(R.id.mIncognito) { act.openIncognito(null) }
        item(R.id.mRecent) { act.showRecentTabs() }
        item(R.id.mTranslate, page) { act.translatePage() }
        item(R.id.mAddHome, page && android.os.Build.VERSION.SDK_INT >= 26) { act.addToHomeScreen() }
        item(R.id.mHistory) { act.openLibrary(bookmarks = false) }
        if (act.incognito) v.findViewById<View>(R.id.mRecent).visibility = View.GONE
        item(R.id.mBookmarks) { act.openLibrary(bookmarks = true) }
        item(R.id.mDownloads) { act.openDownloads() }
        item(R.id.mFind, page) { act.startFind() }
        item(R.id.mShare, page) { act.sharePage() }
        v.findViewById<CheckBox>(R.id.mDesktopCheck).isChecked = tab.desktop
        item(R.id.mDesktop, page) { act.toggleDesktop() }
        item(R.id.mAdblock) { act.openAdblockSettings() }
        item(R.id.mSettings) { act.openSettings() }

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val margin = act.dp(6)
        if (bottom) {
            val decorH = act.window.decorView.height
            pw.showAtLocation(anchor, Gravity.BOTTOM or Gravity.END, margin, decorH - loc[1] - anchor.height + margin)
        } else {
            pw.showAtLocation(anchor, Gravity.TOP or Gravity.END, margin, loc[1] + margin)
        }
    }
}

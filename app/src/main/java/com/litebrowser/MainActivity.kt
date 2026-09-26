package com.litebrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.litebrowser.data.BrowserDb
import com.litebrowser.data.Prefs
import com.litebrowser.filter.AdBlock
import com.litebrowser.ui.AdblockActivity
import com.litebrowser.ui.LibraryActivity
import com.litebrowser.ui.MainMenu
import com.litebrowser.ui.NewTabPage
import com.litebrowser.ui.SettingsActivity
import com.litebrowser.ui.Suggestions
import com.litebrowser.ui.TabSwitcher
import com.litebrowser.ui.color
import com.litebrowser.ui.dp
import com.litebrowser.ui.hideKeyboard
import com.litebrowser.ui.showKeyboard
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    internal val tabs = ArrayList<Tab>()
    internal var current: Tab? = null
        private set

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var omnibox: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var siteIcon: ImageView
    private lateinit var btnClear: ImageButton
    private lateinit var shieldChip: TextView
    private lateinit var tabCount: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var mediaFab: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private lateinit var ntp: NewTabPage
    private lateinit var switcher: TabSwitcher
    private lateinit var suggestions: Suggestions
    private val menu by lazy { MainMenu(this) }

    private val main = Handler(Looper.getMainLooper())
    private val uiPending = AtomicBoolean()
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var savedUiFlags = 0
    private var pendingDownload: (() -> Unit)? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        toolbar = findViewById(R.id.toolbar)
        omnibox = findViewById(R.id.omnibox)
        urlBar = findViewById(R.id.urlBar)
        siteIcon = findViewById(R.id.siteIcon)
        btnClear = findViewById(R.id.btnClear)
        shieldChip = findViewById(R.id.shieldChip)
        tabCount = findViewById(R.id.tabCount)
        progress = findViewById(R.id.progress)
        webContainer = findViewById(R.id.webContainer)
        mediaFab = findViewById(R.id.mediaFab)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)

        ntp = NewTabPage(this, findViewById(R.id.ntp), ::navigate) { urlBar.showKeyboard() }
        switcher = TabSwitcher(
            this, findViewById(R.id.tabSwitcher), tabs, { current },
            onSelect = { selectTab(it); switcher.hide() },
            onClose = { closeTab(it); switcher.refresh() },
            onNew = { switcher.hide(); newTab(null) },
            onCloseAll = ::confirmCloseAll,
        )
        suggestions = Suggestions(this, findViewById<ListView>(R.id.suggestions), ::navigate) {
            urlBar.setText(it)
            urlBar.setSelection(urlBar.text.length)
        }
        setupToolbar()
        setupFindBar()
        applyToolbarPosition()

        restoreTabs()
        if (!handleIntent(intent) && tabs.isEmpty()) newTab(null, focus = false)
        if (current == null) selectTab(tabs.last())
    }

    // ------------------------------------------------------------------ toolbar

    private fun setupToolbar() {
        findViewById<View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnTabs).setOnClickListener { openSwitcher() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { v -> current?.let { menu.show(v, it, Prefs.bottomBar) } }
        btnClear.setOnClickListener { urlBar.text.clear() }
        shieldChip.setOnClickListener { showSiteInfo() }
        siteIcon.setOnClickListener { if (!urlBar.hasFocus()) showSiteInfo() }
        mediaFab.setOnClickListener { showVideos() }
        urlBar.setOnFocusChangeListener { _, focused ->
            omnibox.setBackgroundResource(if (focused) R.drawable.bg_omnibox_focused else R.drawable.bg_omnibox)
            if (focused) {
                val t = current
                urlBar.setText(if (t == null || t.isNtp) "" else t.url)
                urlBar.selectAll()
            } else {
                suggestions.hide()
            }
            refreshToolbar()
        }
        urlBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                if (!urlBar.hasFocus()) return
                btnClear.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
                suggestions.query(s.toString())
            }
        })
        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate(urlBar.text.toString()); true
            } else false
        }
    }

    private fun applyToolbarPosition() {
        val bars = listOf<View>(toolbar, findBar, progress)
        bars.forEach { content.removeView(it) }
        if (Prefs.bottomBar) bars.reversed().forEach { content.addView(it) }
        else bars.forEachIndexed { i, v -> content.addView(v, i) }
        (mediaFab.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.BOTTOM or Gravity.END
    }

    internal fun refreshToolbar() {
        val tab = current ?: return
        val focused = urlBar.hasFocus()
        if (!focused) urlBar.setText(if (tab.isNtp) "" else displayUrl(tab.url))
        btnClear.visibility = if (focused && urlBar.text.isNotEmpty()) View.VISIBLE else View.GONE
        siteIcon.setImageResource(
            when {
                focused || tab.isNtp -> R.drawable.ic_search
                tab.url.startsWith("https://") -> R.drawable.ic_lock
                else -> R.drawable.ic_info_warn
            },
        )
        val showShield = !focused && !tab.isNtp && Prefs.adblock
        shieldChip.visibility = if (showShield) View.VISIBLE else View.GONE
        if (showShield) {
            val off = AdBlock.isWhitelisted(tab.host)
            shieldChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (off) R.drawable.ic_shield_off else R.drawable.ic_shield_accent, 0, 0, 0,
            )
            shieldChip.text = if (off) "" else tab.blocked.get().toString()
        }
        tabCount.text = if (tabs.size > 99) ":)" else tabs.size.toString()
        val loading = tab.progress in 1..99 && !tab.isNtp
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        progress.progress = tab.progress
        val videos = tab.media.count
        mediaFab.visibility = if (videos > 0 && !tab.isNtp && !focused) View.VISIBLE else View.GONE
        mediaFab.text = if (videos == 1) "Скачать видео" else "Видео · $videos"
    }

    private fun displayUrl(url: String): String {
        val u = Uri.parse(url)
        val host = u.host ?: return url
        return host.removePrefix("www.")
    }

    /** Coalesces toolbar refreshes requested from WebView network threads. */
    private fun postRefresh(tab: Tab) {
        if (tab !== current || !uiPending.compareAndSet(false, true)) return
        main.postDelayed({ uiPending.set(false); refreshToolbar() }, 250)
    }

    // ------------------------------------------------------------------ tabs

    internal fun newTab(url: String?, background: Boolean = false, parent: Tab? = null, focus: Boolean = url == null): Tab {
        val tab = Tab(url ?: Tab.NTP)
        tab.parent = parent
        val idx = if (parent != null) (tabs.indexOf(parent) + 1).coerceIn(0, tabs.size) else tabs.size
        tabs.add(idx, tab)
        if (background) {
            ensureWeb(tab).apply { loadUrl(tab.url); onPause() }
            tab.loaded = true
            refreshToolbar()
        } else {
            selectTab(tab)
            if (focus && !switcher.isShown) main.post { urlBar.showKeyboard() }
        }
        return tab
    }

    internal fun selectTab(tab: Tab) {
        val prev = current
        if (prev != null && prev !== tab) {
            captureThumb(prev)
            prev.web?.apply { onPause(); visibility = View.GONE }
        }
        current = tab
        if (tab.isNtp) {
            tab.web?.visibility = View.GONE
            ntp.refresh()
            ntp.view.visibility = View.VISIBLE
        } else {
            val web = ensureWeb(tab)
            if (!tab.loaded) { web.loadUrl(tab.url); tab.loaded = true }
            web.visibility = View.VISIBLE
            web.onResume()
            ntp.view.visibility = View.GONE
        }
        if (findBar.visibility == View.VISIBLE) closeFind()
        urlBar.clearFocus()
        refreshToolbar()
    }

    internal fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        destroyWeb(tab)
        tabs.forEach { if (it.parent === tab) it.parent = tab.parent }
        if (tabs.isEmpty()) {
            current = null
            newTab(null, focus = false)
            return
        }
        if (current === tab) {
            current = null
            val next = tab.parent?.takeIf { it in tabs } ?: tabs[idx.coerceAtMost(tabs.size - 1)]
            selectTab(next)
        } else {
            refreshToolbar()
        }
    }

    private fun confirmCloseAll() {
        AlertDialog.Builder(this).setTitle("Закрыть все вкладки?")
            .setPositiveButton("Закрыть") { _, _ ->
                tabs.toList().forEach { destroyWeb(it) }
                tabs.clear()
                current = null
                switcher.hide()
                newTab(null, focus = false)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun openSwitcher() {
        current?.let { captureThumb(it) }
        urlBar.clearFocus()
        urlBar.hideKeyboard()
        switcher.show()
    }

    private fun captureThumb(tab: Tab) {
        val v: View = if (tab.isNtp) ntp.view else tab.web ?: return
        if (v.width == 0 || v.height == 0 || v.visibility != View.VISIBLE) return
        runCatching {
            val scale = 0.4f
            val h = minOf(v.height, (v.width * 1.3f).toInt())
            val bmp = Bitmap.createBitmap((v.width * scale).toInt(), (h * scale).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
            v.draw(c)
            tab.thumbnail = bmp
        }
    }

    private fun saveTabs() {
        val arr = JSONArray()
        tabs.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
        Prefs.sp.edit().putString("tabs", arr.toString()).putInt("tab_cur", tabs.indexOf(current)).apply()
    }

    private fun restoreTabs() {
        val arr = runCatching { JSONArray(Prefs.sp.getString("tabs", "[]")) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("u")
            if (url.isEmpty() || url == "about:blank") continue
            tabs += Tab(url, o.optString("t"))
        }
        val cur = Prefs.sp.getInt("tab_cur", tabs.size - 1)
        tabs.getOrNull(cur)?.let { selectTab(it) }
    }

    // ------------------------------------------------------------------ navigation

    internal fun navigate(input: String) {
        if (input.isBlank()) return
        val url = Prefs.toUrl(input)
        val tab = current ?: newTab(null)
        if (tab.isNtp) tab.cameFromNtp = true
        tab.url = url
        tab.title = ""
        val web = ensureWeb(tab)
        web.loadUrl(url)
        tab.loaded = true
        urlBar.clearFocus()
        urlBar.hideKeyboard()
        switcher.hide()
        selectTab(tab)
        web.requestFocus()
    }

    private fun goHome() {
        val tab = current ?: return
        if (tab.isNtp) return
        destroyWeb(tab)
        tab.url = Tab.NTP
        tab.title = ""
        tab.cameFromNtp = false
        selectTab(tab)
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return false
        val cur = current
        if (cur != null && cur.isNtp) navigate(data) else newTab(data)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        switcher.hide()
        handleIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val tab = current
        when {
            fullscreenView != null -> exitFullscreen()
            switcher.isShown -> switcher.hide()
            urlBar.hasFocus() -> { urlBar.clearFocus(); urlBar.hideKeyboard() }
            findBar.visibility == View.VISIBLE -> closeFind()
            tab == null -> moveTaskToBack(true)
            !tab.isNtp && tab.web?.canGoBack() == true -> tab.web?.goBack()
            !tab.isNtp && tab.cameFromNtp -> goHome()
            tab.parent != null && tab.parent in tabs -> closeTab(tab)
            else -> moveTaskToBack(true)
        }
    }

    override fun onPause() {
        super.onPause()
        current?.web?.onPause()
        saveTabs()
        AdBlock.saveStats()
    }

    override fun onResume() {
        super.onResume()
        current?.web?.onResume()
        if (current?.isNtp == true) ntp.refresh()
        refreshToolbar()
    }

    override fun onDestroy() {
        tabs.forEach { destroyWeb(it) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ WebView

    private fun ensureWeb(tab: Tab): WebView = tab.web ?: createWeb(tab)

    private fun destroyWeb(tab: Tab) {
        tab.web?.let {
            webContainer.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        tab.web = null
        tab.loaded = false
        tab.progress = 100
        tab.media.clear()
        tab.blocked.set(0)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb(tab: Tab): WebView {
        val web = WebView(this)
        web.settings.apply {
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        applySettings(web, tab)
        web.webViewClient = TabClient(tab)
        web.webChromeClient = TabChrome(tab)
        web.setDownloadListener { url, _, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            AlertDialog.Builder(this).setTitle("Скачать файл?").setMessage(name)
                .setPositiveButton("Скачать") { _, _ -> downloadDirect(url, name, tab) }
                .setNegativeButton("Отмена", null).show()
            if (tab.web?.canGoBack() != true && tab.isPopup) closeTab(tab)
        }
        web.setOnLongClickListener { onLongPress(tab) }
        web.visibility = View.GONE
        webContainer.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tab.web = web
        return web
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(web: WebView, tab: Tab) {
        val s = web.settings
        s.javaScriptEnabled = Prefs.javascript
        s.textZoom = Prefs.textZoom
        s.userAgentString = if (tab.desktop) desktopUa(WebSettings.getDefaultUserAgent(this)) else null
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, !Prefs.blockThirdPartyCookies)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 33) {
            s.isAlgorithmicDarkeningAllowed = Prefs.darkPages
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            s.forceDark = if (night && Prefs.darkPages) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    private fun desktopUa(ua: String) = ua
        .replace(Regex("""\(Linux; Android [^)]*\)"""), "(X11; Linux x86_64)")
        .replace(" Mobile", "")
        .replace(Regex("""\s?Version/\d+\.\d+"""), "")
        .replace("; wv", "")

    internal fun toggleDesktop() {
        val tab = current ?: return
        tab.desktop = !tab.desktop
        tab.web?.let { applySettings(it, tab); it.reload() }
    }

    private inner class TabClient(private val tab: Tab) : WebViewClient() {
        private var injectedFor: String? = null

        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            val url = req.url.toString()
            if (req.isForMainFrame) {
                tab.pageHost = req.url.host?.lowercase()
                tab.blocked.set(0)
                tab.media.clear()
                if (tab.isPopup && !tab.popupChecked) {
                    tab.popupChecked = true
                    if (AdBlock.blocksNavigation(url, tab.openerHost)) {
                        AdBlock.totalBlocked.incrementAndGet()
                        main.post { closeTab(tab); toast("Всплывающая реклама заблокирована") }
                        return AdBlock.emptyResponse()
                    }
                }
                postRefresh(tab)
                return null
            }
            if (AdBlock.shouldBlock(req, tab.pageHost)) {
                tab.blocked.incrementAndGet()
                AdBlock.totalBlocked.incrementAndGet()
                postRefresh(tab)
                return AdBlock.emptyResponse()
            }
            if (tab.media.offer(url)) postRefresh(tab)
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val uri = req.url
            if (uri.scheme == "http" || uri.scheme == "https") {
                if (req.isForMainFrame && tab.pageHost != null && uri.host != tab.pageHost &&
                    AdBlock.blocksNavigation(uri.toString(), tab.pageHost)
                ) {
                    tab.blocked.incrementAndGet()
                    AdBlock.totalBlocked.incrementAndGet()
                    toast("Переход на рекламный сайт заблокирован")
                    return true
                }
                return false
            }
            openExternal(view, uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            injectedFor = null
            tab.url = url
            tab.pageHost = Uri.parse(url).host?.lowercase()
            tab.favicon = favicon
            if (tab === current) refreshToolbar()
        }

        override fun onPageCommitVisible(view: WebView, url: String) = inject(view, url)

        override fun onPageFinished(view: WebView, url: String) {
            inject(view, url)
            tab.url = url
            view.title?.let { if (it.isNotBlank() && !url.contains(it)) tab.title = it }
            BrowserDb.addVisit(url, tab.title)
            scanMedia(tab)
            if (tab === current) refreshToolbar()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            if (tab === current) refreshToolbar()
        }

        private fun inject(view: WebView, url: String) {
            if (injectedFor == url) return
            injectedFor = url
            AdBlock.pageScript(Uri.parse(url).host?.lowercase())?.let { view.evaluateJavascript(it, null) }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Recreate the crashed tab instead of letting the whole app die.
            val url = tab.url
            destroyWeb(tab)
            tab.url = url
            if (tab === current) selectTab(tab)
            return true
        }
    }

    private inner class TabChrome(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (tab === current) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
                progress.progress = newProgress
            }
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            tab.title = title
            BrowserDb.updateTitle(tab.url, title)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            tab.favicon = icon
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return blockPopup()
            val child = newTab(null, parent = tab, focus = false)
            child.url = "about:blank"
            child.isPopup = true
            child.openerHost = tab.pageHost
            child.loaded = true
            selectTab(child)
            (resultMsg.obj as WebView.WebViewTransport).webView = ensureWeb(child)
            resultMsg.sendToTarget()
            return true
        }

        private fun blockPopup(): Boolean {
            tab.blocked.incrementAndGet()
            AdBlock.totalBlocked.incrementAndGet()
            postRefresh(tab)
            return false
        }

        override fun onCloseWindow(window: WebView) {
            closeTab(tab)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) { callback.onCustomViewHidden(); return }
            fullscreenView = view
            fullscreenCallback = callback
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            content.visibility = View.GONE
            @Suppress("DEPRECATION")
            window.decorView.let {
                savedUiFlags = it.systemUiVisibility
                it.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }

        override fun onHideCustomView() = exitFullscreen()

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        root.removeView(v)
        content.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = savedUiFlags
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
    }

    private fun openExternal(view: WebView, uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, uri)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            startActivity(intent)
        } catch (_: Exception) {
            val fallback = runCatching {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            }.getOrNull()
            if (fallback != null) view.loadUrl(fallback) else toast("Нет приложения для открытия ссылки")
        }
    }

    // ------------------------------------------------------------------ page actions

    internal fun reloadOrStop() {
        val web = current?.web ?: return
        if ((current?.progress ?: 100) < 100) web.stopLoading() else web.reload()
    }

    internal fun toggleBookmark() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        if (BrowserDb.isBookmarked(tab.url)) {
            BrowserDb.removeBookmark(tab.url); toast("Закладка удалена")
        } else {
            BrowserDb.addBookmark(tab.url, tab.displayTitle()); toast("Добавлено в закладки")
        }
    }

    internal fun sharePage() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), null))
    }

    internal fun openLibrary(bookmarks: Boolean) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_BOOKMARKS, bookmarks), REQ_LIBRARY)
    }

    internal fun openSettings() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
    }

    internal fun openAdblockSettings() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, AdblockActivity::class.java), REQ_SETTINGS)
    }

    internal fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            toast("Файлы сохраняются в Download/LiteBrowser")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            REQ_LIBRARY -> {
                val url = data?.getStringExtra(LibraryActivity.EXTRA_URL) ?: return
                if (data.getBooleanExtra(LibraryActivity.EXTRA_NEW_TAB, false)) newTab(url) else navigate(url)
            }
            REQ_SETTINGS -> {
                applyToolbarPosition()
                tabs.forEach { t -> t.web?.let { applySettings(it, t) } }
                current?.web?.reload()
                refreshToolbar()
            }
        }
    }

    internal fun showSiteInfo() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        val host = tab.host ?: return
        val v = layoutInflater.inflate(R.layout.dialog_site, null)
        v.findViewById<TextView>(R.id.siteHost).text = host.removePrefix("www.")
        val secure = tab.url.startsWith("https://")
        v.findViewById<TextView>(R.id.siteSecurity).apply {
            text = if (secure) "Защищённое соединение" else "Незащищённое соединение"
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (secure) R.drawable.ic_lock else R.drawable.ic_info_warn, 0, 0, 0)
        }
        v.findViewById<TextView>(R.id.siteBlocked).text = tab.blocked.get().toString()
        val sw = v.findViewById<Switch>(R.id.siteSwitch)
        sw.isChecked = Prefs.adblock && !AdBlock.isWhitelisted(host)
        sw.isEnabled = Prefs.adblock
        val dialog = AlertDialog.Builder(this).setView(v).create()
        sw.setOnCheckedChangeListener { _, on ->
            AdBlock.setWhitelisted(host, !on)
            tab.web?.reload()
            refreshToolbar()
        }
        v.findViewById<View>(R.id.siteSettings).setOnClickListener { dialog.dismiss(); openAdblockSettings() }
        dialog.show()
    }

    // ------------------------------------------------------------------ find in page

    private fun setupFindBar() {
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                current?.web?.findAllAsync(s.toString())
                if (s.isEmpty()) findCount.text = ""
            }
        })
        findInput.setOnEditorActionListener { _, _, _ -> current?.web?.findNext(true); true }
        findViewById<View>(R.id.findPrev).setOnClickListener { current?.web?.findNext(false) }
        findViewById<View>(R.id.findNext).setOnClickListener { current?.web?.findNext(true) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
    }

    internal fun startFind() {
        val web = current?.web?.takeIf { current?.isNtp == false } ?: return
        web.setFindListener { active, count, _ -> findCount.text = if (count == 0) "0/0" else "${active + 1}/$count" }
        toolbar.visibility = View.GONE
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findInput.showKeyboard()
    }

    private fun closeFind() {
        current?.web?.clearMatches()
        findInput.hideKeyboard()
        findBar.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ media & downloads

    private fun scanMedia(tab: Tab, then: (() -> Unit)? = null) {
        val web = tab.web ?: return
        web.evaluateJavascript(MediaDetector.SCAN_JS) { result ->
            runCatching {
                val arr = JSONArray(JSONTokener(result).nextValue() as String)
                for (i in 0 until arr.length()) tab.media.offerDom(arr.getString(i))
            }
            if (tab === current) refreshToolbar()
            then?.invoke()
        }
    }

    internal fun showVideos() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        scanMedia(tab) {
            val items = tab.media.list()
            if (items.isEmpty()) {
                toast("Видео не найдено. Запустите воспроизведение и попробуйте снова.")
                return@scanMedia
            }
            AlertDialog.Builder(this)
                .setTitle("Видео на странице")
                .setItems(items.map { it.label }.toTypedArray()) { _, i -> startMediaDownload(tab, items[i]) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun startMediaDownload(tab: Tab, item: MediaItem) {
        if (item.isHls) {
            withStoragePermission {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                }
                HlsDownloadService.start(this, item.url, requestHeaders(item.url, tab))
                toast("Загрузка потока началась")
            }
        } else {
            downloadDirect(item.url, URLUtil.guessFileName(item.url, null, null), tab)
        }
    }

    private fun requestHeaders(url: String, tab: Tab?): HashMap<String, String> {
        val h = hashMapOf("User-Agent" to (tab?.web?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(this)))
        tab?.url?.takeIf { it.startsWith("http") }?.let { h["Referer"] = it }
        CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
        return h
    }

    private fun downloadDirect(url: String, name: String, tab: Tab?) = withStoragePermission {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "LiteBrowser/$name")
            requestHeaders(url, tab).forEach { (k, v) -> req.addRequestHeader(k, v) }
            getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Скачивание: $name")
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingDownload = action
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_STORAGE) return
        val action = pendingDownload
        pendingDownload = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
        else toast("Нужно разрешение на запись файлов")
    }

    // ------------------------------------------------------------------ context menu

    private fun onLongPress(tab: Tab): Boolean {
        val web = tab.web ?: return false
        val hit = web.hitTestResult
        val extra = hit.extra ?: return false
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> contextMenu(tab, extra, null)
            WebView.HitTestResult.IMAGE_TYPE -> contextMenu(tab, null, extra)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Handler(Looper.getMainLooper()) { m ->
                    contextMenu(tab, m.data.getString("url"), extra); true
                }.obtainMessage()
                web.requestFocusNodeHref(msg)
            }
            else -> return false
        }
        return true
    }

    private fun contextMenu(tab: Tab, link: String?, image: String?) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (link != null && link.startsWith("http")) {
            actions += "Открыть в новой вкладке" to { newTab(link, parent = tab); Unit }
            actions += "Открыть в фоновой вкладке" to { newTab(link, background = true, parent = tab); toast("Открыто в фоновой вкладке") }
            actions += "Копировать ссылку" to { copy(link) }
            actions += "Поделиться ссылкой" to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
            }
            actions += "Скачать по ссылке" to { downloadDirect(link, URLUtil.guessFileName(link, null, null), tab) }
        }
        if (image != null && image.startsWith("http")) {
            actions += "Открыть изображение в новой вкладке" to { newTab(image, parent = tab); Unit }
            actions += "Скачать изображение" to { downloadDirect(image, URLUtil.guessFileName(image, null, "image/*"), tab) }
            actions += "Копировать адрес изображения" to { copy(image) }
        }
        if (actions.isEmpty()) return
        val title = TextView(this).apply {
            text = link ?: image
            maxLines = 2
            textSize = 13f
            setTextColor(color(R.color.c_text2))
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        AlertDialog.Builder(this).setCustomTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", text))
        if (Build.VERSION.SDK_INT < 33) toast("Скопировано")
    }

    internal fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_STORAGE = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_FILE = 3
        private const val REQ_LIBRARY = 4
        private const val REQ_SETTINGS = 5
    }
}

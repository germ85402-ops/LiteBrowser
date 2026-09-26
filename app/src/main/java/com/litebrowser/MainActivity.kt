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
import android.speech.RecognizerIntent
import android.graphics.Rect
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.PopupMenu
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
import com.litebrowser.ui.GestureBar
import com.litebrowser.ui.LetterIcon
import com.litebrowser.ui.PullRefresh
import com.litebrowser.ui.Snackbar
import com.litebrowser.ui.TopCropImageView
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
import kotlin.math.abs

open class MainActivity : Activity() {
    open val incognito: Boolean get() = false

    internal val tabs = ArrayList<Tab>()
    internal var current: Tab? = null
        private set

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var toolbar: GestureBar
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
    private lateinit var btnMic: ImageButton
    private lateinit var zoomOverlay: TopCropImageView
    private lateinit var swipePeek: TopCropImageView
    private lateinit var pull: PullRefresh
    private lateinit var snackbar: Snackbar

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
    private var barsHidden = false
    private var scrollAccum = 0
    private var swipeTarget: Tab? = null
    private var lastTabCount = -1

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
        btnMic = findViewById(R.id.btnMic)
        zoomOverlay = findViewById(R.id.zoomOverlay)
        snackbar = Snackbar(this, root)
        snackbar.onShift = { dy ->
            listOf(mediaFab, findViewById<View>(R.id.tsNew)).forEach { it.animate().translationY(dy).setDuration(200).start() }
        }
        swipePeek = TopCropImageView(this).apply {
            setBackgroundColor(color(R.color.c_bg))
            visibility = View.GONE
            elevation = dp(2).toFloat()
        }
        webContainer.addView(swipePeek, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pull = PullRefresh(this, webContainer) { current?.web?.reload() }

        ntp = NewTabPage(this, findViewById(R.id.ntp), ::navigate, { urlBar.showKeyboard() }, incognito)
        switcher = TabSwitcher(
            this, findViewById(R.id.tabSwitcher), zoomOverlay, tabs, incognito, { current }, ::pageRect,
            onSelect = { selectTab(it) },
            onClose = { closeTab(it) },
            onNew = { switcher.hide(); newTab(null) },
            onCloseAll = ::closeAllTabs,
            onToggleMode = ::toggleMode,
        )
        suggestions = Suggestions(this, findViewById<ListView>(R.id.suggestions), ::navigate) {
            urlBar.setText(it)
            urlBar.setSelection(urlBar.text.length)
        }
        setupToolbar()
        setupFindBar()
        applyToolbarPosition()

        if (!incognito) restoreTabs()
        if (!handleIntent(intent) && tabs.isEmpty()) newTab(null, focus = false)
        if (current == null) selectTab(tabs.last())
    }

    // ------------------------------------------------------------------ toolbar

    private fun setupToolbar() {
        findViewById<View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnTabs).setOnClickListener { openSwitcher() }
        findViewById<View>(R.id.btnTabs).setOnLongClickListener { v -> showTabsPopup(v); true }
        btnMic.setOnClickListener { startVoiceSearch() }
        urlBar.setOnLongClickListener {
            val t = current
            if (urlBar.hasFocus() || t == null || t.isNtp) return@setOnLongClickListener false
            copy(t.url)
            true
        }
        toolbar.listener = object : GestureBar.Listener {
            override fun canSwipe() = !urlBar.hasFocus() && !switcher.isShown && fullscreenView == null
            override fun onSwipeMove(dx: Float) = tabSwipeMove(dx)
            override fun onSwipeEnd(dx: Float, vx: Float) = tabSwipeEnd(dx, vx)
            override fun onSwipeToSwitcher() = openSwitcher()
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { v -> current?.let { menu.show(v, it, Prefs.bottomBar) } }
        btnClear.setOnClickListener { urlBar.text.clear() }
        shieldChip.setOnClickListener { showSiteInfo() }
        siteIcon.setOnClickListener { if (!urlBar.hasFocus()) showSiteInfo() }
        mediaFab.setOnClickListener { showVideos() }
        urlBar.setOnFocusChangeListener { _, focused ->
            omnibox.setBackgroundResource(if (focused) R.drawable.bg_omnibox_focused else R.drawable.bg_omnibox)
            listOf(R.id.btnHome, R.id.btnTabs, R.id.btnMenu).forEach {
                findViewById<View>(it).visibility = if (focused) View.GONE else View.VISIBLE
            }
            if (focused) {
                showBars()
                val t = current
                urlBar.setText(if (t == null || t.isNtp) "" else t.url)
                urlBar.selectAll()
                if (t == null || t.isNtp) suggestions.zeroSuggest(clipboardText(), !incognito)
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
                btnMic.visibility = if (s.isEmpty()) View.VISIBLE else View.GONE
                suggestions.query(s.toString(), clipboardText(), !incognito)
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
        toolbar.atBottom = Prefs.bottomBar
        pull.enabled = Prefs.pullToRefresh
        showBars(animate = false)
        (mediaFab.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.BOTTOM or Gravity.END
    }

    internal fun refreshToolbar() {
        val tab = current ?: return
        val focused = urlBar.hasFocus()
        if (!focused) urlBar.setText(if (tab.isNtp) "" else displayUrl(tab.url))
        btnClear.visibility = if (focused && urlBar.text.isNotEmpty()) View.VISIBLE else View.GONE
        btnMic.visibility = if (focused && urlBar.text.isEmpty()) View.VISIBLE else View.GONE
        siteIcon.setImageResource(
            when {
                incognito && (focused || tab.isNtp) -> R.drawable.ic_incognito_small
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
        if (lastTabCount >= 0 && lastTabCount != tabs.size) {
            tabCount.animate().cancel()
            tabCount.scaleX = 1.35f
            tabCount.scaleY = 1.35f
            tabCount.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }
        lastTabCount = tabs.size
        setProgress(tab)
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
            snackbar.show("Вкладка открыта в фоне", "Перейти", onAction = { if (tab in tabs) selectTab(tab) })
        } else {
            selectTab(tab)
            pageView(tab)?.let { v ->
                v.alpha = 0f
                v.translationY = dp(48).toFloat()
                v.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
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
        pull.finish()
        showBars(animate = false)
        refreshToolbar()
    }

    /** Closes a tab; the WebView is kept until the "undo" snackbar expires. */
    internal fun closeTab(tab: Tab, undoable: Boolean = true) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        tabs.forEach { if (it.parent === tab) it.parent = tab.parent }
        val keep = undoable && !tab.isPopup
        if (keep) {
            tab.web?.apply { onPause(); visibility = View.GONE }
            val wasCurrent = current === tab
            snackbar.show(
                "Вкладка «${tab.displayTitle().take(40)}» закрыта", "Отменить",
                onAction = {
                    tabs.add(idx.coerceAtMost(tabs.size), tab)
                    if (wasCurrent || switcher.isShown) selectTab(tab) else refreshToolbar()
                    if (switcher.isShown) switcher.refresh()
                },
                onTimeout = { finalizeClose(tab) },
            )
        } else {
            finalizeClose(tab)
        }
        if (tabs.isEmpty()) {
            current = null
            if (incognito && !switcher.isShown) { snackbar.commit(); finish(); return }
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
        if (switcher.isShown) switcher.refresh()
    }

    private fun finalizeClose(tab: Tab) {
        if (tab in tabs) return
        if (!incognito && !tab.isNtp && !tab.isPopup) rememberClosed(tab)
        destroyWeb(tab)
    }

    private fun closeAllTabs() {
        snackbar.commit()
        val closed = tabs.toList()
        val cur = current
        closed.forEach { it.web?.apply { onPause(); visibility = View.GONE } }
        tabs.clear()
        current = null
        if (incognito) { closed.forEach { destroyWeb(it) }; finish(); return }
        switcher.hide()
        newTab(null, focus = false)
        snackbar.show(
            "Все вкладки закрыты", "Отменить",
            onAction = {
                val fresh = tabs.toList()
                tabs.clear()
                tabs.addAll(closed)
                fresh.forEach { destroyWeb(it) }
                selectTab(cur?.takeIf { it in tabs } ?: tabs.last())
            },
            onTimeout = { closed.forEach { finalizeClose(it) } },
            duration = 6000,
        )
    }

    private fun openSwitcher() {
        if (switcher.isShown) return
        current?.let { captureThumb(it) }
        urlBar.clearFocus()
        urlBar.hideKeyboard()
        switcher.show()
    }

    private fun pageView(tab: Tab?): View? = if (tab == null) null else if (tab.isNtp) ntp.view else tab.web

    /** Page area in root coordinates, used as the full-size end of the switcher zoom. */
    private fun pageRect(): Rect {
        val a = IntArray(2)
        val b = IntArray(2)
        webContainer.getLocationInWindow(a)
        root.getLocationInWindow(b)
        val l = a[0] - b[0]
        val t = a[1] - b[1]
        return Rect(l, t, l + webContainer.width, t + webContainer.height)
    }

    private fun captureThumb(tab: Tab) {
        val v: View = if (tab.isNtp) ntp.view else tab.web ?: return
        if (v.width == 0 || v.height == 0 || v.visibility != View.VISIBLE) return
        runCatching {
            val scale = 0.35f
            val h = v.height
            val bmp = Bitmap.createBitmap((v.width * scale).toInt(), (h * scale).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
            v.draw(c)
            tab.thumbnail = bmp
        }
    }

    private fun saveTabs() {
        if (incognito) return
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
        if (intent?.action == Incognito.ACTION_NEW_TAB) {
            if (current?.isNtp != true) newTab(null) else main.post { urlBar.showKeyboard() }
            return true
        }
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
            switcher.isShown -> switcher.close()
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
        if (!incognito) AdBlock.saveStats()
    }

    override fun onResume() {
        super.onResume()
        current?.web?.onResume()
        if (current?.isNtp == true) ntp.refresh()
        refreshToolbar()
    }

    override fun onDestroy() {
        snackbar.commit()
        if (incognito && isFinishing) tabs.firstOrNull { it.web != null }?.web?.apply { clearCache(true); clearFormData() }
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
        web.setOnScrollChangeListener { _, _, y, _, oldY -> onPageScroll(tab, y, oldY) }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        // Observes touches for pull-to-refresh only; the WebView still handles every event.
        web.setOnTouchListener { v, ev -> if (tab === current) pull.onTouch(v, ev); false }
        if (incognito) {
            @Suppress("DEPRECATION")
            web.settings.saveFormData = false
        }
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
            if (tab === current) showBars()
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
            if (!incognito) BrowserDb.addVisit(url, tab.title)
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
                setProgress(tab)
                if (newProgress == 100) pull.finish()
            }
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            tab.title = title
            if (!incognito) BrowserDb.updateTitle(tab.url, title)
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
            REQ_VOICE -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
                navigate(text)
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
            actions += "Открыть в фоновой вкладке" to { newTab(link, background = true, parent = tab); Unit }
            if (!incognito) actions += "Открыть в режиме инкогнито" to { openIncognito(link) }
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
    // ------------------------------------------------------------------ progress & bars

    private fun setProgress(tab: Tab) {
        val loading = tab.progress in 1..99 && !tab.isNtp
        if (loading) {
            progress.animate().cancel()
            progress.alpha = 1f
            progress.visibility = View.VISIBLE
            if (tab.progress < progress.progress) progress.progress = tab.progress
            else progress.setProgress(tab.progress, true)
        } else if (progress.visibility == View.VISIBLE && progress.alpha == 1f) {
            progress.setProgress(100, true)
            progress.animate().alpha(0f).setStartDelay(150).setDuration(200).withEndAction {
                progress.visibility = View.INVISIBLE
                progress.progress = 0
                progress.animate().startDelay = 0
            }.start()
        }
    }

    private fun onPageScroll(tab: Tab, y: Int, oldY: Int) {
        if (tab !== current || !Prefs.autoHideBar) return
        val dy = y - oldY
        scrollAccum = if (dy > 0 == scrollAccum > 0) scrollAccum + dy else dy
        when {
            y < dp(56) -> showBars()
            scrollAccum > dp(28) -> hideBars()
            scrollAccum < -dp(28) -> showBars()
        }
    }

    private fun canHideBars(): Boolean {
        val t = current ?: return false
        return !t.isNtp && t.progress >= 100 && !urlBar.hasFocus() && findBar.visibility != View.VISIBLE &&
            !switcher.isShown && fullscreenView == null
    }

    /** Slides the toolbar away on scroll, like Chrome's browser controls. */
    private fun hideBars() {
        if (barsHidden || !canHideBars()) return
        barsHidden = true
        val h = toolbar.height.toFloat()
        val target: View = if (Prefs.bottomBar) toolbar else content
        target.animate().cancel()
        target.animate().translationY(if (Prefs.bottomBar) h else -h).setDuration(200).withEndAction {
            if (barsHidden) {
                toolbar.visibility = View.GONE
                target.translationY = 0f
                snackbar.bottomOffset = 0
            }
        }.start()
    }

    private fun showBars(animate: Boolean = true) {
        snackbar.bottomOffset = if (Prefs.bottomBar) dp(56) else 0
        if (!barsHidden) return
        barsHidden = false
        scrollAccum = 0
        val target: View = if (Prefs.bottomBar) toolbar else content
        target.animate().cancel()
        val wasGone = toolbar.visibility == View.GONE
        if (findBar.visibility != View.VISIBLE) toolbar.visibility = View.VISIBLE
        if (!animate || !wasGone) { target.translationY = 0f; return }
        val h = dp(56).toFloat()
        target.translationY = if (Prefs.bottomBar) h else -h
        target.animate().translationY(0f).setDuration(200).start()
    }

    // ------------------------------------------------------------------ tab swipe on toolbar

    private fun neighbour(dx: Float): Tab? {
        val i = tabs.indexOf(current)
        return if (dx < 0) tabs.getOrNull(i + 1) else tabs.getOrNull(i - 1)
    }

    private fun tabSwipeMove(dx: Float) {
        val cur = current ?: return
        val page = pageView(cur) ?: return
        val next = neighbour(dx)
        val w = webContainer.width.toFloat()
        val eff = if (next == null) dx / 4f else dx
        if (next !== swipeTarget) {
            swipeTarget = next
            swipePeek.setImageBitmap(next?.thumbnail)
            swipePeek.visibility = if (next == null) View.GONE else View.VISIBLE
        }
        page.translationX = eff
        swipePeek.translationX = eff + if (dx < 0) w else -w
    }

    private fun tabSwipeEnd(dx: Float, vx: Float) {
        val cur = current ?: return
        val page = pageView(cur) ?: return
        val next = neighbour(dx).takeIf { it != null && it === swipeTarget }
        val w = webContainer.width.toFloat()
        val commit = next != null && (abs(dx) > w * 0.3f || abs(vx) > 1000 && vx * dx > 0)
        val dir = if (dx < 0) -1f else 1f
        val done = {
            page.translationX = 0f
            swipePeek.translationX = 0f
            swipePeek.visibility = View.GONE
            swipePeek.setImageDrawable(null)
            swipeTarget = null
        }
        if (commit && next != null) {
            page.animate().translationX(dir * w).setDuration(180).start()
            swipePeek.animate().translationX(0f).setDuration(180).withEndAction {
                done()
                selectTab(next)
            }.start()
        } else {
            page.animate().translationX(0f).setDuration(180).start()
            swipePeek.animate().translationX(if (dx < 0) w else -w).setDuration(180).withEndAction(done).start()
        }
    }

    // ------------------------------------------------------------------ extra actions

    private fun showTabsPopup(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Закрыть вкладку")
        pm.menu.add(0, 2, 1, getString(R.string.new_tab))
        pm.menu.add(0, 3, 2, getString(R.string.incognito_new))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> current?.let { t -> closeTab(t) }
                2 -> if (incognito) startActivity(Intent(this, MainActivity::class.java).setAction(Incognito.ACTION_NEW_TAB)) else newTab(null)
                3 -> openIncognito(null)
            }
            true
        }
        pm.show()
    }

    private fun toggleMode() {
        if (incognito) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } else {
            switcher.hide()
            openIncognito(null)
        }
    }

    internal fun openIncognito(url: String?) {
        if (incognito) { newTab(url); return }
        startActivity(Incognito.intent(this, url))
    }

    private fun rememberClosed(tab: Tab) {
        val arr = runCatching { JSONArray(Prefs.sp.getString("recent_closed", "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray().put(JSONObject().put("u", tab.url).put("t", tab.displayTitle()))
        for (i in 0 until minOf(arr.length(), 14)) {
            val o = arr.getJSONObject(i)
            if (o.optString("u") != tab.url) out.put(o)
        }
        Prefs.sp.edit().putString("recent_closed", out.toString()).apply()
    }

    internal fun showRecentTabs() {
        val arr = runCatching { JSONArray(Prefs.sp.getString("recent_closed", "[]")) }.getOrDefault(JSONArray())
        if (arr.length() == 0) { toast("Недавно закрытых вкладок нет"); return }
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
        AlertDialog.Builder(this).setTitle(getString(R.string.recent_tabs))
            .setItems(items.map { it.optString("t").ifBlank { it.optString("u") } }.toTypedArray()) { _, i ->
                newTab(items[i].optString("u"))
            }
            .setNeutralButton("Очистить") { _, _ -> Prefs.sp.edit().remove("recent_closed").apply() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    internal fun translatePage() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        navigate("https://translate.google.com/translate?sl=auto&tl=ru&u=" + Uri.encode(tab.url))
    }

    internal fun addToHomeScreen() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        if (Build.VERSION.SDK_INT < 26) return
        val sm = getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) { toast("Лаунчер не поддерживает ярлыки"); return }
        val size = dp(48)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val fav = tab.favicon
        if (fav != null && fav.width >= 32) {
            Canvas(bmp).drawBitmap(Bitmap.createScaledBitmap(fav, size, size, true), 0f, 0f, null)
        } else {
            LetterIcon(tab.url, tab.title).apply { setBounds(0, 0, size, size); draw(Canvas(bmp)) }
        }
        val info = ShortcutInfo.Builder(this, "site_" + tab.url.hashCode())
            .setShortLabel(tab.displayTitle().take(24))
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url), this, MainActivity::class.java))
            .build()
        sm.requestPinShortcut(info, null)
    }

    private fun startVoiceSearch() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (_: Exception) {
            toast("Голосовой поиск недоступен")
        }
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java)
        val desc = cm.primaryClipDescription ?: return null
        if (!desc.hasMimeType("text/*")) return null
        return cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 500 }
    }


    companion object {
        private const val REQ_STORAGE = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_FILE = 3
        private const val REQ_LIBRARY = 4
        private const val REQ_SETTINGS = 5
        private const val REQ_VOICE = 6
    }
}

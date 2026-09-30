package app.svetlo

import android.Manifest
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.ConnectivityManager
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Base64
import android.view.animation.DecelerateInterpolator
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
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
import app.svetlo.data.BrowserDb
import app.svetlo.data.Prefs
import app.svetlo.filter.AdBlock
import app.svetlo.ui.AdblockActivity
import app.svetlo.ui.GestureBar
import app.svetlo.ui.LetterIcon
import app.svetlo.ui.PullRefresh
import app.svetlo.ui.Snackbar
import app.svetlo.ui.TopCropImageView
import app.svetlo.ui.LibraryActivity
import app.svetlo.ui.MainMenu
import app.svetlo.ui.MediaSheet
import app.svetlo.ui.NewTabPage
import app.svetlo.ui.DownloadsActivity
import app.svetlo.ui.OnboardingActivity
import app.svetlo.ui.ReaderActivity
import app.svetlo.ui.SettingsActivity
import app.svetlo.ui.Suggestions
import app.svetlo.ui.TabSwitcher
import app.svetlo.ui.color
import app.svetlo.ui.dp
import app.svetlo.ui.motionDuration
import app.svetlo.ui.hideKeyboard
import app.svetlo.ui.showKeyboard
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var barsHidden = false
    private var scrollAccum = 0
    private var ignoreScrollUntil = 0L
    private var barShift = 0f
    private var barAnim: ValueAnimator? = null
    private var swipeTarget: Tab? = null
    private var lastTabCount = -1

    private val pendingPermissions = HashMap<Int, () -> Unit>()
    private var nextPermissionCode = 100
    private val sslAllowed = HashSet<String>()
    private val sslPending = HashMap<String, MutableList<SslErrorHandler>>()
    private val sitePermissions by lazy { SitePermissions(this, incognito, { it === current && it in tabs }, ::requestAppPermissions, ::toast) }
    private var sslDialog: AlertDialog? = null
    private class BlobJob(val owner: Tab, val target: OutputTarget, val name: String, val entry: String) {
        private var finished = false
        var bytes = 0L
            private set

        @Synchronized fun append(chunk: ByteArray) {
            check(!finished) { "download already finished" }
            target.stream.write(chunk)
            bytes += chunk.size
        }

        @Synchronized fun commit(): Boolean {
            if (finished) return false
            finished = true
            return runCatching { target.commit() }.fold(
                onSuccess = { true },
                onFailure = { runCatching { target.abort() }; false },
            )
        }

        @Synchronized fun abort() {
            if (finished) return
            finished = true
            target.abort()
        }
    }
    private val blobJobs = ConcurrentHashMap<String, BlobJob>()
    private val mse by lazy { MseRecorder(applicationContext) { main.post { refreshToolbar() } } }

    private lateinit var errorView: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var httpFallback: TextView

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
            listOf(mediaFab, findViewById<View>(R.id.tsNew)).forEach { it.animate().translationY(dy).setDuration(motionDuration(200)).start() }
        }
        swipePeek = TopCropImageView(this).apply {
            setBackgroundColor(color(R.color.c_bg))
            visibility = View.GONE
            elevation = dp(2).toFloat()
        }
        webContainer.addView(swipePeek, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pull = PullRefresh(this, webContainer) { current?.let { reloadTab(it) } }

        ntp = NewTabPage(this, findViewById(R.id.ntp), ::navigate, { urlBar.showKeyboard() }, incognito)
        buildErrorView()
        switcher = TabSwitcher(
            this, findViewById(R.id.tabSwitcher), zoomOverlay, tabs, incognito, { current }, ::pageRect,
            onSelect = { selectTab(it) },
            onClose = { closeTab(it) },
            onNew = { switcher.hide(); newTab(null) },
            onCloseAll = ::closeAllTabs,
            onToggleMode = ::toggleMode,
        )
        suggestions = Suggestions(this, findViewById<ListView>(R.id.suggestions), ::navigate, onFill = {
            urlBar.setText(it)
            urlBar.setSelection(urlBar.text.length)
        })
        setupToolbar()
        setupFindBar()
        applyToolbarPosition()

        if (!incognito) restoreTabs()
        if (!handleIntent(intent) && tabs.isEmpty()) newTab(null, focus = false)
        if (current == null) selectTab(tabs.last())
        if (!incognito && !Prefs.onboarded) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, OnboardingActivity::class.java), REQ_ONBOARDING)
        }
    }

    // ------------------------------------------------------------------ toolbar

    private fun setupToolbar() {
        // Drawn above the page while it slides over it; no outline so it casts no shadow.
        toolbar.outlineProvider = null
        toolbar.elevation = dp(1).toFloat()
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
        findViewById<View>(R.id.btnSearchBack).setOnClickListener { urlBar.clearFocus(); urlBar.hideKeyboard() }
        shieldChip.setOnClickListener { showSiteInfo() }
        siteIcon.setOnClickListener { if (!urlBar.hasFocus()) showSiteInfo() }
        mediaFab.setOnClickListener { if (mse.recordingTab != null) stopMseRecording() else showVideos() }
        urlBar.setOnFocusChangeListener { _, focused ->
            omnibox.setBackgroundResource(if (focused) R.drawable.bg_omnibox_focused else R.drawable.bg_omnibox)
            listOf(R.id.btnHome, R.id.btnTabs, R.id.btnMenu).forEach {
                findViewById<View>(it).visibility = if (focused || it == R.id.btnHome && resources.configuration.screenWidthDp < 360) View.GONE else View.VISIBLE
            }
            findViewById<View>(R.id.btnSearchBack).visibility = if (focused) View.VISIBLE else View.GONE
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
                suggestions.query(s.toString(), clipboardText(), !incognito, allowRemote = !incognito)
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
        val showShield = !focused && !tab.isNtp && Prefs.adblock && resources.configuration.screenWidthDp >= 400 && resources.configuration.fontScale < 1.3f
        shieldChip.visibility = if (showShield) View.VISIBLE else View.GONE
        if (showShield) {
            val off = AdBlock.isWhitelisted(tab.host)
            shieldChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (off) R.drawable.ic_shield_off else R.drawable.ic_shield_accent, 0, 0, 0,
            )
            shieldChip.text = if (off) "" else tab.blocked.get().toString()
        }
        tabCount.text = if (tabs.size > 99) ":)" else tabs.size.toString()
        findViewById<View>(R.id.btnTabs).contentDescription = getString(R.string.tab_counter_description, tabs.size)
        findViewById<View>(R.id.btnHome).visibility = if (focused || resources.configuration.screenWidthDp < 360) View.GONE else View.VISIBLE
        if (lastTabCount >= 0 && lastTabCount != tabs.size) {
            tabCount.animate().cancel()
            if (motionDuration(220) == 0L) {
                tabCount.scaleX = 1f
                tabCount.scaleY = 1f
            } else {
                tabCount.scaleX = 1.35f
                tabCount.scaleY = 1.35f
                tabCount.animate().scaleX(1f).scaleY(1f).setDuration(motionDuration(220)).start()
            }
        }
        lastTabCount = tabs.size
        setProgress(tab)
        val videos = tab.media.count
        val recording = mse.recordingTab
        val streams = videos > 0 || tab.mseTypes.isNotEmpty()
        mediaFab.visibility = if ((recording != null || streams && !tab.isNtp) && !focused) View.VISIBLE else View.GONE
        mediaFab.text = when {
            recording != null -> "● Запись · ${formatMb(mse.bytes)} · Стоп"
            videos <= 1 -> getString(app.svetlo.R.string.label_8e7b9894c7)
            else -> "Видео · $videos"
        }
        updateErrorView()
    }

    private fun formatMb(bytes: Long) = String.format(Locale("ru"), getString(app.svetlo.R.string.label_93eb0c0182), bytes / 1048576.0)

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
        tab.desktop = SiteSettings.desktop(tab.url)
        tab.parent = parent
        tab.lastUsed = android.os.SystemClock.elapsedRealtime()
        val idx = if (parent != null) (tabs.indexOf(parent) + 1).coerceIn(0, tabs.size) else tabs.size
        tabs.add(idx, tab)
        if (background) {
            ensureWeb(tab).apply { loadUrl(tab.url); onPause() }
            tab.loaded = true
            refreshToolbar()
            snackbar.show(getString(app.svetlo.R.string.label_830504a381), getString(app.svetlo.R.string.label_48db038c20), onAction = { if (tab in tabs) selectTab(tab) })
        } else {
            selectTab(tab)
            pageView(tab)?.let { v ->
                if (motionDuration(220) == 0L) {
                    v.alpha = 1f
                    v.translationY = 0f
                } else {
                    v.alpha = 0f
                    v.translationY = dp(48).toFloat()
                    v.animate().alpha(1f).translationY(0f).setDuration(motionDuration(220)).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
            }
            if (focus && !switcher.isShown) main.post { urlBar.showKeyboard() }
        }
        evictInactiveTabs()
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
            if (!tab.loaded) {
                val state = tab.savedState
                tab.savedState = null
                val restored = state != null && runCatching { web.restoreState(state) }.getOrNull() != null
                if (!restored) { tab.nativeOffset = tab.sessionIndex; web.loadUrl(tab.url) }
                tab.loaded = true
            }
            web.visibility = View.VISIBLE
            web.onResume()
            ntp.view.visibility = View.GONE
        }
        if (findBar.visibility == View.VISIBLE) closeFind()
        urlBar.clearFocus()
        pull.finish()
        showBars(animate = false)
        refreshToolbar()
        tab.lastUsed = android.os.SystemClock.elapsedRealtime()
        evictInactiveTabs()
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
            getString(app.svetlo.R.string.label_26cbd6084c), getString(app.svetlo.R.string.label_555ad1c0c3),
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
        if (!incognito) TabSessions.save(this, TabSessions.snapshot(tabs, current))
    }

    private fun restoreTabs() {
        val session = TabSessions.read(this)
        val arr = session?.optJSONArray("tabs") ?: runCatching { JSONArray(Prefs.sp.getString("tabs", "[]")) }.getOrNull() ?: return
        val curIdx = session?.optInt("current", arr.length() - 1) ?: Prefs.sp.getInt("tab_cur", arr.length() - 1)
        var selected: Tab? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("u")
            if (url.isEmpty() || url == "about:blank") continue
            val tab = Tab(url, o.optString("t"))
            tab.desktop = o.optBoolean("desktop")
            val history = o.optJSONArray("history")
            tab.sessionHistory = (0 until (history?.length() ?: 0)).mapNotNull { n -> history?.optString(n)?.takeIf { Origin.of(it) != null } }.toMutableList()
            tab.sessionIndex = o.optInt("index").coerceIn(0, (tab.sessionHistory.size - 1).coerceAtLeast(0))
            tabs += tab
            if (i == curIdx) selected = tab
        }
        (selected ?: tabs.lastOrNull())?.let { selectTab(it) }
        // One-way migration: old binary Parcel files are deliberately not deserialized.
        File(noBackupFilesDir, "tabs.state").delete()
    }

    internal fun canGoForward(tab: Tab) = tab.web?.canGoForward() == true || tab.sessionIndex < tab.sessionHistory.lastIndex
    internal fun goForward(tab: Tab) {
        if (tab.web?.canGoForward() == true) tab.web?.goForward()
        else if (tab.sessionIndex < tab.sessionHistory.lastIndex) restoreHistoryAt(tab, tab.sessionIndex + 1)
    }
    private fun restoreHistoryAt(tab: Tab, index: Int) {
        val url = tab.sessionHistory.getOrNull(index) ?: return
        destroyWeb(tab)
        tab.savedState = null
        tab.sessionIndex = index
        tab.url = url
        selectTab(tab)
    }

    private fun evictInactiveTabs(keep: Int = 4) {
        val candidates = tabs.filter { it !== current && it.web != null && it !== mse.recordingTab }.sortedBy { it.lastUsed }
        candidates.dropLast(keep.coerceAtLeast(0)).forEach { t ->
            val w = t.web ?: return@forEach
            TabSessions.capture(t)
            t.savedState = Bundle().takeIf { w.saveState(it) != null }
            destroyWeb(t)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) evictInactiveTabs(0)
    }

    // ------------------------------------------------------------------ navigation

    internal fun navigate(input: String) {
        if (input.isBlank()) return
        val url = Prefs.toUrl(input)
        val tab = current ?: newTab(null)
        if (tab.isNtp) tab.cameFromNtp = true
        tab.url = url
        tab.title = ""
        tab.sessionHistory.clear()
        tab.sessionIndex = 0
        tab.nativeOffset = 0
        tab.desktop = SiteSettings.desktop(url)
        val web = ensureWeb(tab)
        applySettings(web, tab)
        web.loadUrl(url)
        tab.loaded = true
        urlBar.clearFocus()
        urlBar.hideKeyboard()
        switcher.hide()
        selectTab(tab)
        web.requestFocus()
        evictInactiveTabs()
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
        if (intent?.action == "app.svetlo.RETRY_DOWNLOAD") {
            val entry = intent.getStringExtra("download_id")?.let(DownloadRegistry::get) ?: return false
            if (Origin.of(entry.source) == null) return false
            if (entry.id.startsWith("svc-")) {
                val kind = if (entry.source.substringBefore('?').endsWith(".mpd", true)) MediaKind.DASH else MediaKind.HLS
                startMediaDownload(current ?: newTab(null, focus = false), MediaItem(entry.source, kind), null)
            } else downloadDirect(entry.source, entry.name, current)
            return true
        }
        if (intent?.action == Incognito.ACTION_NEW_TAB) {
            if (current?.isNtp != true) newTab(null) else main.post { urlBar.showKeyboard() }
            return true
        }
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return false
        if (Origin.of(data) == null) return false
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
            !tab.isNtp && tab.sessionIndex > 0 -> restoreHistoryAt(tab, tab.sessionIndex - 1)
            !tab.isNtp && tab.cameFromNtp -> goHome()
            tab.parent != null && tab.parent in tabs -> closeTab(tab)
            else -> moveTaskToBack(true)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ntp.updateLayout()
        switcher.refresh()
        refreshToolbar()
    }

    override fun onPause() {
        super.onPause()
        // In picture-in-picture the activity is paused but the video must keep playing.
        if (!isInPip()) current?.web?.onPause()
        saveTabs()
        if (!incognito) AdBlock.saveStats()
    }

    override fun onStop() {
        super.onStop()
        current?.web?.onPause()
    }

    private fun isInPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun canPip() = Build.VERSION.SDK_INT >= 26 && Prefs.pictureInPicture && fullscreenView != null &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @SuppressLint("NewApi")
    private fun pipParams(): android.app.PictureInPictureParams {
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(canPip()).setSeamlessResizeEnabled(true)
        return b.build()
    }

    /** Chrome-like: leaving the app while a video is fullscreen continues it in a floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && canPip()) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onResume() {
        super.onResume()
        current?.web?.onResume()
        if (current?.isNtp == true) ntp.refresh()
        refreshToolbar()
    }

    override fun onDestroy() {
        sitePermissions.dismiss()
        sslDialog?.dismiss()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        snackbar.commit()
        if (::suggestions.isInitialized) suggestions.close()
        if (incognito && isFinishing) ArticleStore.folder(this).deleteRecursively()
        if (incognito && isFinishing) tabs.firstOrNull { it.web != null }?.web?.apply { clearCache(true); clearFormData() }
        tabs.forEach { destroyWeb(it) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ WebView

    private fun ensureWeb(tab: Tab): WebView = tab.web ?: createWeb(tab)

    private fun destroyWeb(tab: Tab) {
        cancelBlobJobs(tab)
        if (mse.recordingTab === tab) stopMseRecording()
        tab.web?.let {
            webContainer.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        tab.web = null
        tab.loaded = false
        tab.progress = 100
        tab.error = null
        tab.media.clear()
        tab.mseTypes.clear()
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
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        applySettings(web, tab)
        web.webViewClient = TabClient(tab)
        web.webChromeClient = TabChrome(tab)
        web.addJavascriptInterface(BlobBridge(), BLOB_BRIDGE)
        web.addJavascriptInterface(mse.Bridge(tab), MseRecorder.BRIDGE)
        web.setDownloadListener { url, _, disposition, mime, _ ->
            val name = fileNameFor(url, disposition, mime)
            AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_59f91618a9)).setMessage(name)
                .setPositiveButton(getString(app.svetlo.R.string.label_fe8f79f29d)) { _, _ -> startDownload(url, name, mime, tab) }
                .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
            // blob: data is read from the page itself, so its tab must stay alive.
            if (tab.web?.canGoBack() != true && tab.isPopup && !url.startsWith("blob:")) closeTab(tab)
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
        s.javaScriptEnabled = SiteSettings.javascript(tab.url, Prefs.javascript)
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
        if (!incognito) SiteSettings.setDesktop(tab.url, tab.desktop)
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
                if (tab.error != null) main.post { tab.error = null; if (tab === current) updateErrorView() }
                if (tab.isPopup && !tab.popupChecked) {
                    tab.popupChecked = true
                    if (AdBlock.blocksNavigation(url, tab.openerHost)) {
                        AdBlock.totalBlocked.incrementAndGet()
                        main.post { closeTab(tab); toast(getString(app.svetlo.R.string.label_dce3ab6dbb)) }
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
                    toast(getString(app.svetlo.R.string.label_4c35c9697b))
                    return true
                }
                if (req.isForMainFrame) {
                    tab.desktop = SiteSettings.desktop(uri.toString())
                    view.settings.javaScriptEnabled = SiteSettings.javascript(uri.toString(), Prefs.javascript)
                    view.settings.userAgentString = if (tab.desktop) desktopUa(WebSettings.getDefaultUserAgent(this@MainActivity)) else null
                }
                return false
            }
            if (!req.isForMainFrame || uri.scheme in setOf("file", "content", "javascript")) return true
            openExternal(view, uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.generation++
            if (tab === current) sitePermissions.dismiss()
            cancelBlobJobs(tab)
            injectedFor = null
            tab.mseTypes.clear()
            sslAllowed.clear()
            sslDialog?.dismiss()
            view.evaluateJavascript(MseRecorder.hook(tab.mseArm), null)
            // Scriptlets must run before page scripts to be effective; pageScript repeats them if this was too early.
            AdBlock.earlyScript(Uri.parse(url).host?.lowercase())?.let { view.evaluateJavascript(it, null) }
            if (tab.error?.url != url) tab.error = null
            if (tab === current) showBars()
            tab.url = url
            tab.pageHost = Uri.parse(url).host?.lowercase()
            tab.favicon = favicon
            if (tab === current) refreshToolbar()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            // onPageStarted may still run in the previous document; repeat once the new one is live.
            view.evaluateJavascript(MseRecorder.hook(tab.mseArm), null)
            tab.mseArm = false
            inject(view, url)
        }

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
            TabSessions.capture(tab)
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

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            val code = err.errorCode
            if (code == ERROR_UNSUPPORTED_SCHEME || code == ERROR_UNKNOWN) return
            val url = req.url.toString()
            val host = req.url.host?.removePrefix("www.") ?: url
            val (title, text) = when {
                !isOnline() -> getString(app.svetlo.R.string.label_5a73991ea8) to "Проверьте Wi‑Fi или мобильный интернет и попробуйте снова."
                code == ERROR_HOST_LOOKUP -> getString(app.svetlo.R.string.label_a771295a6d) to "Не удалось найти адрес $host. Проверьте, нет ли в нём опечатки."
                code == ERROR_CONNECT || code == ERROR_TIMEOUT -> getString(app.svetlo.R.string.label_829e9b0e87) to "$host слишком долго не отвечает или отклонил подключение."
                code == ERROR_FAILED_SSL_HANDSHAKE -> getString(app.svetlo.R.string.label_b1ffffa8ce) to "$host использует неподдерживаемый протокол или неверный сертификат."
                code == ERROR_REDIRECT_LOOP -> getString(app.svetlo.R.string.label_8ef7a4752d) to "Попробуйте удалить cookie для $host."
                else -> getString(app.svetlo.R.string.label_b2717fdac6) to "$host: ${err.description}"
            }
            tab.error = PageError(url, title, text)
            if (tab === current) updateErrorView()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = Uri.parse(error.url).host?.lowercase()
            when {
                host == null -> handler.cancel()
                sslKey(error) in sslAllowed -> handler.proceed()
                // Only the page's own host gets a prompt; broken third-party resources are dropped.
                tab !== current || host != tab.pageHost -> handler.cancel()
                sslPending.containsKey(sslKey(error)) -> sslPending.getValue(sslKey(error)) += handler
                else -> askSslProceed(host, error, handler)
            }
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String?) {
            askHttpAuth(host, realm, handler)
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
            tab.media.pageTitle = title
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
            updatePipParams()
        }

        override fun onHideCustomView() = exitFullscreen()

        override fun onPermissionRequest(request: PermissionRequest) = sitePermissions.onSitePermission(tab, request)

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            sitePermissions.cancel(request)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            sitePermissions.onGeolocationPrompt(tab, origin, callback)

        override fun onGeolocationPermissionsHidePrompt() {
            sitePermissions.dismiss()
        }

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
        updatePipParams()
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
            if (fallback != null && Origin.of(fallback) != null) view.loadUrl(fallback) else toast(getString(app.svetlo.R.string.label_77f0674297))
        }
    }

    // ------------------------------------------------------------------ page actions

    internal fun reloadOrStop() {
        val tab = current ?: return
        val web = tab.web ?: return
        if (tab.progress < 100) web.stopLoading() else reloadTab(tab)
    }

    private fun reloadTab(tab: Tab) {
        val web = tab.web ?: return
        val failed = tab.error?.url
        if (failed != null) web.loadUrl(failed) else web.reload()
    }

    // ------------------------------------------------------------------ error page

    private fun buildErrorView() {
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(color(R.color.c_bg))
            setPadding(dp(32), dp(72), dp(32), dp(32))
            visibility = View.GONE
            isClickable = true
        }
        errorView.addView(ImageView(this).apply { setImageResource(R.drawable.ic_globe); alpha = 0.7f; scaleX = 2f; scaleY = 2f },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(28) })
        errorTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(color(R.color.c_text))
            gravity = Gravity.CENTER
        }
        errorText = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.c_text2))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(28))
        }
        errorView.addView(errorTitle)
        errorView.addView(errorText)
        errorView.addView(TextView(this).apply {
            text = getString(app.svetlo.R.string.label_9e506acb19)
            textSize = 15f
            setTextColor(color(R.color.c_on_accent))
            setBackgroundResource(R.drawable.bg_fab)
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            setOnClickListener { current?.let { reloadTab(it) } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        httpFallback = TextView(this).apply {
            text = getString(app.svetlo.R.string.label_b5a0f1f8cb)
            textSize = 14f
            setTextColor(color(R.color.c_text2))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                val url = current?.error?.url?.takeIf { it.startsWith("https://") } ?: return@setOnClickListener
                AlertDialog.Builder(this@MainActivity).setTitle(getString(app.svetlo.R.string.label_4e00dd028a))
                    .setMessage(getString(app.svetlo.R.string.label_c6351aea1f))
                    .setPositiveButton(getString(app.svetlo.R.string.label_f2cea57d22)) { _, _ -> navigate("http://" + url.removePrefix("https://")) }
                    .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
            }
        }
        errorView.addView(httpFallback)
        // Above the WebViews (added at index 0) but below the NTP, suggestions and overlays.
        webContainer.addView(errorView, webContainer.indexOfChild(ntp.view),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun updateErrorView() {
        val err = current?.takeIf { !it.isNtp }?.error
        if (err == null) { errorView.visibility = View.GONE; return }
        httpFallback.visibility = if (err.url.startsWith("https://")) View.VISIBLE else View.GONE
        errorTitle.text = err.title
        errorText.text = err.text
        errorView.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching { cm.activeNetwork != null }.getOrDefault(true)
    }

    // ------------------------------------------------------------------ site prompts

    private fun sslKey(error: SslError): String {
        val cert = android.net.http.SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate") ?: byteArrayOf()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        return "${Origin.of(error.url)}|${error.primaryError}|$digest"
    }

    private fun askSslProceed(host: String, error: SslError, handler: SslErrorHandler) {
        val handlers = mutableListOf(handler)
        val key = sslKey(error)
        sslPending[key] = handlers
        val reason = when (error.primaryError) {
            SslError.SSL_EXPIRED -> getString(app.svetlo.R.string.label_8c001d4a6d)
            SslError.SSL_NOTYETVALID -> getString(app.svetlo.R.string.label_d53210234e)
            SslError.SSL_IDMISMATCH -> getString(app.svetlo.R.string.label_e12dfc388b)
            SslError.SSL_UNTRUSTED -> getString(app.svetlo.R.string.label_aa466ccd0c)
            SslError.SSL_DATE_INVALID -> getString(app.svetlo.R.string.label_7c8b616af1)
            else -> getString(app.svetlo.R.string.label_26f5195e4d)
        }
        var proceed = false
        sslDialog = AlertDialog.Builder(this)
            .setTitle(getString(app.svetlo.R.string.label_4a25e8a75e))
            .setMessage("$reason\n\nЗлоумышленники могут пытаться похитить ваши данные с сайта $host (например, пароли или номера карт).")
            .setPositiveButton(getString(app.svetlo.R.string.label_f6dab074d7), null)
            .setNegativeButton(getString(app.svetlo.R.string.label_7c9d733795)) { _, _ -> proceed = true }
            .setOnDismissListener {
                sslPending.remove(key)
                sslDialog = null
                if (proceed) { sslAllowed += sslKey(error); handlers.forEach { it.proceed() } } else handlers.forEach { it.cancel() }
            }
            .show()
    }

    private fun askHttpAuth(host: String, realm: String?, handler: HttpAuthHandler) {
        val user = EditText(this).apply { hint = getString(app.svetlo.R.string.label_a79f8a521d); setSingleLine() }
        val pass = EditText(this).apply {
            hint = getString(app.svetlo.R.string.label_14f7c63cc1)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(user)
            addView(pass)
        }
        var done = false
        AlertDialog.Builder(this)
            .setTitle("Вход на $host")
            .setMessage(realm?.takeIf { it.isNotBlank() }?.let { "Сайт требует авторизацию: «$it»" } ?: getString(app.svetlo.R.string.label_d119d9fb73))
            .setView(box)
            .setPositiveButton(getString(app.svetlo.R.string.label_939e95a11d)) { _, _ -> done = true; handler.proceed(user.text.toString(), pass.text.toString()) }
            .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null)
            .setOnDismissListener { if (!done) handler.cancel() }
            .show()
        user.showKeyboard()
    }

    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestAppPermissions(perms: List<String>, then: () -> Unit) {
        val missing = perms.filterNot(::hasPermission)
        if (missing.isEmpty()) { then(); return }
        val code = nextPermissionCode++
        pendingPermissions[code] = then
        requestPermissions(missing.toTypedArray(), code)
    }

    internal fun toggleBookmark() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        if (BrowserDb.isBookmarked(tab.url)) {
            BrowserDb.removeBookmark(tab.url); toast(getString(app.svetlo.R.string.label_c70571f4f1))
        } else {
            BrowserDb.addBookmark(tab.url, tab.displayTitle()); toast(getString(app.svetlo.R.string.label_22168e158e))
        }
    }

    internal fun sharePage() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), null))
    }

    internal fun openReader() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        val web = tab.web ?: return
        web.evaluateJavascript(Reader.EXTRACT_JS) { result ->
            val json = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            val article = Reader.parse(tab.url, json)
            if (article == null) { toast(getString(app.svetlo.R.string.label_93567d69a9)); return@evaluateJavascript }
            val articleFile = runCatching { ArticleStore.write(this, article) }.getOrElse { toast("Не удалось открыть статью: ${it.message}"); return@evaluateJavascript }
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, if (incognito && Incognito.isolated) app.svetlo.ui.IncognitoReaderActivity::class.java else ReaderActivity::class.java).putExtra("article_file", articleFile), REQ_READER)
        }
    }

    internal fun printPage() {
        val tab = current?.takeIf { !it.isNtp } ?: return
        val web = tab.web ?: return
        val name = tab.displayTitle().replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        runCatching {
            getSystemService(android.print.PrintManager::class.java)
                .print(name, web.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
        }.onFailure { toast(getString(app.svetlo.R.string.label_55193bbdea)) }
    }

    internal fun openLibrary(bookmarks: Boolean) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_BOOKMARKS, bookmarks), REQ_LIBRARY)
    }

    internal fun openSettings() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, if (incognito && Incognito.isolated) app.svetlo.ui.IncognitoSettingsActivity::class.java else SettingsActivity::class.java), REQ_SETTINGS)
    }

    internal fun openAdblockSettings() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, if (incognito && Incognito.isolated) app.svetlo.ui.IncognitoAdblockActivity::class.java else AdblockActivity::class.java), REQ_SETTINGS)
    }

    internal fun openDownloads() {
        startActivity(Intent(this, DownloadsActivity::class.java))
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
            REQ_READER -> {
                val url = data?.getStringExtra(ReaderActivity.EXTRA_URL) ?: return
                navigate(url)
            }
            REQ_SETTINGS, REQ_ONBOARDING -> {
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
        val secure = tab.url.startsWith("https://") && sslAllowed.none { it.startsWith("${Origin.of(tab.url)}|") }
        v.findViewById<TextView>(R.id.siteSecurity).apply {
            text = if (secure) getString(app.svetlo.R.string.label_bf321a8152) else getString(app.svetlo.R.string.label_e25e7b065c)
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
        v.findViewById<View>(R.id.siteSettings).setOnClickListener { dialog.dismiss(); showSiteSettings() }
        dialog.show()
    }

    private fun showSiteSettings() {
        val tab = current ?: return
        val url = tab.url
        val origin = Origin.of(url) ?: return
        val items = arrayOf("JavaScript: ${if (SiteSettings.javascript(url, Prefs.javascript)) getString(app.svetlo.R.string.label_bbbf38509d) else getString(app.svetlo.R.string.label_0b48cc8756)}",
            "Версия для ПК: ${if (tab.desktop) getString(app.svetlo.R.string.label_b9147aa121) else getString(app.svetlo.R.string.label_3bafb21566)}", "Сбросить разрешения и настройки сайта", "Удалить локальное хранилище сайта", "Настройки блокировки", "Камера", "Микрофон", "Местоположение")
        AlertDialog.Builder(this).setTitle(origin).setItems(items) { _, i ->
            when (i) {
                0 -> { SiteSettings.setJavascript(url, !SiteSettings.javascript(url, Prefs.javascript)); tab.web?.let { applySettings(it, tab); it.reload() } }
                1 -> toggleDesktop()
                2 -> { SiteSettings.reset(url); sitePermissions.reset(origin); sslAllowed.clear(); toast(getString(app.svetlo.R.string.label_f4e996a084)) }
                3 -> AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_100807b171)).setMessage(getString(app.svetlo.R.string.label_4bc8a2d58d))
                    .setPositiveButton(getString(app.svetlo.R.string.label_86ea33aef5)) { _, _ ->
                        android.webkit.WebStorage.getInstance().deleteOrigin(Uri.parse(url).let { "${it.scheme}://${it.authority}" })
                        val cookies = CookieManager.getInstance().getCookie(url).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotBlank() }
                        cookies.forEach { CookieManager.getInstance().setCookie(url, "$it=; Max-Age=0; Path=/") }
                        CookieManager.getInstance().flush()
                        tab.web?.reload()
                    }.setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
                4 -> openAdblockSettings()
                5, 6, 7 -> {
                    val key = when (i) { 5 -> "media:true:false"; 6 -> "media:false:true"; else -> "geo" }
                    AlertDialog.Builder(this).setTitle(items[i]).setItems(arrayOf(getString(app.svetlo.R.string.label_dcf0bfb35e), getString(app.svetlo.R.string.label_616bb19d6c), getString(app.svetlo.R.string.label_21aba91387))) { _, choice ->
                        sitePermissions.setDecision(origin, key, if (choice == 0) null else choice == 1)
                    }.show()
                }
            }
        }.show()
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
        showBars(animate = false)
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
        tab.media.pageTitle = tab.title.takeIf { it.isNotBlank() }
        scanMedia(tab) {
            val items = tab.media.list()
            val canRecord = tab.mseTypes.isNotEmpty()
            MediaSheet(
                activity = this,
                items = items,
                canRecord = canRecord,
                onPlay = { item -> openMediaInPlayer(item) },
                onDownload = { item ->
                    if (item.kind == MediaKind.DIRECT) startMediaDownload(tab, item, null)
                    else chooseStreamQuality(tab, item)
                },
                onRecord = { confirmMseRecording(tab) },
            ).show()
        }
    }

    /** Passes a detected stream to Android's media-player resolver, as in ILYRO. */
    private fun openMediaInPlayer(item: MediaItem) {
        val uri = Uri.parse(item.url)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.externalMimeType)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(app.svetlo.R.string.label_56a3ac2897))
        } catch (_: SecurityException) {
            toast(getString(app.svetlo.R.string.label_a54167e3d2))
        }
    }

    private fun confirmMseRecording(tab: Tab) {
        AlertDialog.Builder(this)
            .setTitle(getString(app.svetlo.R.string.label_b9c2dcd395))
            .setMessage(
                getString(app.svetlo.R.string.label_a5fdb66fdb) +
                    "• Выберите нужное качество и досмотрите видео до конца (можно ускорить воспроизведение).\n" +
                    "• Перемотка вперёд пропускает часть видео.\n" +
                    "• Видео и звук обычно сохраняются двумя файлами.\n\n" +
                    getString(app.svetlo.R.string.label_d9233d66bf),
            )
            .setPositiveButton(getString(app.svetlo.R.string.label_14eaa871df)) { _, _ -> startMseRecording(tab) }
            .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null)
            .show()
    }

    private fun startMseRecording(tab: Tab) = withStoragePermission {
        val web = tab.web ?: return@withStoragePermission
        mse.start(tab, tab.title.takeIf { it.isNotBlank() })
        tab.mseArm = true
        web.reload()
        refreshToolbar()
    }

    private fun stopMseRecording() {
        val tab = mse.recordingTab
        tab?.web?.evaluateJavascript("window.__svMseRec&&window.__svMseRec(false)", null)
        tab?.mseArm = false
        val files = mse.stop()
        toast(if (files.isEmpty()) getString(app.svetlo.R.string.label_593d4f4892) else getString(app.svetlo.R.string.label_35ea9a2aa0) + files.joinToString(", "))
        refreshToolbar()
    }

    /** Loads the stream's quality list off the main thread, then lets the user pick one. */
    private fun chooseStreamQuality(tab: Tab, item: MediaItem) {
        val headers = requestHeaders(item.url, tab)
        toast(getString(app.svetlo.R.string.label_026843c815))
        Thread {
            val result = runCatching { StreamDownloader.variants(item.url, headers, item.kind) }
            val variants = result.getOrDefault(emptyList())
            main.post {
                if (isFinishing || isDestroyed) return@post
                // DRM and live streams can't be saved; say so instead of starting a job that fails.
                result.exceptionOrNull()?.let { e ->
                    toast(HlsDownloadService.describe(e)); return@post
                }
                if (variants.size <= 1) { startMediaDownload(tab, item, variants.firstOrNull()); return@post }
                val auto = StreamDownloader.pickDefault(variants)
                val labels = listOf(getString(app.svetlo.R.string.label_5695330ffb) + (auto?.let { " (${it.label})" } ?: "")) + variants.map { it.label }
                AlertDialog.Builder(this)
                    .setTitle(getString(app.svetlo.R.string.label_a41671308a))
                    .setItems(labels.toTypedArray()) { _, i -> startMediaDownload(tab, item, if (i == 0) auto else variants[i - 1]) }
                    .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null)
                    .show()
            }
        }.start()
    }

    private fun startMediaDownload(tab: Tab, item: MediaItem, variant: HlsDownloader.Variant?) {
        if (item.kind != MediaKind.DIRECT) {
            withStoragePermission {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                }
                HlsDownloadService.start(
                    this, item.url, requestHeaders(item.url, tab),
                    title = item.title ?: tab.title.takeIf { it.isNotBlank() },
                    variantUrl = variant?.url, audioUrl = variant?.audioUrl, kind = item.kind,
                )
                toast(if (variant?.audioUrl != null) getString(app.svetlo.R.string.label_705ba22f50) else getString(app.svetlo.R.string.label_755f98538b))
            }
        } else {
            val guessed = URLUtil.guessFileName(item.url, null, null)
            val title = item.title ?: tab.title.takeIf { it.isNotBlank() }
            val name = if (title != null) HlsDownloadService.fileBase(title) + "." + guessed.substringAfterLast('.', "mp4") else guessed
            downloadDirect(item.url, name, tab)
        }
    }

    private fun requestHeaders(url: String, tab: Tab?): HashMap<String, String> {
        val h = hashMapOf("User-Agent" to (tab?.web?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(this)))
        tab?.url?.takeIf { it.startsWith("http") }?.let { h["Referer"] = it }
        CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
        h["X-Svetlo-Origin"] = url
        return h
    }

    private fun fileNameFor(url: String, disposition: String?, mime: String?): String {
        if (url.startsWith("http")) return URLUtil.guessFileName(url, disposition, mime)
        val fromHeader = disposition?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)?.let { Uri.decode(it).substringAfterLast('/').trim() }
        if (!fromHeader.isNullOrBlank()) return fromHeader
        val type = mime?.takeIf { it.isNotBlank() } ?: url.takeIf { it.startsWith("data:") }?.substringAfter(':')?.substringBefore(';')?.substringBefore(',')
        val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
        return "download_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + ext
    }

    private fun startDownload(url: String, name: String, mime: String?, tab: Tab?) = when {
        url.startsWith("blob:") -> downloadBlob(tab, url, name, mime)
        url.startsWith("data:") -> downloadData(url, name)
        else -> downloadDirect(url, name, tab)
    }

    private fun mimeFor(name: String, mime: String?) = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

    private fun downloadData(url: String, name: String) = withStoragePermission {
        val mime = mimeFor(name, url.substringAfter(':').substringBefore(',').substringBefore(';'))
        Thread {
            val result = runCatching {
                val meta = url.substringBefore(',')
                val payload = url.substringAfter(',')
                val bytes = if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
                val target = OutputTarget(this, name, mime)
                val entry = Downloads.recordLocal(name, url.take(200), mime)
                try {
                    target.stream.write(bytes); target.commit()
                    Downloads.finishLocal(entry, target.contentUri, true, null, bytes.size.toLong())
                } catch (e: Exception) {
                    target.abort(); Downloads.finishLocal(entry, null, false, e.message); throw e
                }
            }
            main.post { toast(if (result.isSuccess) "Скачано: $name" else "Не удалось скачать: ${result.exceptionOrNull()?.message}") }
        }.start()
    }

    /** blob: URLs only exist inside the page, so the page reads the blob and streams it back in chunks. */
    private fun downloadBlob(tab: Tab?, url: String, name: String, mime: String?) = withStoragePermission {
        val web = tab?.web ?: return@withStoragePermission
        val target = runCatching { OutputTarget(this, name, mimeFor(name, mime)) }.getOrElse {
            toast("Не удалось создать файл: ${it.message}"); return@withStoragePermission
        }
        val token = UUID.randomUUID().toString()
        blobJobs[token] = BlobJob(tab, target, name, Downloads.recordLocal(name, tab.url, target.mime))
        toast("Скачивание: $name")
        val js = """(function(u,t){var B=window.$BLOB_BRIDGE;fetch(u).then(function(r){return r.blob()}).then(function(b){
            var o=0,S=393216;function next(){if(o>=b.size){B.done(t);return}var f=new FileReader();
            f.onload=function(){var s=f.result;B.chunk(t,s.substring(s.indexOf(',')+1));o+=S;next()};
            f.onerror=function(){B.fail(t,'read error')};f.readAsDataURL(b.slice(o,o+S))}next()})
            .catch(function(e){B.fail(t,String(e))})})(${JSONObject.quote(url)},${JSONObject.quote(token)})"""
        web.evaluateJavascript(js, null)
    }

    private inner class BlobBridge {
        @JavascriptInterface
        fun chunk(token: String, b64: String) {
            val job = blobJobs[token] ?: return
            if (b64.length > MAX_BLOB_CHUNK_BASE64) { fail(token, getString(app.svetlo.R.string.label_94d5315e28)); return }
            runCatching { job.append(Base64.decode(b64, Base64.DEFAULT)) }
                .onFailure { fail(token, it.message) }
        }

        @JavascriptInterface
        fun done(token: String) {
            val job = blobJobs.remove(token) ?: return
            val ok = job.commit()
            val name = job.name
            Downloads.finishLocal(job.entry, job.target.contentUri, ok, if (ok) null else getString(app.svetlo.R.string.label_9f476bf247), job.bytes)
            main.post { toast(if (ok) "Скачано: $name" else "Не удалось сохранить $name") }
        }

        @JavascriptInterface
        fun fail(token: String, message: String?) {
            val job = blobJobs.remove(token) ?: return
            job.abort()
            val name = job.name
            Downloads.finishLocal(job.entry, null, false, message, job.bytes)
            main.post { toast("Не удалось скачать $name: ${message ?: getString(app.svetlo.R.string.label_c394f7f85f)}") }
        }
    }

    private fun cancelBlobJobs(tab: Tab) {
        blobJobs.entries.toList().forEach { (token, job) ->
            if (job.owner === tab && blobJobs.remove(token, job)) {
                job.abort()
                Downloads.finishLocal(job.entry, null, false, getString(app.svetlo.R.string.label_2a38591b3f), job.bytes)
            }
        }
    }

    private fun downloadDirect(url: String, name: String, tab: Tab?) = withStoragePermission {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Svetlo/$name")
            requestHeaders(url, tab).filterKeys { it != "X-Svetlo-Origin" }.forEach { (k, v) -> req.addRequestHeader(k, v) }
            val id = getSystemService(DownloadManager::class.java).enqueue(req)
            Downloads.recordSystem(this, id, name, url, mimeFor(name, null))
            toast("Скачивание: $name")
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { action(); return }
        requestAppPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) action() else toast(getString(app.svetlo.R.string.label_f108c515df))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        pendingPermissions.remove(requestCode)?.invoke()
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
            actions += getString(app.svetlo.R.string.label_97acdc2609) to { newTab(link, parent = tab); Unit }
            actions += getString(app.svetlo.R.string.label_3d2d68e371) to { newTab(link, background = true, parent = tab); Unit }
            if (!incognito) actions += getString(app.svetlo.R.string.label_19089b985a) to { openIncognito(link) }
            actions += getString(app.svetlo.R.string.label_69d7d7248a) to { copy(link) }
            actions += getString(app.svetlo.R.string.label_d11806aee2) to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
            }
            actions += getString(app.svetlo.R.string.label_a02ed5f254) to { downloadDirect(link, URLUtil.guessFileName(link, null, null), tab) }
        }
        if (image != null && image.startsWith("http")) {
            actions += getString(app.svetlo.R.string.label_1d77a17dc0) to { newTab(image, parent = tab); Unit }
            actions += getString(app.svetlo.R.string.label_83ac3ff759) to { downloadDirect(image, URLUtil.guessFileName(image, null, "image/*"), tab) }
            actions += getString(app.svetlo.R.string.label_6c09637d78) to { copy(image) }
        } else if (image != null && image.startsWith("data:image/")) {
            actions += getString(app.svetlo.R.string.label_83ac3ff759) to { downloadData(image, fileNameFor(image, null, null)) }
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
        if (Build.VERSION.SDK_INT < 33) toast(getString(app.svetlo.R.string.label_8922542f90))
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
            else progress.setProgress(tab.progress, motionDuration(150) > 0L)
        } else if (progress.visibility == View.VISIBLE && progress.alpha == 1f) {
            progress.setProgress(100, true)
            progress.animate().alpha(0f).setStartDelay(if (motionDuration(200) == 0L) 0 else 150).setDuration(motionDuration(200)).withEndAction {
                progress.visibility = View.INVISIBLE
                progress.progress = 0
                progress.animate().startDelay = 0
            }.start()
        }
    }

    private fun onPageScroll(tab: Tab, y: Int, oldY: Int) {
        if (tab !== current || !Prefs.autoHideBar) return
        // Viewport resizes at the end of a bar transition can nudge scrollY; don't react to them.
        if (SystemClock.uptimeMillis() < ignoreScrollUntil) { scrollAccum = 0; return }
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

    /*
     * Chrome-like browser controls. The toolbar keeps its place in the layout via a negative margin
     * while hidden, and only translations are animated. The WebView is resized once — when hiding
     * starts and when showing ends — at moments where the changed strip is off-screen or under the
     * bar, so the page never jumps or flashes an empty strip.
     */
    private val barHeight get() = toolbar.height.takeIf { it > 0 } ?: dp(56)

    /** Translation that makes a hidden-layout toolbar appear fully shown. */
    private val shownShift get() = if (Prefs.bottomBar) -barHeight.toFloat() else barHeight.toFloat()

    private val barsLaidOutHidden get() = (toolbar.layoutParams as LinearLayout.LayoutParams).let { it.topMargin != 0 || it.bottomMargin != 0 }

    private fun layoutBars(hidden: Boolean) {
        val lp = toolbar.layoutParams as LinearLayout.LayoutParams
        val top = if (hidden && !Prefs.bottomBar) -barHeight else 0
        val bottom = if (hidden && Prefs.bottomBar) -barHeight else 0
        if (lp.topMargin == top && lp.bottomMargin == bottom) return
        lp.topMargin = top
        lp.bottomMargin = bottom
        toolbar.layoutParams = lp
    }

    private fun setBarShift(s: Float) {
        barShift = s
        toolbar.translationY = s
        progress.translationY = s
        webContainer.translationY = if (Prefs.bottomBar) 0f else s
    }

    private fun animateBarShift(to: Float, onEnd: () -> Unit) {
        barAnim?.cancel()
        val duration = motionDuration(200)
        if (duration == 0L) {
            setBarShift(to)
            onEnd()
            return
        }
        barAnim = ValueAnimator.ofFloat(barShift, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { setBarShift(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd() }
            })
            start()
        }
    }

    private fun resetBars() {
        barAnim?.cancel()
        barAnim = null
        setBarShift(0f)
        layoutBars(hidden = false)
    }

    private fun hideBars() {
        if (barsHidden || !canHideBars()) return
        barsHidden = true
        ignoreScrollUntil = SystemClock.uptimeMillis() + BAR_SETTLE_MS
        if (!barsLaidOutHidden) {
            layoutBars(hidden = true)
            setBarShift(shownShift)
        }
        animateBarShift(0f) {
            snackbar.bottomOffset = 0
            ignoreScrollUntil = SystemClock.uptimeMillis() + BAR_SETTLE_MS
        }
    }

    private fun showBars(animate: Boolean = true) {
        snackbar.bottomOffset = if (Prefs.bottomBar) dp(56) else 0
        if (!barsHidden) {
            if (!animate) resetBars()
            return
        }
        barsHidden = false
        scrollAccum = 0
        ignoreScrollUntil = SystemClock.uptimeMillis() + BAR_SETTLE_MS
        if (!animate || !barsLaidOutHidden) { resetBars(); return }
        animateBarShift(shownShift) {
            resetBars()
            ignoreScrollUntil = SystemClock.uptimeMillis() + BAR_SETTLE_MS
        }
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
            page.animate().translationX(dir * w).setDuration(motionDuration(180)).start()
            swipePeek.animate().translationX(0f).setDuration(motionDuration(180)).withEndAction {
                done()
                selectTab(next)
            }.start()
        } else {
            page.animate().translationX(0f).setDuration(motionDuration(180)).start()
            swipePeek.animate().translationX(if (dx < 0) w else -w).setDuration(motionDuration(180)).withEndAction(done).start()
        }
    }

    // ------------------------------------------------------------------ extra actions

    private fun showTabsPopup(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, getString(app.svetlo.R.string.label_d19457bde2))
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
        if (arr.length() == 0) { toast(getString(app.svetlo.R.string.label_603efe32a4)); return }
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
        AlertDialog.Builder(this).setTitle(getString(R.string.recent_tabs))
            .setItems(items.map { it.optString("t").ifBlank { it.optString("u") } }.toTypedArray()) { _, i ->
                newTab(items[i].optString("u"))
            }
            .setNeutralButton(getString(app.svetlo.R.string.label_98b2073ed1)) { _, _ -> Prefs.sp.edit().remove("recent_closed").apply() }
            .setNegativeButton(getString(app.svetlo.R.string.label_4ae50d3073), null)
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
        if (!sm.isRequestPinShortcutSupported) { toast(getString(app.svetlo.R.string.label_f4fb666b93)); return }
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
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(app.svetlo.R.string.label_574080fa70))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (_: Exception) {
            toast(getString(app.svetlo.R.string.label_f2a32462a8))
        }
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java)
        val desc = cm.primaryClipDescription ?: return null
        if (!desc.hasMimeType("text/*")) return null
        return cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 500 }
    }


    companion object {
        private const val REQ_NOTIFY = 2
        private const val REQ_FILE = 3
        private const val REQ_LIBRARY = 4
        private const val REQ_SETTINGS = 5
        private const val REQ_VOICE = 6
        private const val REQ_ONBOARDING = 7
        private const val REQ_READER = 8
        private const val STATE_VERSION = 1
        private const val BAR_SETTLE_MS = 300L
        private const val BLOB_BRIDGE = "SvetloBlob"
        private const val MAX_BLOB_CHUNK_BASE64 = 524_288
    }
}

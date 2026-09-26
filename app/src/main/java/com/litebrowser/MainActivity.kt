package com.litebrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONTokener

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var btnVideos: Button
    private lateinit var progress: ProgressBar
    private val media = MediaDetector()
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    @Volatile private var adblockOn = true
    @Volatile private var pageUrl: String? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingDownload: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AdBlocker.init(this)
        adblockOn = prefs.getBoolean("adblock", true)

        web = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        btnVideos = findViewById(R.id.btnVideos)
        progress = findViewById(R.id.progress)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // Blocks most pop-up / pop-under ads.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            confirm("Скачать файл?", name) { downloadDirect(url, name) }
        }
        web.setOnLongClickListener { onLongPress() }

        findViewById<Button>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener(::showMenu)
        btnVideos.setOnClickListener { showVideos() }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate(urlBar.text.toString())
                true
            } else false
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(intent?.dataString ?: HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { web.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            !text.contains(' ') && text.contains('.') -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(text)
        }
        web.loadUrl(url)
        web.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun updateVideoCount() = runOnUiThread { btnVideos.text = "⬇ ${media.count}" }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            // Never block the page the user explicitly opened.
            if (adblockOn && !request.isForMainFrame && AdBlocker.isBlocked(uri)) {
                AdBlocker.blockedCount.incrementAndGet()
                return AdBlocker.emptyResponse()
            }
            if (media.offer(uri.toString())) updateVideoCount()
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "http" || uri.scheme == "https") return false
            try {
                val intent = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                else Intent(Intent.ACTION_VIEW, uri)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                startActivity(intent)
            } catch (_: Exception) {
                // No app can handle it; for intent:// links use the declared fallback URL.
                val fallback = runCatching {
                    Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                }.getOrNull()
                if (fallback != null) view.loadUrl(fallback)
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url != pageUrl) {
                media.clear()
                updateVideoCount()
            }
            pageUrl = url
            if (!urlBar.hasFocus()) urlBar.setText(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!urlBar.hasFocus()) urlBar.setText(url)
            if (adblockOn) view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
            scanDom()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            findViewById<FrameLayout>(R.id.root).addView(
                view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            findViewById<View>(R.id.content).visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        override fun onHideCustomView() = exitFullscreen()
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        findViewById<FrameLayout>(R.id.root).removeView(v)
        findViewById<View>(R.id.content).visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
    }

    private fun scanDom(then: (() -> Unit)? = null) {
        web.evaluateJavascript(MediaDetector.SCAN_JS) { result ->
            runCatching {
                val arr = JSONArray(JSONTokener(result).nextValue() as String)
                for (i in 0 until arr.length()) media.offerDom(arr.getString(i))
            }
            updateVideoCount()
            then?.invoke()
        }
    }

    private fun showVideos() {
        scanDom {
            val items = media.list()
            if (items.isEmpty()) {
                Toast.makeText(this, "Видео не найдено. Запустите воспроизведение и попробуйте снова.", Toast.LENGTH_LONG).show()
                return@scanDom
            }
            AlertDialog.Builder(this)
                .setTitle("Найденные медиа")
                .setItems(items.map { it.label }.toTypedArray()) { _, i -> startMediaDownload(items[i]) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun startMediaDownload(item: MediaItem) {
        if (item.isHls) {
            withStoragePermission {
                requestNotificationPermission()
                HlsDownloadService.start(this, item.url, requestHeaders(item.url))
                Toast.makeText(this, "Загрузка потока началась", Toast.LENGTH_SHORT).show()
            }
        } else {
            downloadDirect(item.url, URLUtil.guessFileName(item.url, null, null))
        }
    }

    private fun onLongPress(): Boolean {
        val hit = web.hitTestResult
        val url = hit.extra ?: return false
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (!url.startsWith("http")) return false
                val name = URLUtil.guessFileName(url, null, null)
                AlertDialog.Builder(this)
                    .setTitle(url)
                    .setItems(arrayOf("Скачать", "Копировать ссылку", "Открыть")) { _, i ->
                        when (i) {
                            0 -> downloadDirect(url, name)
                            1 -> {
                                val cm = getSystemService(android.content.ClipboardManager::class.java)
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                            }
                            2 -> web.loadUrl(url)
                        }
                    }
                    .show()
                true
            }
            else -> false
        }
    }

    private fun requestHeaders(url: String): HashMap<String, String> {
        val h = hashMapOf("User-Agent" to web.settings.userAgentString)
        pageUrl?.let { h["Referer"] = it }
        CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
        return h
    }

    private fun downloadDirect(url: String, name: String) = withStoragePermission {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "LiteBrowser/$name")
            requestHeaders(url).forEach { (k, v) -> req.addRequestHeader(k, v) }
            getSystemService(DownloadManager::class.java).enqueue(req)
            Toast.makeText(this, "Скачивание: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_STORAGE) {
            val action = pendingDownload
            pendingDownload = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
            else Toast.makeText(this, "Нужно разрешение на запись файлов", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Да") { _, _ -> onYes() }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Блокировка рекламы").apply { isCheckable = true; isChecked = adblockOn }
        menu.menu.add(0, 2, 1, "Обновить фильтры (${AdBlocker.size})")
        menu.menu.add(0, 3, 2, "Заблокировано: ${AdBlocker.blockedCount.get()}").isEnabled = false
        menu.menu.add(0, 4, 3, "Обновить страницу")
        menu.menu.add(0, 5, 4, "Домой")
        menu.menu.add(0, 6, 5, "Очистить данные сайтов")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    adblockOn = !adblockOn
                    prefs.edit().putBoolean("adblock", adblockOn).apply()
                    web.reload()
                }
                2 -> AdBlocker.update(this) { r ->
                    runOnUiThread {
                        val msg = r.fold({ "Фильтры обновлены: $it доменов" }, { "Ошибка обновления: ${it.message}" })
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
                4 -> web.reload()
                5 -> web.loadUrl(HOME)
                6 -> {
                    web.clearCache(true)
                    web.clearHistory()
                    CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    Toast.makeText(this, "Данные очищены", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        menu.show()
    }

    companion object {
        private const val HOME = "https://duckduckgo.com/"
        private const val REQ_STORAGE = 1
        private const val REQ_NOTIFY = 2
    }
}

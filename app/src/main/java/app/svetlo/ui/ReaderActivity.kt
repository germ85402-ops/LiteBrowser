package app.svetlo.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import app.svetlo.Article
import app.svetlo.R
import app.svetlo.Reader
import app.svetlo.data.Prefs

/** Distraction-free view of an extracted article. Links return to the browser tab. */
class ReaderActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var article: Article
    private var pendingScroll = -1f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        article = pending ?: run { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(iconButton(this, R.drawable.ic_arrow_back, getString(R.string.back)) { finish() })
        bar.addView(TextView(this).apply {
            text = Uri.parse(article.url).host?.removePrefix("www.") ?: ""
            textSize = 15f
            isSingleLine = true
            setTextColor(color(R.color.c_text2))
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(textButton("A−", "Уменьшить шрифт") { setFont(Prefs.readerFont - 2) })
        bar.addView(textButton("A+", "Увеличить шрифт") { setFont(Prefs.readerFont + 2) })
        bar.addView(textButton("Aa", "Шрифт с засечками") { Prefs.readerSerif = !Prefs.readerSerif; render(keepScroll = true) })
        bar.addView(textButton("◐", "Тема") { Prefs.readerTheme = (theme().ordinal + 1) % Reader.Theme.entries.size; render(keepScroll = true) })
        bar.addView(iconButton(this, R.drawable.ic_share, getString(R.string.share)) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, article.url), null))
        })
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, dp(56))

        web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.textZoom = 100
            isVerticalScrollBarEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, req.url.toString()))
                    finish()
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val ratio = pendingScroll
                    if (ratio < 0) return
                    pendingScroll = -1f
                    view.postDelayed({ view.scrollTo(0, (ratio * view.contentHeight * view.scale).toInt()) }, 50)
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render(keepScroll = false)
    }

    private fun textButton(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        contentDescription = desc
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun theme(): Reader.Theme {
        val saved = Prefs.readerTheme
        if (saved in Reader.Theme.entries.indices) return Reader.Theme.entries[saved]
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (night) Reader.Theme.DARK else Reader.Theme.LIGHT
    }

    private fun setFont(px: Int) {
        Prefs.readerFont = px.coerceIn(14, 30)
        render(keepScroll = true)
    }

    private fun render(keepScroll: Boolean) {
        val t = theme()
        val bg = Color.parseColor(t.bg)
        val fg = Color.parseColor(t.fg)
        bar.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.setTextColor(if (i == 1) Color.parseColor(t.muted) else fg)
        for (i in 0 until bar.childCount) (bar.getChildAt(i) as? android.widget.ImageButton)?.setColorFilter(fg)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (t == Reader.Theme.DARK) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        web.setBackgroundColor(bg)
        if (keepScroll && web.contentHeight > 0) pendingScroll = web.scrollY / (web.contentHeight * web.scale)
        web.loadDataWithBaseURL(article.url, Reader.page(article, t, Prefs.readerFont, Prefs.readerSerif), "text/html", "utf-8", null)
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        if (isFinishing) pending = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"

        /** Article handed over by the browser; kept in memory because it can exceed Intent size limits. */
        var pending: Article? = null
    }
}

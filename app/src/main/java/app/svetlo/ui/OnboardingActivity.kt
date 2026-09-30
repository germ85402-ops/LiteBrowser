package app.svetlo.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.svetlo.R
import app.svetlo.data.Prefs
import app.svetlo.data.SearchEngine

/** First-run setup: search engine, bar position, key toggles, default browser. Always finishes with RESULT_OK. */
class OnboardingActivity : Activity() {
    private lateinit var defaultBtn: TextView
    private val barCards = arrayOfNulls<LinearLayout>(2)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        window.statusBarColor = color(R.color.c_bg)
        window.navigationBarColor = color(R.color.c_bg)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(16))
        }
        header(body)

        section(body, getString(app.svetlo.R.string.label_c464388cf3))
        val group = RadioGroup(this).apply {
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        SearchEngine.entries.forEach { e ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = e.title
                textSize = 16f
                setTextColor(color(R.color.c_text))
                minHeight = dp(48)
                setPadding(dp(8), 0, 0, 0)
                isChecked = e == Prefs.searchEngine
                setOnCheckedChangeListener { _, on -> if (on) Prefs.searchEngine = e }
            }, RadioGroup.LayoutParams(MATCH, WRAP))
        }
        body.addView(group, LinearLayout.LayoutParams(MATCH, WRAP))

        section(body, getString(app.svetlo.R.string.label_a25f3cd79e))
        val cards = LinearLayout(this)
        listOf(false to getString(app.svetlo.R.string.label_aa6b22c2d4), true to getString(app.svetlo.R.string.label_fa1881309a)).forEachIndexed { i, (bottom, label) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(12), dp(16), dp(12), dp(12))
                isClickable = true
                contentDescription = "Адресная строка ${label.lowercase()}"
                setOnClickListener { Prefs.bottomBar = bottom; updateBarCards() }
            }
            card.addView(ImageView(this).apply { setImageDrawable(PhoneSketch(this@OnboardingActivity, bottom)) },
                LinearLayout.LayoutParams(dp(56), dp(92)))
            card.addView(TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.c_text))
                setPadding(0, dp(10), 0, 0)
            })
            barCards[i] = card
            cards.addView(card, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i == 0) marginEnd = dp(6) else marginStart = dp(6)
            })
        }
        body.addView(cards, LinearLayout.LayoutParams(MATCH, WRAP))
        updateBarCards()

        section(body, getString(app.svetlo.R.string.label_127492c294))
        val toggles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(0, dp(4), 0, dp(4))
        }
        toggle(toggles, getString(app.svetlo.R.string.label_7f2be1ba3d), getString(app.svetlo.R.string.label_18e40f59bd), Prefs.adblock) { Prefs.adblock = it }
        toggle(toggles, getString(app.svetlo.R.string.label_e4dd681cb3), getString(app.svetlo.R.string.label_d1caf6841a), Prefs.autoHideBar) { Prefs.autoHideBar = it }
        if (Build.VERSION.SDK_INT >= 29) {
            toggle(toggles, getString(app.svetlo.R.string.label_5c036faae2), getString(app.svetlo.R.string.label_272eec4fdb), Prefs.darkPages) { Prefs.darkPages = it }
        }
        body.addView(toggles, LinearLayout.LayoutParams(MATCH, WRAP))

        defaultBtn = TextView(this).apply {
            text = getString(app.svetlo.R.string.label_c029581acc)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.c_accent))
            background = getDrawable(R.drawable.bg_chip)
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { requestDefault() }
        }
        body.addView(defaultBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })

        val start = TextView(this).apply {
            text = getString(app.svetlo.R.string.label_14eaa871df)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.c_on_accent))
            background = getDrawable(R.drawable.bg_fab)
            minHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { finish() }
        }
        val footer = LinearLayout(this).apply {
            setBackgroundColor(color(R.color.c_bg))
            setPadding(dp(20), dp(10), dp(20), dp(16))
            addView(start, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.c_bg))
            addView(ScrollView(this@OnboardingActivity).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        })
    }

    override fun onResume() {
        super.onResume()
        defaultBtn.visibility = if (isDefaultBrowser()) View.GONE else View.VISIBLE
    }

    // Leaving by any route (button or back) completes onboarding with whatever is selected.
    override fun finish() {
        Prefs.onboarded = true
        setResult(RESULT_OK)
        super.finish()
    }

    private fun header(body: LinearLayout) {
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_brand)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val name = SpannableString("Svetlo").apply {
            setSpan(ForegroundColorSpan(color(R.color.c_accent)), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.addView(TextView(this).apply {
            text = name
            textSize = 30f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.c_text))
            setPadding(0, dp(12), 0, 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(TextView(this).apply {
            text = getString(app.svetlo.R.string.label_e7c4f50072)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.c_text2))
            setPadding(0, dp(4), 0, dp(4))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun section(body: LinearLayout, title: String) {
        body.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.c_accent))
            setPadding(dp(4), dp(24), dp(4), dp(8))
        })
    }

    private fun toggle(parent: LinearLayout, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(8), dp(12), dp(8))
            background = themeDrawable(android.R.attr.selectableItemBackground)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(color(R.color.c_text)) })
        texts.addView(TextView(this).apply { text = summary; textSize = 13f; setTextColor(color(R.color.c_text2)) })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun updateBarCards() {
        barCards.forEachIndexed { i, card ->
            val on = (i == 1) == Prefs.bottomBar
            card?.background = getDrawable(if (on) R.drawable.bg_card_selected else R.drawable.bg_card)
            card?.isSelected = on
        }
    }

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) return rm.isRoleHeld(RoleManager.ROLE_BROWSER)
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        return packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun requestDefault() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                if (launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQ_ROLE)) return
            }
        }
        if (!launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) launch(Intent(Settings.ACTION_SETTINGS))
    }

    private fun launch(intent: Intent, req: Int = -1) = try {
        if (req >= 0) startActivityForResult(intent, req) else startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Minimal phone outline showing where the address bar sits. */
    private class PhoneSketch(private val ctx: Context, private val bottom: Boolean) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun draw(c: Canvas) {
            val b = bounds
            val d = ctx.resources.displayMetrics.density
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            r.set(b.left + d, b.top + d, b.right - d, b.bottom - d)
            p.style = Paint.Style.FILL; p.color = ctx.color(R.color.c_surface2)
            c.drawRoundRect(r, 8 * d, 8 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = ctx.color(R.color.c_divider)
            c.drawRoundRect(r, 8 * d, 8 * d, p)
            p.style = Paint.Style.FILL
            val barH = 10 * d
            val pad = 6 * d
            val barTop = if (bottom) b.top + h - pad - barH else b.top + pad
            p.color = ctx.color(R.color.c_accent)
            r.set(b.left + pad, barTop, b.left + w - pad, barTop + barH)
            c.drawRoundRect(r, barH / 2, barH / 2, p)
            p.color = ctx.color(R.color.c_divider)
            val first = if (bottom) b.top + pad + 4 * d else barTop + barH + 8 * d
            for (i in 0 until 4) {
                val y = first + i * 11 * d
                val right = if (i == 3) b.left + w * 0.6f else b.left + w - pad
                r.set(b.left + pad, y, right, y + 5 * d)
                c.drawRoundRect(r, 2.5f * d, 2.5f * d, p)
            }
        }

        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val REQ_ROLE = 41
    }
}

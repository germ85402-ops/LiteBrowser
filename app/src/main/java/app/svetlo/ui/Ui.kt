package app.svetlo.ui

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.svetlo.R
import kotlin.math.abs

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.color(id: Int) = getColor(id)

/** Duration that follows Android's system-wide "Remove animations" setting. */
fun Context.motionDuration(defaultMs: Long): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) 0L else defaultMs

fun Context.themeDrawable(attr: Int): Drawable? =
    getDrawable(TypedValue().also { theme.resolveAttribute(attr, it, true) }.resourceId)

fun View.showKeyboard() {
    requestFocus()
    context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

fun View.hideKeyboard() {
    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
}

fun hostOf(url: String) = Uri.parse(url).host?.removePrefix("www.")?.removePrefix("m.") ?: url

/** Round monogram used in place of favicons. */
class LetterIcon(key: String, label: String? = null) : Drawable() {
    private val host = hostOf(key)
    private val letter = (label?.takeIf { it.isNotBlank() } ?: host).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PALETTE[abs(host.hashCode() % PALETTE.size)] }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val tb = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, bg)
        fg.textSize = r * 0.95f
        fg.getTextBounds(letter, 0, letter.length, tb)
        canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() + tb.height() / 2f, fg)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; fg.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { bg.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private val PALETTE = intArrayOf(
            0xFF3A67F0.toInt(), 0xFFE0533D.toInt(), 0xFF2E9E6A.toInt(), 0xFF8E54E9.toInt(),
            0xFFEF8A17.toInt(), 0xFF1C9BB8.toInt(), 0xFFD6457F.toInt(), 0xFF5B6B85.toInt(),
        )
    }
}

fun iconButton(ctx: Context, icon: Int, desc: String, onClick: (View) -> Unit) = ImageButton(ctx).apply {
    setImageResource(icon)
    contentDescription = desc
    background = ctx.themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
    layoutParams = LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48))
    if (Build.VERSION.SDK_INT >= 26) tooltipText = desc
    setOnClickListener(onClick)
}

/** Settings-style page built in code: top bar + scrollable list of rows. */
class Page(private val act: Activity, title: String) {
    val body: LinearLayout
    val actions: LinearLayout
    private val root: LinearLayout
    private val indexed = mutableListOf<Pair<View, () -> String>>()
    private val headers = mutableSetOf<View>()
    private var searchQuery = ""
    private var noResults: TextView? = null

    init {
        root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(act.color(R.color.c_bg)); isFocusableInTouchMode = true }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(act.color(R.color.c_toolbar))
            setPadding(act.dp(4), 0, act.dp(4), 0)
        }
        bar.addView(iconButton(act, R.drawable.ic_arrow_back, act.getString(R.string.back)) { act.finish() })
        bar.addView(TextView(act).apply {
            text = title
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(act.color(R.color.c_text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(act.dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions = LinearLayout(act)
        bar.addView(actions)
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, act.dp(56))
        body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, act.dp(24)) }
        val frame = android.widget.FrameLayout(act)
        frame.addView(ScrollView(act).apply { addView(body); isFillViewport = true }, android.widget.FrameLayout.LayoutParams(
            if (act.resources.configuration.screenWidthDp >= 720) act.dp(720) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        act.setContentView(root)
    }

    fun header(text: String) {
        val view = TextView(act).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(act.color(R.color.c_accent))
            setPadding(act.dp(20), act.dp(22), act.dp(20), act.dp(6))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        body.addView(view)
        headers += view
        indexed += view to { text }
    }

    fun enableSearch() {
        val field = android.widget.EditText(act).apply {
            hint = act.getString(R.string.settings_search)
            setTextColor(act.color(R.color.c_text)); setHintTextColor(act.color(R.color.c_text2))
            textSize = 16f; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(act.dp(16), 0, act.dp(16), 0)
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val searchBar = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = act.getDrawable(R.drawable.bg_omnibox)
        }
        searchBar.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val clear = iconButton(act, R.drawable.ic_close_small, act.getString(R.string.clear)) { field.text.clear(); field.showKeyboard() }
        clear.visibility = View.GONE
        searchBar.addView(clear)
        root.addView(searchBar, 1, LinearLayout.LayoutParams(
            if (act.resources.configuration.screenWidthDp >= 760) act.dp(720) else ViewGroup.LayoutParams.MATCH_PARENT, act.dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            marginStart = act.dp(16); marginEnd = act.dp(16); topMargin = act.dp(8); bottomMargin = act.dp(8)
        })
        noResults = TextView(act).apply {
            text = act.getString(R.string.settings_no_results); textSize = 16f
            setTextColor(act.color(R.color.c_text2)); gravity = Gravity.CENTER
            setPadding(act.dp(24), act.dp(40), act.dp(24), 0); visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(body::addView)
        field.setOnEditorActionListener { _, _, _ -> field.hideKeyboard(); true }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s.toString().trim()
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                filterRows()
            }
        })
    }

    private fun filterRows() {
        var header: View? = null
        var headerMatches = false
        var found = false
        indexed.forEach { (view, text) ->
            if (view in headers) {
                header = view; headerMatches = text().contains(searchQuery, true); view.visibility = View.GONE
            } else {
                val match = searchQuery.isEmpty() || headerMatches || text().contains(searchQuery, true)
                view.visibility = if (match) View.VISIBLE else View.GONE
                if (match) { found = true; header?.visibility = View.VISIBLE }
            }
        }
        noResults?.visibility = if (found) View.GONE else View.VISIBLE
    }

    class Row(val view: LinearLayout, val title: TextView, val summary: TextView, val switch: Switch?) {
        fun setSummary(text: String?) {
            summary.text = text
            summary.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    fun row(title: String, summary: String? = null, onClick: ((Row) -> Unit)? = null) = addRow(title, summary, null, onClick)

    fun switchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): Row {
        val row = addRow(title, summary, checked, null)
        row.switch!!.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.view.setOnClickListener { row.switch.toggle() }
        return row
    }

    private fun addRow(title: String, summary: String?, checked: Boolean?, onClick: ((Row) -> Unit)?): Row {
        val v = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = act.dp(60)
            setPadding(act.dp(20), act.dp(10), act.dp(16), act.dp(10))
            background = act.themeDrawable(android.R.attr.selectableItemBackground)
        }
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(act).apply { text = title; textSize = 16f; setTextColor(act.color(R.color.c_text)) }
        val s = TextView(act).apply { textSize = 13f; setTextColor(act.color(R.color.c_text2)) }
        texts.addView(t); texts.addView(s)
        v.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = checked?.let { Switch(act).apply { isChecked = it; isClickable = false; isFocusable = false } }
        sw?.let { v.addView(it) }
        val row = Row(v, t, s, sw)
        row.setSummary(summary)
        onClick?.let { cb -> v.setOnClickListener { cb(row) } }
        if (onClick != null || sw != null) {
            v.isFocusable = true
            texts.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            sw?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            v.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.text = listOfNotNull(t.text, s.text.takeIf { s.visibility == View.VISIBLE }).joinToString(". ")
                    if (sw != null) { info.className = Switch::class.java.name; info.isCheckable = true; info.isChecked = sw.isChecked }
                }
            }
        }
        body.addView(v)
        indexed += v to { "${t.text} ${s.text}" }
        return row
    }
}

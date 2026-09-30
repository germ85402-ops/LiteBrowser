package app.svetlo.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import app.svetlo.R
import app.svetlo.Tab

/**
 * Grid of tab cards. Opening zooms the current page into its card and selecting zooms the
 * card back to full screen; cards can be swiped away.
 */
class TabSwitcher(
    private val act: Activity,
    private val root: View,
    private val zoom: ImageView,
    private val tabs: List<Tab>,
    private val incognito: Boolean,
    private val current: () -> Tab?,
    private val pageRect: () -> Rect,
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    onNew: () -> Unit,
    onCloseAll: () -> Unit,
    onToggleMode: () -> Unit,
) {
    private val grid = root.findViewById<GridView>(R.id.tsGrid)
    private val title = root.findViewById<TextView>(R.id.tsTitle)
    private val search = root.findViewById<android.widget.EditText>(R.id.tsSearch)
    private val clear = root.findViewById<View>(R.id.tsClear)
    private val empty = root.findViewById<View>(R.id.tsEmpty)
    private var query = ""
    private val visibleTabs get() = tabs.filter { query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true) }
    private val adapter = Adapter()
    private var hiddenTab: Tab? = null
    private var anim: ValueAnimator? = null

    val isShown get() = root.visibility == View.VISIBLE

    init {
        root.isFocusableInTouchMode = true
        grid.adapter = adapter
        updateColumns()
        clear.setOnClickListener { search.text.clear(); search.showKeyboard() }
        search.setOnEditorActionListener { _, _, _ -> search.hideKeyboard(); true }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s.toString().trim()
                clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                refreshResults()
            }
        })
        root.findViewById<View>(R.id.tsBack).setOnClickListener { close() }
        root.findViewById<View>(R.id.tsNew).setOnClickListener { search.hideKeyboard(); onNew() }
        root.findViewById<View>(R.id.tsCloseAll).setOnClickListener { onCloseAll() }
        root.findViewById<ImageButton>(R.id.tsMode).apply {
            setImageResource(if (incognito) R.drawable.ic_tab_square else R.drawable.ic_incognito)
            contentDescription = if (incognito) act.getString(app.svetlo.R.string.label_1fcbde02de) else act.getString(app.svetlo.R.string.label_86b552e23a)
            setOnClickListener { onToggleMode() }
        }
    }

    fun show() {
        if (isShown) return
        search.text.clear()
        search.clearFocus()
        refresh()
        val cur = current()
        val pos = visibleTabs.indexOf(cur).coerceAtLeast(0)
        root.visibility = View.VISIBLE
        root.requestFocus()
        root.alpha = 0f
        val fadeIn = act.motionDuration(200)
        if (fadeIn == 0L) root.alpha = 1f else root.animate().alpha(1f).setDuration(fadeIn).start()
        grid.setSelection(pos)
        val bmp = cur?.thumbnail ?: return
        hiddenTab = cur
        adapter.notifyDataSetChanged()
        zoom.setImageBitmap(bmp)
        onLaidOut {
            val target = thumbRect(pos)
            if (target == null) {
                hiddenTab = null
                adapter.notifyDataSetChanged()
            } else {
                animateZoom(pageRect(), target) {
                    hiddenTab = null
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    /** Leaves the switcher by zooming into [tab] (the current tab by default). */
    fun close(tab: Tab? = current()) {
        if (!isShown) return
        search.hideKeyboard()
        search.clearFocus()
        if (tab == null) { hide(); return }
        val from = thumbRect(visibleTabs.indexOf(tab))
        onSelect(tab)
        val bmp = tab.thumbnail
        if (from == null || bmp == null) { hide(); return }
        hiddenTab = tab
        adapter.notifyDataSetChanged()
        zoom.setImageBitmap(bmp)
        animateZoom(from, pageRect()) { hiddenTab = null }
        val fadeOut = act.motionDuration(180)
        root.animate().alpha(0f).setStartDelay(if (fadeOut == 0L) 0 else 60).setDuration(fadeOut).withEndAction {
            root.visibility = View.GONE
            root.animate().startDelay = 0
        }.start()
    }

    fun hide() {
        if (!isShown) return
        search.hideKeyboard()
        search.clearFocus()
        root.animate().alpha(0f).setDuration(act.motionDuration(150)).withEndAction { root.visibility = View.GONE }.start()
    }

    fun refresh() {
        updateColumns()
        title.text = if (incognito) act.resources.getQuantityString(R.plurals.incognito_tabs_count, tabs.size, tabs.size)
        else act.resources.getQuantityString(R.plurals.tabs_count, tabs.size, tabs.size)
        refreshResults()
    }

    private fun updateColumns() {
        val config = act.resources.configuration
        val minWidth = if (config.fontScale >= 1.3f) 260 else 220
        grid.numColumns = (config.screenWidthDp / minWidth).coerceIn(if (config.screenWidthDp < 360 || config.fontScale >= 1.3f) 1 else 2, 5)
    }

    private fun refreshResults() {
        adapter.notifyDataSetChanged()
        empty.visibility = if (visibleTabs.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (visibleTabs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onLaidOut(action: () -> Unit) {
        grid.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.viewTreeObserver.removeOnPreDrawListener(this)
                action()
                return true
            }
        })
    }

    private fun thumbRect(pos: Int): Rect? {
        if (pos < 0) return null
        val card = grid.getChildAt(pos - grid.firstVisiblePosition) ?: return null
        val thumb = card.findViewById<View>(R.id.tabThumb)
        if (thumb.width == 0) return null
        val a = IntArray(2)
        val b = IntArray(2)
        thumb.getLocationInWindow(a)
        (zoom.parent as View).getLocationInWindow(b)
        val l = a[0] - b[0]
        val t = a[1] - b[1]
        return Rect(l, t, l + thumb.width, t + thumb.height)
    }

    /**
     * Moves the overlay from rect [a] to rect [b]. The overlay is laid out at the page size and
     * scaled uniformly; a rounded outline clips it to the current rect height.
     */
    private fun animateZoom(a: Rect, b: Rect, end: () -> Unit) {
        anim?.cancel()
        val page = pageRect()
        val lp = zoom.layoutParams as FrameLayout.LayoutParams
        lp.width = page.width()
        lp.height = page.height()
        lp.leftMargin = page.left
        lp.topMargin = page.top
        zoom.layoutParams = lp
        zoom.pivotX = 0f
        zoom.pivotY = 0f
        zoom.clipToOutline = true
        val radiusPx = act.dp(14).toFloat()
        var clipH = page.height().toFloat()
        var radius = 0f
        zoom.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, clipH.toInt(), radius)
            }
        }
        fun state(r: Rect): FloatArray {
            val s = r.width().toFloat() / page.width()
            return floatArrayOf(s, (r.left - page.left).toFloat(), (r.top - page.top).toFloat(), r.height() / s, if (r == page) 0f else radiusPx / s)
        }
        val sa = state(a)
        val sb = state(b)
        zoom.visibility = View.VISIBLE
        val zoomDuration = act.motionDuration(280)
        if (zoomDuration == 0L) {
            zoom.visibility = View.GONE
            zoom.setImageDrawable(null)
            end()
            return
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = zoomDuration
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                fun lerp(i: Int) = sa[i] + (sb[i] - sa[i]) * t
                zoom.scaleX = lerp(0)
                zoom.scaleY = lerp(0)
                zoom.translationX = lerp(1)
                zoom.translationY = lerp(2)
                clipH = lerp(3)
                radius = lerp(4)
                zoom.invalidateOutline()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    zoom.visibility = View.GONE
                    zoom.setImageDrawable(null)
                    end()
                }
            })
            start()
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = visibleTabs.size
        override fun getItem(position: Int) = visibleTabs[position]
        override fun getItemId(position: Int) = System.identityHashCode(visibleTabs[position]).toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: act.layoutInflater.inflate(R.layout.item_tab, parent, false).apply { clipToOutline = true }
            val tab = visibleTabs[position]
            v.animate().cancel()
            v.translationX = 0f
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.isSelected = tab === current()
            v.setBackgroundResource(if (tab === current()) R.drawable.bg_card_selected else R.drawable.bg_card)
            v.findViewById<TextView>(R.id.tabTitle).text = tab.displayTitle()
            val icon = v.findViewById<ImageView>(R.id.tabIcon)
            when {
                tab.isNtp -> icon.setImageResource(if (incognito) R.drawable.ic_incognito_small else R.drawable.ic_brand)
                tab.favicon != null -> icon.setImageBitmap(tab.favicon)
                else -> icon.setImageDrawable(LetterIcon(tab.url))
            }
            v.findViewById<ImageView>(R.id.tabThumb).apply {
                setImageBitmap(tab.thumbnail)
                visibility = if (tab === hiddenTab) View.INVISIBLE else View.VISIBLE
            }
            v.findViewById<View>(R.id.tabClose).contentDescription = act.getString(R.string.close_named_tab, tab.displayTitle())
            v.findViewById<View>(R.id.tabClose).setOnClickListener {
                v.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(act.motionDuration(150)).withEndAction {
                    v.scaleX = 1f; v.scaleY = 1f
                    onClose(tab)
                }.start()
            }
            v.setOnClickListener { close(tab) }
            v.setOnTouchListener(SwipeToDismiss { onClose(tab) })
            return v
        }
    }
}

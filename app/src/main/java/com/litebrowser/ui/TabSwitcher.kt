package com.litebrowser.ui

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
import com.litebrowser.R
import com.litebrowser.Tab

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
    private val adapter = Adapter()
    private var hiddenTab: Tab? = null
    private var anim: ValueAnimator? = null

    val isShown get() = root.visibility == View.VISIBLE

    init {
        grid.adapter = adapter
        root.findViewById<View>(R.id.tsBack).setOnClickListener { close() }
        root.findViewById<View>(R.id.tsNew).setOnClickListener { onNew() }
        root.findViewById<View>(R.id.tsCloseAll).setOnClickListener { onCloseAll() }
        root.findViewById<ImageButton>(R.id.tsMode).apply {
            setImageResource(if (incognito) R.drawable.ic_tab_square else R.drawable.ic_incognito)
            contentDescription = if (incognito) "Обычные вкладки" else "Новая вкладка инкогнито"
            setOnClickListener { onToggleMode() }
        }
    }

    fun show() {
        if (isShown) return
        refresh()
        val cur = current()
        val pos = tabs.indexOf(cur).coerceAtLeast(0)
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(200).start()
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
        if (tab == null) { hide(); return }
        val from = thumbRect(tabs.indexOf(tab))
        onSelect(tab)
        val bmp = tab.thumbnail
        if (from == null || bmp == null) { hide(); return }
        hiddenTab = tab
        adapter.notifyDataSetChanged()
        zoom.setImageBitmap(bmp)
        animateZoom(from, pageRect()) { hiddenTab = null }
        root.animate().alpha(0f).setStartDelay(60).setDuration(180).withEndAction { root.visibility = View.GONE; root.animate().startDelay = 0 }.start()
    }

    fun hide() {
        if (!isShown) return
        root.animate().alpha(0f).setDuration(150).withEndAction { root.visibility = View.GONE }.start()
    }

    fun refresh() {
        title.text = if (incognito) act.resources.getQuantityString(R.plurals.incognito_tabs_count, tabs.size, tabs.size)
        else act.resources.getQuantityString(R.plurals.tabs_count, tabs.size, tabs.size)
        adapter.notifyDataSetChanged()
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
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
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
        override fun getCount() = tabs.size
        override fun getItem(position: Int) = tabs[position]
        override fun getItemId(position: Int) = System.identityHashCode(tabs[position]).toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: act.layoutInflater.inflate(R.layout.item_tab, parent, false).apply { clipToOutline = true }
            val tab = tabs[position]
            v.animate().cancel()
            v.translationX = 0f
            v.alpha = 1f
            v.setBackgroundResource(if (tab === current()) R.drawable.bg_card_selected else R.drawable.bg_card)
            v.findViewById<TextView>(R.id.tabTitle).text = tab.displayTitle()
            val icon = v.findViewById<ImageView>(R.id.tabIcon)
            when {
                tab.isNtp -> icon.setImageResource(if (incognito) R.drawable.ic_incognito_small else R.drawable.ic_launcher)
                tab.favicon != null -> icon.setImageBitmap(tab.favicon)
                else -> icon.setImageDrawable(LetterIcon(tab.url))
            }
            v.findViewById<ImageView>(R.id.tabThumb).apply {
                setImageBitmap(tab.thumbnail)
                visibility = if (tab === hiddenTab) View.INVISIBLE else View.VISIBLE
            }
            v.findViewById<View>(R.id.tabClose).setOnClickListener {
                v.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(150).withEndAction {
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

package com.litebrowser.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import com.litebrowser.R
import com.litebrowser.Tab

class TabSwitcher(
    private val act: Activity,
    private val root: View,
    private val tabs: List<Tab>,
    private val current: () -> Tab?,
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    onNew: () -> Unit,
    onCloseAll: () -> Unit,
) {
    private val grid = root.findViewById<GridView>(R.id.tsGrid)
    private val title = root.findViewById<TextView>(R.id.tsTitle)
    private val adapter = Adapter()

    val isShown get() = root.visibility == View.VISIBLE

    init {
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> tabs.getOrNull(pos)?.let(onSelect) }
        root.findViewById<View>(R.id.tsBack).setOnClickListener { hide() }
        root.findViewById<View>(R.id.tsNew).setOnClickListener { onNew() }
        root.findViewById<View>(R.id.tsCloseAll).setOnClickListener { onCloseAll() }
    }

    fun show() {
        refresh()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.scaleX = 1.04f
        root.scaleY = 1.04f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        grid.setSelection(tabs.indexOf(current()).coerceAtLeast(0))
    }

    fun hide() {
        if (!isShown) return
        root.animate().alpha(0f).setDuration(120).withEndAction { root.visibility = View.GONE }.start()
    }

    fun refresh() {
        title.text = act.resources.getQuantityString(R.plurals.tabs_count, tabs.size, tabs.size)
        adapter.notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = tabs.size
        override fun getItem(position: Int) = tabs[position]
        override fun getItemId(position: Int) = System.identityHashCode(tabs[position]).toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: act.layoutInflater.inflate(R.layout.item_tab, parent, false).apply { clipToOutline = true }
            val tab = tabs[position]
            v.setBackgroundResource(if (tab === current()) R.drawable.bg_card_selected else R.drawable.bg_card)
            v.findViewById<TextView>(R.id.tabTitle).text = tab.displayTitle()
            val icon = v.findViewById<ImageView>(R.id.tabIcon)
            when {
                tab.isNtp -> icon.setImageResource(R.drawable.ic_launcher)
                tab.favicon != null -> icon.setImageBitmap(tab.favicon)
                else -> icon.setImageDrawable(LetterIcon(tab.url))
            }
            v.findViewById<ImageView>(R.id.tabThumb).setImageBitmap(tab.thumbnail)
            v.findViewById<View>(R.id.tabClose).setOnClickListener { onClose(tab) }
            return v
        }
    }
}

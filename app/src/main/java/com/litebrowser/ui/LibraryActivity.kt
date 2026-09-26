package com.litebrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.litebrowser.R
import com.litebrowser.data.BrowserDb
import com.litebrowser.data.Entry

/** History or bookmarks list with search. */
class LibraryActivity : Activity() {
    private var bookmarks = false
    private var items: List<Entry> = emptyList()
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookmarks = intent.getBooleanExtra(EXTRA_BOOKMARKS, false)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.c_bg)) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.c_toolbar))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(iconButton(this, R.drawable.ic_arrow_back, getString(R.string.back)) { finish() })
        search = EditText(this).apply {
            hint = if (bookmarks) "Поиск в закладках" else "Поиск в истории"
            setBackgroundResource(R.drawable.bg_omnibox)
            setPadding(dp(16), 0, dp(16), 0)
            textSize = 15f
            isSingleLine = true
            setTextColor(color(R.color.c_text))
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        bar.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        if (!bookmarks) bar.addView(iconButton(this, R.drawable.ic_delete, "Очистить историю") { confirmClear() })
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, dp(56))

        val frame = android.widget.FrameLayout(this)
        val list = ListView(this).apply { divider = null; adapter = this@LibraryActivity.adapter }
        empty = TextView(this).apply {
            text = if (bookmarks) "Закладок пока нет.\nНажмите ☆ в меню, чтобы добавить." else "История пуста"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(color(R.color.c_text2))
        }
        frame.addView(list)
        frame.addView(empty)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ -> open(items[pos].url, false) }
        list.setOnItemLongClickListener { _, _, pos, _ -> options(items[pos]); true }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = load()
        })
        load()
    }

    private fun load() {
        val q = search.text.toString().trim()
        items = if (bookmarks) BrowserDb.bookmarks(q) else BrowserDb.history(q)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(url: String, newTab: Boolean) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, url).putExtra(EXTRA_NEW_TAB, newTab))
        finish()
    }

    private fun options(e: Entry) {
        AlertDialog.Builder(this).setTitle(e.title.ifBlank { e.url })
            .setItems(arrayOf("Открыть в новой вкладке", "Копировать ссылку", "Удалить")) { _, i ->
                when (i) {
                    0 -> open(e.url, true)
                    1 -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", e.url))
                    2 -> {
                        if (bookmarks) BrowserDb.removeBookmark(e.url) else BrowserDb.deleteHistory(e.url)
                        items = items - e
                        adapter.notifyDataSetChanged()
                    }
                }
            }.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Очистить всю историю?")
            .setPositiveButton("Очистить") { _, _ ->
                BrowserDb.clearHistory()
                items = emptyList()
                adapter.notifyDataSetChanged()
                empty.visibility = View.VISIBLE
            }
            .setNegativeButton("Отмена", null).show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = themeDrawable(android.R.attr.selectableItemBackground)
                addView(ImageView(context), LinearLayout.LayoutParams(dp(36), dp(36)))
                val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
                texts.addView(TextView(context).apply {
                    textSize = 15f; setTextColor(color(R.color.c_text)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                texts.addView(TextView(context).apply {
                    textSize = 12f; setTextColor(color(R.color.c_text2)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val e = items[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(LetterIcon(e.url, e.title))
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = e.title.ifBlank { hostOf(e.url) }
            val time = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            (texts.getChildAt(1) as TextView).text = "${hostOf(e.url)} · $time"
            return row
        }
    }

    companion object {
        const val EXTRA_BOOKMARKS = "bookmarks"
        const val EXTRA_URL = "url"
        const val EXTRA_NEW_TAB = "new_tab"
    }
}

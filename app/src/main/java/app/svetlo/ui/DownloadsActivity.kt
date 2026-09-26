package app.svetlo.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.svetlo.DownloadEntry
import app.svetlo.DownloadRegistry
import app.svetlo.DownloadStatus
import app.svetlo.Downloads
import app.svetlo.R

/** In-app list of downloads with live progress. */
class DownloadsActivity : Activity() {
    private var items: List<DownloadEntry> = emptyList()
    private lateinit var empty: View
    private lateinit var list: ListView
    private val adapter = Adapter()
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private val onChange: () -> Unit = { load() }
    private val tick = object : Runnable {
        override fun run() {
            if (Downloads.hasActiveSystem()) {
                Downloads.refreshSystemAsync(this@DownloadsActivity)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadRegistry.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(R.color.c_bg)) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.c_toolbar))
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(iconButton(this, R.drawable.ic_arrow_back, getString(R.string.back)) { finish() })
        bar.addView(TextView(this).apply {
            text = getString(R.string.downloads)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.c_text))
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(this, R.drawable.ic_more_vert, getString(R.string.menu)) { toolbarMenu(it) })
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, dp(56))

        val frame = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            adapter = this@DownloadsActivity.adapter
            setPadding(0, dp(4), 0, dp(16))
            clipToPadding = false
        }
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(56))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_download)
                imageTintList = ColorStateList.valueOf(color(R.color.c_text2))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.c_surface2)) }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(22), dp(22), dp(22), dp(22))
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(TextView(context).apply {
                text = getString(R.string.downloads_empty)
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(color(R.color.c_text2))
                setPadding(0, dp(16), 0, 0)
            })
        }
        frame.addView(list)
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ ->
            val e = items[pos]
            if (e.status == DownloadStatus.DONE) Downloads.open(this, e) else options(e)
        }
        list.setOnItemLongClickListener { _, _, pos, _ -> options(items[pos]); true }
        load()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        DownloadRegistry.addListener(onChange)
        // Picks up downloads finished or deleted while the screen was hidden.
        Downloads.refreshSystemAsync(this)
        load()
        startTicker()
    }

    override fun onPause() {
        resumed = false
        DownloadRegistry.removeListener(onChange)
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun startTicker() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    private fun load() {
        val wasTicking = items.any { it.active && it.id.startsWith("dm-") }
        items = DownloadRegistry.list()
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (resumed && !wasTicking && items.any { it.active && it.id.startsWith("dm-") }) startTicker()
    }

    private fun toolbarMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Очистить список").isEnabled = items.any { !it.active }
        m.menu.add(0, 2, 0, "Системные загрузки")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> confirmClear()
                2 -> runCatching {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast("Системные загрузки недоступны") }
            }
            true
        }
        m.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Очистить список загрузок?")
            .setMessage("Завершённые загрузки исчезнут из списка. Файлы останутся на устройстве.")
            .setPositiveButton("Очистить") { _, _ -> DownloadRegistry.clearFinished() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun options(e: DownloadEntry) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (e.active) acts += "Отменить загрузку" to { Downloads.cancel(this, e) }
        if (e.status == DownloadStatus.DONE) {
            acts += "Открыть" to { Downloads.open(this, e) }
            acts += getString(R.string.share) to { Downloads.share(this, e) }
        }
        if (e.source.startsWith("http", ignoreCase = true)) acts += "Копировать ссылку" to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", e.source))
            toast("Ссылка скопирована")
        }
        if (!e.active) acts += "Удалить из списка" to { Downloads.delete(this, e, deleteFile = false) }
        if (e.status == DownloadStatus.DONE || e.contentUri != null) acts += "Удалить файл" to { confirmDeleteFile(e) }
        AlertDialog.Builder(this).setTitle(e.name)
            .setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }
            .show()
    }

    private fun confirmDeleteFile(e: DownloadEntry) {
        AlertDialog.Builder(this).setTitle("Удалить файл?").setMessage(e.name)
            .setPositiveButton("Удалить") { _, _ -> Downloads.delete(this, e, deleteFile = true) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun iconFor(e: DownloadEntry) = when (Downloads.kind(e.name, e.mime)) {
        Downloads.Kind.VIDEO -> R.drawable.ic_file_video
        Downloads.Kind.AUDIO -> R.drawable.ic_file_audio
        Downloads.Kind.IMAGE -> R.drawable.ic_file_image
        Downloads.Kind.DOC -> R.drawable.ic_file_doc
        Downloads.Kind.OTHER -> R.drawable.ic_file_other
    }

    private fun progressDrawable(): LayerDrawable {
        fun pill(c: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(c) }
        return LayerDrawable(arrayOf(pill(color(R.color.c_divider)), ClipDrawable(pill(color(R.color.c_accent)), Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private class Holder(val icon: ImageView, val name: TextView, val status: TextView, val progress: ProgressBar, val action: ImageButton)

    private companion object {
        // Keeps "1.2 МБ/с" on one line when large fonts wrap the status.
        val UNIT_GAP = Regex(" (Б|КБ|МБ|ГБ|ТБ)")
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: createRow()
            val h = row.tag as Holder
            val e = items[position]
            val muted = e.status == DownloadStatus.FAILED || e.status == DownloadStatus.CANCELLED
            h.icon.setImageResource(iconFor(e))
            h.icon.imageTintList = ColorStateList.valueOf(color(if (muted) R.color.c_text2 else R.color.c_accent))
            (h.icon.background as GradientDrawable).setColor(color(if (muted) R.color.c_surface2 else R.color.c_accent_soft))
            h.name.text = e.name
            h.name.setTextColor(color(if (muted) R.color.c_text2 else R.color.c_text))
            h.status.text = Downloads.statusLine(e).replace(UNIT_GAP, "\u00A0$1")
            h.status.setTextColor(color(if (e.status == DownloadStatus.FAILED) R.color.c_error else R.color.c_text2))
            if (e.active) {
                h.progress.visibility = View.VISIBLE
                val pct = Downloads.progressPercent(e)
                h.progress.isIndeterminate = pct == null
                if (pct != null) h.progress.progress = pct
                h.action.setImageResource(R.drawable.ic_close)
                h.action.contentDescription = "Отменить загрузку"
                h.action.setOnClickListener { Downloads.cancel(this@DownloadsActivity, e) }
            } else {
                h.progress.visibility = View.GONE
                h.action.setImageResource(R.drawable.ic_more_vert)
                h.action.contentDescription = "Действия"
                h.action.setOnClickListener { options(e) }
            }
            return row
        }

        private fun createRow(): View {
            val ctx = this@DownloadsActivity
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(dp(16), dp(10), dp(6), dp(10))
                background = themeDrawable(android.R.attr.selectableItemBackground)
            }
            val icon = ImageView(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
            val name = TextView(ctx).apply {
                textSize = 15f; setTextColor(color(R.color.c_text)); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val status = TextView(ctx).apply {
                textSize = 13f; setTextColor(color(R.color.c_text2)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressDrawable = progressDrawable()
                indeterminateTintList = ColorStateList.valueOf(color(R.color.c_accent))
            }
            texts.addView(name)
            texts.addView(status)
            texts.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val action = iconButton(ctx, R.drawable.ic_more_vert, "Действия") {}.apply { isFocusable = false }
            row.addView(action, LinearLayout.LayoutParams(dp(44), dp(44)))
            row.tag = Holder(icon, name, status, progress, action)
            return row
        }
    }
}

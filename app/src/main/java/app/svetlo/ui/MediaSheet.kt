package app.svetlo.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.svetlo.MediaItem
import app.svetlo.MediaKind
import app.svetlo.R

/** Chrome-like media sheet with the two useful actions kept beside each detected stream. */
class MediaSheet(
    private val activity: Activity,
    private val items: List<MediaItem>,
    private val canRecord: Boolean,
    private val onPlay: (MediaItem) -> Unit,
    private val onDownload: (MediaItem) -> Unit,
    private val onRecord: () -> Unit,
) {
    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity, R.style.MediaSheetDialog)
        dialog.window?.setWindowAnimations(
            if (activity.motionDuration(220) == 0L) 0 else R.style.MediaSheetAnimation,
        )
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_media_sheet)
            setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(18))
        }

        root.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(2).toFloat()
                setColor(activity.color(R.color.c_text2))
                alpha = 110
            }
        }, LinearLayout.LayoutParams(activity.dp(32), activity.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = activity.dp(16)
        })

        val title = TextView(activity).apply {
            val hasAudio = items.any(::isAudio)
            val hasVideo = items.any { !isAudio(it) }
            text = when {
                hasAudio && hasVideo -> activity.getString(app.svetlo.R.string.label_7be96fa0ab)
                hasAudio -> activity.getString(app.svetlo.R.string.label_6868e4a6c3)
                hasVideo -> activity.getString(app.svetlo.R.string.label_34d788e58f)
                else -> activity.getString(app.svetlo.R.string.label_0a7bf2e89f)
            }
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.color(R.color.c_text))
        }
        root.addView(title)
        root.addView(TextView(activity).apply {
            text = when {
                items.isNotEmpty() -> "Найдено файлов и потоков: ${items.size}"
                canRecord -> activity.getString(app.svetlo.R.string.label_346e0e0fd0)
                else -> activity.getString(app.svetlo.R.string.label_d5874e5452)
            }
            textSize = 13f
            setTextColor(activity.color(R.color.c_text2))
            setPadding(0, activity.dp(4), 0, activity.dp(12))
        })

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (items.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = activity.getString(app.svetlo.R.string.label_334a2e153a)
                textSize = 14f
                setTextColor(activity.color(R.color.c_text2))
                setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
                setBackgroundResource(R.drawable.bg_card)
                setLineSpacing(activity.dp(3).toFloat(), 1f)
            })
        } else {
            items.asReversed().forEachIndexed { index, item ->
                list.addView(mediaCard(item,
                    onPlay = { dialog.dismiss(); onPlay(item) },
                    onDownload = { dialog.dismiss(); onDownload(item) },
                ))
                if (index != items.lastIndex) {
                    list.addView(View(activity), LinearLayout.LayoutParams(1, activity.dp(10)))
                }
            }
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (canRecord) {
            val recordButton = TextView(activity).apply {
                text = activity.getString(app.svetlo.R.string.label_dc69c54f56)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minHeight = activity.dp(48)
                setTextColor(activity.color(R.color.c_accent))
                background = activity.themeDrawable(android.R.attr.selectableItemBackground)
                contentDescription = activity.getString(app.svetlo.R.string.label_8ae37ecf9d)
                setOnClickListener { dialog.dismiss(); onRecord() }
            }
            root.addView(recordButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(52)).apply {
                topMargin = activity.dp(8)
            })
        }

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            val metrics = activity.resources.displayMetrics
            val screenHeight = metrics.heightPixels
            val estimatedContent = activity.dp(174 + minOf(items.size, MAX_HEIGHT_ITEMS) * 156 + (if (canRecord) 64 else 0))
            val maxHeight = (screenHeight * MAX_HEIGHT_FRACTION).toInt()
            val height = minOf(maxHeight, maxOf(activity.dp(260), estimatedContent))
            val width = minOf(metrics.widthPixels, activity.dp(MAX_WIDTH_DP))
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(width, height)
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.32f }
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.32f }
        }
    }

    private fun mediaCard(item: MediaItem, onPlay: () -> Unit, onDownload: () -> Unit): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            setBackgroundResource(R.drawable.bg_card)
        }

        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(activity).apply {
            setImageResource(if (isAudio(item)) R.drawable.ic_file_audio else R.drawable.ic_file_video)
            imageTintList = android.content.res.ColorStateList.valueOf(activity.color(R.color.c_accent))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activity.color(R.color.c_accent_soft))
            }
            setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
            contentDescription = null
        }
        heading.addView(icon, LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)))

        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), 0, 0, 0)
        }
        val pieces = item.label.split('\n', limit = 2)
        labels.addView(TextView(activity).apply {
            text = pieces.firstOrNull().orEmpty().ifBlank { activity.getString(app.svetlo.R.string.label_6dfcda8ff6) }
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.color(R.color.c_text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(TextView(activity).apply {
            text = pieces.getOrNull(1).orEmpty()
            textSize = 12f
            setTextColor(activity.color(R.color.c_text2))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, activity.dp(3), 0, 0)
        })
        heading.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(heading)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(actionButton(activity.getString(app.svetlo.R.string.label_9c5f607c24), primary = false, action = onPlay),
            LinearLayout.LayoutParams(0, activity.dp(44), 1f).apply { marginEnd = activity.dp(8) })
        actions.addView(actionButton(activity.getString(app.svetlo.R.string.label_fe8f79f29d), primary = true, action = onDownload),
            LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(12)
        })
        return card
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = if (primary) {
            activity.getDrawable(R.drawable.bg_fab)
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = activity.dp(22).toFloat()
                setColor(activity.color(R.color.c_surface2))
                setStroke(activity.dp(1), activity.color(R.color.c_divider))
            }
        }
        setTextColor(activity.color(if (primary) R.color.c_on_accent else R.color.c_text))
        isFocusable = true
        isClickable = true
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun isAudio(item: MediaItem): Boolean = item.kind == MediaKind.DIRECT &&
        item.fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private companion object {
        const val MAX_HEIGHT_ITEMS = 8
        const val MAX_HEIGHT_FRACTION = 0.86f
        const val MAX_WIDTH_DP = 720
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    }
}

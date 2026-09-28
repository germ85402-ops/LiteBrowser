package app.svetlo.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.svetlo.R

/** Bottom message with an optional action (e.g. "Отменить"), Material-snackbar style. */
class Snackbar(private val act: Activity, parent: FrameLayout) {
    private val text = TextView(act).apply {
        textSize = 14f
        setTextColor(act.color(R.color.c_snack_text))
        maxLines = 2
    }
    private val action = TextView(act).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(act.color(R.color.c_snack_action))
        setPadding(act.dp(16), act.dp(12), act.dp(8), act.dp(12))
        background = act.themeDrawable(android.R.attr.selectableItemBackground)
    }
    private val view = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_snack)
        elevation = act.dp(6).toFloat()
        minimumHeight = act.dp(48)
        setPadding(act.dp(16), 0, act.dp(4), 0)
        visibility = View.GONE
        addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(action)
    }
    private var onTimeout: (() -> Unit)? = null
    private val hideRunnable = Runnable { dismiss() }

    /** Receives the vertical offset floating buttons should move by to stay above the message. */
    var onShift: ((Float) -> Unit)? = null

    /** Extra bottom margin, e.g. the height of a bottom toolbar. */
    var bottomOffset = 0

    init {
        parent.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** [onTimeout] runs when the message goes away without its action being used. */
    fun show(msg: String, actionText: String? = null, onAction: (() -> Unit)? = null, onTimeout: (() -> Unit)? = null, duration: Long = 4000) {
        commit()
        view.removeCallbacks(hideRunnable)
        text.text = msg
        action.text = actionText
        action.visibility = if (actionText == null) View.GONE else View.VISIBLE
        action.setOnClickListener {
            this.onTimeout = null
            dismiss()
            onAction?.invoke()
        }
        this.onTimeout = onTimeout
        (view.layoutParams as FrameLayout.LayoutParams).apply {
            val m = act.dp(12)
            setMargins(m, m, m, m + bottomOffset)
        }
        view.requestLayout()
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = act.dp(32).toFloat()
        }
        val durationMs = act.motionDuration(200)
        if (durationMs == 0L) {
            view.alpha = 1f
            view.translationY = 0f
        } else view.animate().alpha(1f).translationY(0f).setDuration(durationMs).start()
        view.post { onShift?.invoke(-(view.height + act.dp(12)).toFloat()) }
        view.postDelayed(hideRunnable, duration)
    }

    fun dismiss() {
        view.removeCallbacks(hideRunnable)
        commit()
        if (view.visibility != View.VISIBLE) return
        onShift?.invoke(0f)
        val durationMs = act.motionDuration(160)
        if (durationMs == 0L) {
            view.alpha = 0f
            view.translationY = act.dp(32).toFloat()
            view.visibility = View.GONE
        } else view.animate().alpha(0f).translationY(act.dp(32).toFloat()).setDuration(durationMs)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    /** Finalizes a pending undoable action immediately. */
    fun commit() {
        val cb = onTimeout
        onTimeout = null
        cb?.invoke()
    }
}

package app.svetlo.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import app.svetlo.R
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Chrome-like overscroll refresh indicator driven by the page's touch stream. */
class PullRefresh(ctx: Context, container: FrameLayout, private val onRefresh: () -> Unit) {
    private val size = ctx.dp(40)
    private val trigger = ctx.dp(84).toFloat()
    private val maxPull = ctx.dp(140).toFloat()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val view = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_refresh_accent)
        scaleType = ImageView.ScaleType.CENTER
        setBackgroundResource(R.drawable.bg_pull)
        elevation = ctx.dp(4).toFloat()
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var startY = 0f
    private var tracking = false
    private var pulling = false
    private var dist = 0f
    private var spin: ObjectAnimator? = null
    private var startedAt = 0L

    var enabled = true
    var refreshing = false
        private set

    init {
        container.addView(view, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    fun onTouch(page: View, ev: MotionEvent) {
        if (!enabled || refreshing) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = !page.canScrollVertically(-1)
                pulling = false
                startY = ev.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancel()
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dy = ev.y - startY
                if (!pulling) {
                    if (dy > slop && !page.canScrollVertically(-1)) {
                        pulling = true
                        startY = ev.y
                    } else if (dy < -slop) {
                        tracking = false
                    }
                } else {
                    update(dy)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pulling) if (dist >= trigger) start() else retract()
                tracking = false
                pulling = false
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    private fun cancel() {
        if (pulling) retract()
        tracking = false
        pulling = false
    }

    private fun update(dy: Float) {
        // Exponential damping gives the rubber-band feel.
        dist = maxPull * (1 - exp(-max(0f, dy) / maxPull))
        val p = min(1f, dist / trigger)
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.translationY = dist - size
        view.alpha = 0.3f + 0.7f * p
        view.scaleX = 0.5f + 0.5f * p
        view.scaleY = view.scaleX
        view.rotation = dist * 2.2f
        view.imageAlpha = if (p >= 1f) 255 else 140
    }

    private fun start() {
        refreshing = true
        startedAt = SystemClock.uptimeMillis()
        view.imageAlpha = 255
        val moveDuration = ctx.motionDuration(150)
        if (moveDuration == 0L) view.translationY = trigger * 0.75f - size
        else view.animate().translationY(trigger * 0.75f - size).setDuration(moveDuration).start()
        if (ctx.motionDuration(750) > 0L) {
            spin = ObjectAnimator.ofFloat(view, View.ROTATION, view.rotation, view.rotation + 360f).apply {
                duration = ctx.motionDuration(750)
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        onRefresh()
        view.postDelayed({ finish() }, 15_000)
    }

    /** Called when the page finished loading; keeps the spinner visible for a moment at least. */
    fun finish() {
        if (!refreshing) return
        val wait = if (ctx.motionDuration(180) == 0L) 0 else 450 - (SystemClock.uptimeMillis() - startedAt)
        if (wait > 0) { view.postDelayed({ finish() }, wait); return }
        refreshing = false
        spin?.cancel()
        val durationMs = ctx.motionDuration(180)
        if (durationMs == 0L) {
            view.scaleX = 0f
            view.scaleY = 0f
            view.alpha = 0f
            view.visibility = View.GONE
        } else view.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(durationMs).withEndAction { view.visibility = View.GONE }.start()
    }

    private fun retract() {
        dist = 0f
        val durationMs = ctx.motionDuration(160)
        if (durationMs == 0L) {
            view.translationY = -size.toFloat()
            view.alpha = 0f
            view.visibility = View.GONE
        } else view.animate().translationY(-size.toFloat()).alpha(0f).setDuration(durationMs)
            .withEndAction { view.visibility = View.GONE }.start()
    }
}

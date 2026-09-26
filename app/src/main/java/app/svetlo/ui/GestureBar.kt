package app.svetlo.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Toolbar that turns drags into Chrome-style gestures: horizontal swipe switches tabs,
 * swipe toward the page opens the tab switcher. Taps still reach the children.
 */
class GestureBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    interface Listener {
        fun canSwipe(): Boolean
        fun onSwipeMove(dx: Float)
        fun onSwipeEnd(dx: Float, vx: Float)
        fun onSwipeToSwitcher()
    }

    var listener: Listener? = null
    var atBottom = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var mode = NONE
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return mode == HORIZONTAL || mode == VERTICAL
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (listener == null) return super.onTouchEvent(ev)
        track(ev)
        return true
    }

    private fun track(ev: MotionEvent) {
        val l = listener ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                mode = if (l.canSwipe()) NONE else REJECTED
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (mode == NONE) {
                    val toward = if (atBottom) -dy else dy
                    when {
                        abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f -> {
                            mode = HORIZONTAL
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        toward > slop * 2 && abs(dy) > abs(dx) * 1.5f -> mode = VERTICAL
                        abs(dy) > slop * 3 -> mode = REJECTED
                    }
                }
                if (mode == HORIZONTAL) l.onSwipeMove(dx)
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                val dx = ev.rawX - downX
                val toward = if (atBottom) downY - ev.rawY else ev.rawY - downY
                when (mode) {
                    HORIZONTAL -> l.onSwipeEnd(dx, tracker?.xVelocity ?: 0f)
                    VERTICAL -> if (toward > height * 0.6f) l.onSwipeToSwitcher()
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == HORIZONTAL) l.onSwipeEnd(0f, 0f)
                reset()
            }
        }
    }

    private fun reset() {
        mode = NONE
        tracker?.recycle()
        tracker = null
    }

    private companion object {
        const val NONE = 0
        const val HORIZONTAL = 1
        const val VERTICAL = 2
        const val REJECTED = -1
    }
}

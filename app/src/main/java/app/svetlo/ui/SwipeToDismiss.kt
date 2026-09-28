package app.svetlo.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** Horizontal swipe-away for tab cards; a tap without movement performs a click. */
class SwipeToDismiss(private val onDismiss: () -> Unit) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var tracker: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                swiping = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                v.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!swiping && abs(dx) > slop && abs(dx) > abs(dy)) {
                    swiping = true
                    v.isPressed = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (abs(dy) > slop) v.isPressed = false
                if (swiping) {
                    v.translationX = dx
                    v.alpha = 1f - min(1f, abs(dx) / v.width) * 0.8f
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val dx = ev.rawX - downX
                v.isPressed = false
                if (swiping) {
                    if (abs(dx) > v.width * 0.4f || abs(vx) > 1200 && sign(vx) == sign(dx)) {
                        v.animate().translationX(sign(dx) * v.width * 1.2f).alpha(0f).setDuration(v.context.motionDuration(160))
                            .withEndAction(onDismiss).start()
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(v.context.motionDuration(160)).start()
                    }
                } else if (abs(dx) < slop && abs(ev.rawY - downY) < slop) {
                    v.performClick()
                }
                recycle()
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (swiping) v.animate().translationX(0f).alpha(1f).setDuration(v.context.motionDuration(160)).start()
                recycle()
            }
        }
        return true
    }

    private fun recycle() {
        swiping = false
        tracker?.recycle()
        tracker = null
    }
}

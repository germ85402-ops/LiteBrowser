package app.svetlo

import android.animation.ValueAnimator
import android.os.Looper
import android.view.View
import app.svetlo.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Hiding/showing the toolbar on scroll must not move the page on screen at the moment the layout
 * changes (that was the visible "jerk"). Runs with -Pscreenshots=true (needs the Robolectric runtime).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserControlsRobolectricTest {
    @Before
    fun gate() {
        assumeTrue(System.getProperty("screenshots") == "true")
        // Robolectric runs animators instantly by default; real timing is needed to see the start state.
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
    }

    @Test fun topBar() = check(bottom = false)

    @Test fun bottomBar() = check(bottom = true)

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs a layout pass without advancing animations (Robolectric's idle() completes them). */
    private fun layout(v: View) {
        val d = v.rootView
        d.measure(View.MeasureSpec.makeMeasureSpec(d.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(d.height, View.MeasureSpec.EXACTLY))
        d.layout(d.left, d.top, d.right, d.bottom)
    }

    private fun screenTop(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[1]

    private fun check(bottom: Boolean) {
        Prefs.bottomBar = bottom
        Prefs.autoHideBar = true
        Prefs.onboarded = true
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        act.navigate("https://example.com/")
        idle(500)
        val page = act.findViewById<View>(R.id.webContainer)
        val bar = act.findViewById<View>(R.id.toolbar)
        val root = act.findViewById<View>(R.id.root)
        val h = bar.height
        val top0 = screenTop(page)
        val height0 = page.height
        val barTop0 = screenTop(bar)

        val hide = MainActivity::class.java.getDeclaredMethod("hideBars").apply { isAccessible = true }
        val show = MainActivity::class.java.getDeclaredMethod("showBars", Boolean::class.javaPrimitiveType).apply { isAccessible = true }

        hide.invoke(act)
        layout(root)
        assertEquals("page must not jump when hiding starts", top0, screenTop(page))
        assertEquals("toolbar must not jump when hiding starts", barTop0, screenTop(bar))

        idle(400)
        assertEquals("page grows by the toolbar height", height0 + h, page.height)
        if (bottom) {
            assertEquals(top0, screenTop(page))
            assertEquals(screenTop(root) + root.height, screenTop(bar))
        } else {
            assertEquals(top0 - h, screenTop(page))
            assertEquals(barTop0 - h, screenTop(bar))
        }

        show.invoke(act, true)
        layout(root)
        assertEquals("no resize at the start of showing", height0 + h, page.height)
        idle(400)
        assertEquals(top0, screenTop(page))
        assertEquals(height0, page.height)
        assertEquals(barTop0, screenTop(bar))
    }
}

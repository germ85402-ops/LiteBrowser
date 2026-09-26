package com.litebrowser

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import com.litebrowser.data.BrowserDb
import com.litebrowser.ui.AdblockActivity
import com.litebrowser.ui.SettingsActivity
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Renders key screens to PNG for visual review: ./gradlew testDebugUnitTest -Pscreenshots=true --tests '*ScreenshotTest*' */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots")

    @Before
    fun gate() {
        assumeTrue(System.getProperty("screenshots") == "true")
        out.mkdirs()
    }

    private fun idle() = repeat(3) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        Thread.sleep(50)
    }

    private fun render(v: View): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun save(name: String, bmp: Bitmap) = File(out, "$name.png").outputStream().use {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
    }

    private fun shot(name: String, act: Activity) = save(name, render(act.window.decorView))

    /** Draws the activity with a floating window (dialog / popup) composited on top. */
    private fun shotWithOverlay(name: String, act: Activity, overlay: View, x: Int, y: Int) {
        val base = render(act.window.decorView)
        val c = Canvas(base)
        c.drawColor(0x33000000)
        c.translate(x.toFloat(), y.toFloat())
        overlay.draw(c)
        save(name, base)
    }

    private fun seedHistory() {
        listOf(
            "https://m.youtube.com/" to "YouTube", "https://habr.com/ru/" to "Хабр", "https://ya.ru/" to "Яндекс",
            "https://github.com/" to "GitHub", "https://ru.wikipedia.org/wiki/Android" to "Android — Википедия",
            "https://www.kinopoisk.ru/" to "Кинопоиск",
        ).forEach { (u, t) -> repeat(3) { BrowserDb.addVisit(u, t) } }
        Thread.sleep(300)
    }

    private fun main() = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }

    @Test
    fun screens() {
        seedHistory()
        val act = main()
        shot("01_new_tab", act)

        // Omnibox with suggestions.
        val url = act.findViewById<EditText>(R.id.urlBar)
        url.requestFocus()
        url.setText("wiki")
        idle()
        shot("02_suggestions", act)
        url.clearFocus()
        idle()

        // Tab switcher with a few tabs.
        act.newTab(null)
        act.newTab(null)
        idle()
        act.findViewById<View>(R.id.btnTabs).performClick()
        idle()
        shot("03_tabs", act)

        // Closing a tab from the switcher shows the undo snackbar.
        act.closeTab(act.tabs.last())
        idle()
        shot("11_undo_snackbar", act)
        act.findViewById<View>(R.id.tsBack).performClick()
        idle()

        // Main menu popup.
        act.findViewById<View>(R.id.btnMenu).performClick()
        idle()
        val popup = shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).latestPopupWindow
        val pv = popup.contentView.rootView
        val w = pv.width.takeIf { it > 0 } ?: act.resources.displayMetrics.widthPixels
        shotWithOverlay("04_menu", act, pv, act.window.decorView.width - w - 18, 90)
        popup.dismiss()
        idle()
    }

    @Test
    fun bottomBar() {
        com.litebrowser.data.Prefs.init(org.robolectric.RuntimeEnvironment.getApplication())
        com.litebrowser.data.Prefs.bottomBar = true
        val act = main()
        act.navigate("https://example.com/")
        idle()
        act.current!!.blocked.set(5)
        act.current!!.media.offer("https://cdn.example.com/video/clip.mp4")
        act.refreshToolbar()
        idle()
        shot("10_bottom_bar", act)
        com.litebrowser.data.Prefs.bottomBar = false
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun darkNewTab() {
        seedHistory()
        shot("05_new_tab_dark", main())
    }

    @Test
    fun incognito() {
        val act = Robolectric.buildActivity(IncognitoActivity::class.java).setup().get().also { idle() }
        shot("12_incognito", act)
    }

    @Test
    fun settingsScreens() {
        shot("06_settings", Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() })
        shot("07_adblock", Robolectric.buildActivity(AdblockActivity::class.java).setup().get().also { idle() })
    }

    @Test
    fun siteDialog() {
        val act = main()
        act.navigate("https://example.com/")
        idle()
        act.current!!.blocked.set(17)
        act.refreshToolbar()
        idle()
        shot("08_page_toolbar", act)
        act.showSiteInfo()
        idle()
        val d: Dialog = ShadowDialog.getLatestDialog()
        val dv = d.window!!.decorView
        shotWithOverlay("09_site_info", act, dv, (act.window.decorView.width - dv.width) / 2, (act.window.decorView.height - dv.height) / 2)
    }
}

package app.svetlo

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import app.svetlo.data.BrowserDb
import app.svetlo.ui.AdblockActivity
import app.svetlo.ui.DownloadsActivity
import app.svetlo.ui.MediaSheet
import app.svetlo.ui.OnboardingActivity
import app.svetlo.ui.SettingsActivity
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
        app.svetlo.data.Prefs.init(org.robolectric.RuntimeEnvironment.getApplication())
        app.svetlo.data.Prefs.bottomBar = true
        val act = main()
        act.navigate("https://example.com/")
        idle()
        act.current!!.blocked.set(5)
        act.current!!.media.offer("https://cdn.example.com/video/clip.mp4")
        act.refreshToolbar()
        idle()
        shot("10_bottom_bar", act)
        app.svetlo.data.Prefs.bottomBar = false
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
    fun mediaSheet() {
        val act = main()
        act.navigate("https://example.com/video")
        idle()
        MediaSheet(
            activity = act,
            items = listOf(
                MediaItem("https://cdn.example.com/lecture.mp4", MediaKind.DIRECT),
                MediaItem("https://cdn.example.com/music.mp3", MediaKind.DIRECT),
            ),
            canRecord = true,
            onPlay = {},
            onDownload = {},
            onRecord = {},
        ).show()
        idle()
        val d: Dialog = ShadowDialog.getLatestDialog()
        val dv = d.window!!.decorView
        shotWithOverlay("18_media_sheet", act, dv, (act.window.decorView.width - dv.width) / 2, act.window.decorView.height - dv.height)
        d.dismiss()
    }

    private fun seedDownloads() {
        DownloadRegistry.init(org.robolectric.RuntimeEnvironment.getApplication())
        DownloadRegistry.list().forEach { DownloadRegistry.remove(it.id) }
        val now = System.currentTimeMillis()
        val mb = 1048576L
        listOf(
            DownloadEntry("svc-1", "Как устроен Android — лекция 3.mp4", "https://cdn.example.com/lecture3.m3u8", DownloadStatus.RUNNING,
                now, mime = "video/mp4", bytes = (12.3 * mb).toLong(), total = 27 * mb, speed = 1.2 * mb, jobId = 1),
            DownloadEntry("app-2", "Отпуск_2026_финал.mp4", "https://example.com/v.mp4", DownloadStatus.DONE,
                now - 3_600_000, mime = "video/mp4", bytes = 27 * mb, total = 27 * mb, contentUri = "content://media/1"),
            DownloadEntry("app-3", "report-q3.pdf", "https://example.com/report-q3.pdf", DownloadStatus.FAILED,
                now - 7_200_000, mime = "application/pdf", message = "обрыв соединения"),
            DownloadEntry("app-4", "IMG_20260925_142000.jpg", "https://example.com/p.jpg", DownloadStatus.DONE,
                now - 86_400_000, mime = "image/jpeg", bytes = (2.4 * mb).toLong(), total = (2.4 * mb).toLong(), contentUri = "content://media/2"),
            DownloadEntry("app-5", "podcast-episode-42.mp3", "https://example.com/42.mp3", DownloadStatus.CANCELLED,
                now - 3 * 86_400_000L, mime = "audio/mpeg"),
        ).forEach { DownloadRegistry.put(it) }
    }

    @Test
    fun downloads() {
        seedDownloads()
        shot("15_downloads", Robolectric.buildActivity(DownloadsActivity::class.java).setup().get().also { idle() })
        DownloadRegistry.list().forEach { DownloadRegistry.remove(it.id) }
        shot("15b_downloads_empty", Robolectric.buildActivity(DownloadsActivity::class.java).setup().get().also { idle() })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun downloadsDark() {
        seedDownloads()
        shot("16_downloads_dark", Robolectric.buildActivity(DownloadsActivity::class.java).setup().get().also { idle() })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.5f)
    fun downloadsLargeFont() {
        seedDownloads()
        shot("17_downloads_large_font", Robolectric.buildActivity(DownloadsActivity::class.java).setup().get().also { idle() })
    }

    @Test
    fun onboarding() {
        val act = Robolectric.buildActivity(OnboardingActivity::class.java).setup().get().also { idle() }
        shot("13_onboarding", act)
        fun find(v: View): android.widget.ScrollView? = v as? android.widget.ScrollView
            ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        find(act.window.decorView)!!.fullScroll(View.FOCUS_DOWN)
        idle()
        shot("13b_onboarding_bottom", act)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun onboardingDark() {
        shot("14_onboarding_dark", Robolectric.buildActivity(OnboardingActivity::class.java).setup().get().also { idle() })
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
    @Test
    @Config(qualifiers = "w800dp-h1100dp-xhdpi")
    fun tabletTabSearch() {
        val activity = main()
        activity.newTab("https://example.com/alpha", focus = false).title = "Alpha"
        activity.newTab("https://example.com/beta", focus = false).title = "Beta"
        activity.findViewById<View>(R.id.btnTabs).performClick()
        idle()
        val search = activity.findViewById<EditText>(R.id.tsSearch)
        search.setText("Alpha")
        idle()
        val grid = activity.findViewById<android.widget.GridView>(R.id.tsGrid)
        org.junit.Assert.assertEquals(1, grid.adapter.count)
        org.junit.Assert.assertTrue(grid.numColumns >= 3)
        shot("tablet-tab-search", activity)
        activity.finish()
    }

    private fun descendants(v: View): List<View> = listOf(v) +
        ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { descendants(g.getChildAt(it)) } } ?: emptyList())

    @Test
    fun libraryDeleteLastBookmark() {
        BrowserDb.bookmarks().forEach { BrowserDb.removeBookmark(it.url) }
        BrowserDb.addBookmark("https://example.com/saved", "Сохранённая страница")
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = android.content.Intent(context, app.svetlo.ui.LibraryActivity::class.java)
            .putExtra(app.svetlo.ui.LibraryActivity.EXTRA_BOOKMARKS, true)
        val act = Robolectric.buildActivity(app.svetlo.ui.LibraryActivity::class.java, intent).setup().get(); idle()
        shot("27_bookmarks", act)
        descendants(act.window.decorView).first { it.contentDescription?.toString() == act.getString(R.string.entry_actions, "Сохранённая страница") }.performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        dialog.listView.performItemClick(dialog.listView.getChildAt(2), 2, 2)
        idle()
        org.junit.Assert.assertTrue(BrowserDb.bookmarks().isEmpty())
        val empty = descendants(act.window.decorView).filterIsInstance<android.widget.TextView>()
            .first { it.text.toString() == act.getString(R.string.bookmark_empty_help) }
        org.junit.Assert.assertEquals(View.VISIBLE, empty.visibility)
        shot("28_bookmarks_empty", act)
        act.finish()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 1.3f)
    fun narrowPhoneSearchAndMenu() {
        val act = main()
        val url = act.findViewById<EditText>(R.id.urlBar)
        url.requestFocus(); url.setText("example.com"); idle()
        org.junit.Assert.assertTrue(url.width >= act.resources.displayMetrics.density * 100)
        shot("19_small_phone_search", act)
        act.findViewById<View>(R.id.btnSearchBack).performClick(); idle()
        org.junit.Assert.assertFalse(url.hasFocus())
        act.findViewById<View>(R.id.btnMenu).performClick(); idle()
        val popup = shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).latestPopupWindow
        org.junit.Assert.assertEquals(View.GONE, popup.contentView.findViewById<View>(R.id.mFind).visibility)
        org.junit.Assert.assertTrue(popup.width <= act.window.decorView.width)
        org.junit.Assert.assertTrue(popup.height < act.window.decorView.height)
        val pv = popup.contentView.rootView
        shotWithOverlay("20_small_phone_menu", act, pv, act.window.decorView.width - pv.width - act.resources.displayMetrics.density.toInt() * 6, 20)
        popup.dismiss(); act.finish()
    }

    @Test
    fun tabSearchEmptyAndReset() {
        val act = main()
        act.findViewById<View>(R.id.btnTabs).performClick(); idle()
        val search = act.findViewById<EditText>(R.id.tsSearch)
        org.junit.Assert.assertFalse("Opening tabs should not focus the keyboard", search.hasFocus())
        search.setText("not-a-tab-94852"); idle()
        org.junit.Assert.assertEquals(View.VISIBLE, act.findViewById<View>(R.id.tsEmpty).visibility)
        shot("21_tabs_no_results", act)
        act.findViewById<View>(R.id.tsClear).performClick(); idle()
        org.junit.Assert.assertEquals(View.GONE, act.findViewById<View>(R.id.tsEmpty).visibility)
        search.setText("not-a-tab-94852"); idle()
        act.findViewById<View>(R.id.tsBack).performClick(); idle()
        act.findViewById<View>(R.id.btnTabs).performClick(); idle()
        org.junit.Assert.assertEquals("", search.text.toString())
        org.junit.Assert.assertEquals(View.VISIBLE, act.findViewById<View>(R.id.tsGrid).visibility)
        act.finish()
    }

    @Test
    fun settingsSearchAndSwitchAccessibility() {
        val act = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
        val search = descendants(act.window.decorView).filterIsInstance<EditText>().single()
        search.setText("адресная"); idle()
        val switch = descendants(act.window.decorView).filterIsInstance<android.widget.Switch>()
            .first { ((it.parent as View).visibility == View.VISIBLE) }
        val row = switch.parent as View
        val node = row.createAccessibilityNodeInfo()
        org.junit.Assert.assertTrue(node.isCheckable)
        org.junit.Assert.assertEquals(switch.isChecked, node.isChecked)
        val previous = switch.isChecked
        row.performClick(); idle()
        org.junit.Assert.assertEquals(!previous, switch.isChecked)
        shot("22_settings_search", act)
        search.setText("not-a-setting-94852"); idle()
        val empty = descendants(act.window.decorView).filterIsInstance<android.widget.TextView>()
            .first { it.text.toString() == act.getString(R.string.settings_no_results) }
        org.junit.Assert.assertEquals(View.VISIBLE, empty.visibility)
        search.text.clear(); idle()
        org.junit.Assert.assertEquals(View.GONE, empty.visibility)
        app.svetlo.data.Prefs.bottomBar = previous
        act.finish()
    }

    @Test
    @Config(qualifiers = "w800dp-h1100dp-xhdpi")
    fun tabletHomeAndSettings() {
        shot("23_tablet_home", main())
        shot("24_tablet_settings", Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() })
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 1.5f)
    fun readerNarrowPhone() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val token = ArticleStore.write(context, Article("https://example.com/article", "Тестовая статья", null, null, "<p>Текст статьи для чтения.</p>", "ru"))
        val intent = android.content.Intent(context, app.svetlo.ui.ReaderActivity::class.java).putExtra("article_file", token)
        val act = Robolectric.buildActivity(app.svetlo.ui.ReaderActivity::class.java, intent).setup().get(); idle()
        val controls = descendants(act.window.decorView).filter { it.isClickable && it.visibility == View.VISIBLE }
        controls.forEach { view ->
            val location = IntArray(2); view.getLocationInWindow(location)
            org.junit.Assert.assertTrue("Reader controls must fit in a narrow window", location[0] >= 0 && location[0] + view.width <= act.window.decorView.width)
        }
        shot("25_reader_small_phone", act)
        act.finish()
    }

    @Test
    fun activeDownloadActions() {
        seedDownloads()
        val act = Robolectric.buildActivity(DownloadsActivity::class.java).setup().get(); idle()
        val button = descendants(act.window.decorView).first { it.contentDescription?.toString() == act.getString(R.string.entry_actions, "Как устроен Android — лекция 3.mp4") }
        button.performClick(); idle()
        org.junit.Assert.assertEquals(DownloadStatus.RUNNING, DownloadRegistry.get("svc-1")!!.status)
        val dialog = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        org.junit.Assert.assertEquals(act.getString(R.string.label_4205b1307e), dialog.listView.adapter.getItem(0))
        val dv = dialog.window!!.decorView
        shotWithOverlay("26_download_actions", act, dv, (act.window.decorView.width - dv.width) / 2, (act.window.decorView.height - dv.height) / 2)
        dialog.dismiss(); act.finish()
    }

}

package app.svetlo

import app.svetlo.data.BrowserDb
import app.svetlo.data.Prefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { Prefs.init(app); BrowserDb.init(app); DownloadRegistry.init(app) }
    @Test fun articleTransferPreservesContentAndMetadata() {
        val original = Article("https://example.com/a", "Title", "Author", "Example", "<p>Content</p>", "en")
        val token = ArticleStore.write(app, original)
        val read = ArticleStore.read(app, token)!!
        assertEquals(original.html, read.html)
        assertEquals(original.byline, read.byline)
        assertEquals(original.site, read.site)
        assertNull(ArticleStore.read(app, "../../files/secrets"))
        ArticleStore.remove(app, token)
        assertNull(ArticleStore.read(app, token))
    }
    @Test fun downloadUpdatesDoNotOverwriteEachOther() {
        val entry = DownloadEntry("concurrency-test", "video.mp4", "https://example.com", DownloadStatus.RUNNING, System.currentTimeMillis())
        DownloadRegistry.put(entry)
        val threads = (0 until 4).map { Thread { repeat(20) { DownloadRegistry.update(entry.id) { e -> e.copy(bytes = e.bytes + 1) } } }.apply { start() } }
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(80L, DownloadRegistry.get(entry.id)!!.bytes)
        DownloadRegistry.remove(entry.id)
    }
    @Test fun backupOmitsPermissionGrantsAndRejectsExecutableBookmarks() {
        Prefs.sp.edit().putString("cookie", "secret").putBoolean("site:https://example.com:443|permission:geo", true).commit()
        val backup = Backup.export()
        assertFalse(backup.contains("secret"))
        assertFalse(backup.contains("permission:geo"))
        val invalid = JSONObject(backup)
        invalid.getJSONArray("bookmarks").put(JSONObject().put("url", "javascript:alert(1)"))
        try { Backup.validate(invalid.toString()); fail("unsafe URL should be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun sitePermissionsAreScopedToExactOrigin() {
        SiteSettings.decision("https://example.com", "geo", true)
        assertEquals(true, SiteSettings.decision("https://example.com:443/path", "geo"))
        assertNull(SiteSettings.decision("https://example.com:8443", "geo"))
        assertNull(SiteSettings.decision("http://example.com", "geo"))
        SiteSettings.reset("https://example.com")
    }
    @Test fun pausedDownloadsAndJobIdsSurviveRegistryReads() {
        val e = DownloadEntry("pause-test", "video.mp4", "https://example.com", DownloadStatus.PAUSED, 1, jobId = 42)
        DownloadRegistry.put(e)
        assertEquals(DownloadStatus.PAUSED, DownloadRegistry.get(e.id)!!.status)
        assertEquals(42, DownloadRegistry.get(e.id)!!.jobId)
        DownloadRegistry.remove(e.id)
    }
    @Test fun stalePermissionRequestsCannotReceiveAccessAfterNavigation() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        val tab = Tab("https://permission-test.example/")
        var after: (() -> Unit)? = null
        var denied = false
        var granted = false
        val request = object : android.webkit.PermissionRequest() {
            override fun getOrigin() = android.net.Uri.parse(tab.url)
            override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<out String>) { granted = true }
            override fun deny() { denied = true }
        }
        SiteSettings.decision(tab.url, "media:true:false", true)
        val prompts = SitePermissions(activity, false, { true }, { _, callback -> after = callback }, {})
        prompts.onSitePermission(tab, request)
        assertNotNull(after)
        tab.generation++
        after!!.invoke()
        assertTrue(denied)
        assertFalse(granted)
        SiteSettings.reset(tab.url)
        activity.finish()
    }
    @Test fun sessionSnapshotKeepsCurrentUrlWithinBoundedHistory() {
        val tab = Tab("https://example.com/50", "Title")
        tab.sessionHistory = (0 until 200).map { "https://example.com/$it" }.toMutableList()
        tab.sessionIndex = 50
        val snapshot = JSONObject(TabSessions.snapshot(listOf(tab), tab))
        val saved = snapshot.getJSONArray("tabs").getJSONObject(0)
        assertEquals(tab.url, saved.getJSONArray("history").getString(saved.getInt("index")))
        assertTrue(saved.getJSONArray("history").length() <= 100)
    }

}

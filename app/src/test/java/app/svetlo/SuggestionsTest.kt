package app.svetlo

import android.app.Activity
import android.os.Looper
import android.widget.ListView
import app.svetlo.data.BrowserDb
import app.svetlo.data.Prefs
import app.svetlo.ui.Suggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuggestionsTest {
    @Before
    fun resetPrefs() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Prefs.init(app)
        Prefs.sp.edit().clear().commit()
        BrowserDb.init(app)
    }

    @Test
    fun privateOmniboxDoesNotSendPartialQueriesToSearchProvider() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val called = AtomicBoolean(false)
        val suggestions = Suggestions(activity, ListView(activity), {}, {}, remoteFetcher = { _, _ ->
            called.set(true)
            emptyList()
        })

        suggestions.query("private search text", allowRemote = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertFalse(called.get())
        suggestions.close()
        activity.finish()
    }

    @Test
    fun privateOmniboxDoesNotSuggestRegularBrowsingHistory() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val marker = "private-history-${System.nanoTime()}"
        BrowserDb.addVisit("https://example.com/$marker", marker)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (BrowserDb.history(marker, 1).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("test history entry should be written", BrowserDb.history(marker, 1).isNotEmpty())

        val list = ListView(activity)
        val suggestions = Suggestions(activity, list, {}, {})
        suggestions.query(marker, withHistory = false, allowRemote = false)

        // Only the search row remains; no local-history result is shown.
        assertEquals(1, list.adapter.count)
        suggestions.close()
        activity.finish()
    }

    @Test
    fun onlyTheLatestTypedQueryStartsARemoteRequest() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fetched = CountDownLatch(1)
        val suggestions = Suggestions(activity, ListView(activity), {}, {}, remoteFetcher = { url, _ ->
            requested += url
            fetched.countDown()
            listOf("result")
        })

        suggestions.query("first query")
        suggestions.query("latest query")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertTrue("the debounced request should run", fetched.await(2, TimeUnit.SECONDS))
        assertEquals(1, requested.size)
        assertTrue(requested.single().contains("latest%20query"))
        suggestions.close()
        activity.finish()
    }
}

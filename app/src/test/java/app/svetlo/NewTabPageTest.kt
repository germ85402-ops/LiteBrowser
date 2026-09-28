package app.svetlo

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import app.svetlo.data.Prefs
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NewTabPageTest {
    @Before
    fun clearPrefs() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Prefs.init(app)
        Prefs.sp.edit().clear().commit()
        Prefs.onboarded = true
    }

    @Test
    fun addShortcutValidatesAndSavesSite() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val grid = activity.findViewById<GridLayout>(R.id.ntpTiles)
        val add = (0 until grid.childCount).map(grid::getChildAt)
            .first { it.contentDescription == "Добавить сайт в быстрый доступ" }
        add.performClick()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val address = findEditText(dialog.window!!.decorView)
            ?: error("the add-site form should expose an address field")

        address.setText("cats and videos")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue("an invalid search phrase should keep the form open", dialog.isShowing)
        assertTrue("the address field should explain the error", address.error?.isNotBlank() == true)

        address.setText("example.com")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)

        val saved = JSONArray(Prefs.sp.getString("ntp_pinned", "[]"))
        assertEquals("https://example.com", saved.getString(0))
        assertTrue((0 until grid.childCount).map(grid::getChildAt).any { it.contentDescription == "Example" })
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findEditText(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

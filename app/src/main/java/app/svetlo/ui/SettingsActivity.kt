package app.svetlo.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import app.svetlo.BuildConfig
import app.svetlo.data.BrowserDb
import app.svetlo.data.Prefs
import app.svetlo.data.SearchEngine
import app.svetlo.filter.AdBlock
import java.io.File

open class SettingsActivity : Activity() {
    private lateinit var page: Page
    private var adblockRow: Page.Row? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = Page(this, getString(app.svetlo.R.string.label_7f17c7c62a))
        setResult(RESULT_OK)

        page.header(getString(app.svetlo.R.string.label_eee2285ba1))
        page.row(getString(app.svetlo.R.string.label_c464388cf3), Prefs.searchEngine.title) { row ->
            val engines = SearchEngine.entries
            AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_c464388cf3))
                .setSingleChoiceItems(engines.map { it.title }.toTypedArray(), engines.indexOf(Prefs.searchEngine)) { d, i ->
                    Prefs.searchEngine = engines[i]
                    Prefs.customSearch = ""
                    row.setSummary(engines[i].title)
                    d.dismiss()
                }.show()
        }
        page.row(getString(app.svetlo.R.string.custom_search), Prefs.customSearch.ifBlank { getString(app.svetlo.R.string.label_7e967753a7) }) { row ->
            val field = android.widget.EditText(this).apply { setText(Prefs.customSearch); hint = "https://example.com/search?q=%s"; isSingleLine = true }
            val dialog = AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_fde6f6b188)).setView(field).setPositiveButton(getString(app.svetlo.R.string.label_4864057d62), null).setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (value.isNotEmpty() && !Prefs.validCustomSearch(value)) field.error = getString(app.svetlo.R.string.label_07ab1b9acb)
                else { Prefs.customSearch = value; row.setSummary(value.ifBlank { getString(app.svetlo.R.string.label_c9467860d8) }); dialog.dismiss() }
            } }
            dialog.show()
        }
        page.switchRow(getString(app.svetlo.R.string.label_9ae3022506), getString(app.svetlo.R.string.label_c0a8a5cc3c), Prefs.suggestions) { Prefs.suggestions = it }
        page.switchRow(getString(app.svetlo.R.string.label_a895c38d59), getString(app.svetlo.R.string.label_891cbb5363), Prefs.bottomBar) { Prefs.bottomBar = it }
        page.switchRow(getString(app.svetlo.R.string.label_e4dd681cb3), getString(app.svetlo.R.string.label_d1caf6841a), Prefs.autoHideBar) { Prefs.autoHideBar = it }
        page.switchRow(getString(app.svetlo.R.string.label_60cadd7396), getString(app.svetlo.R.string.label_252b8b6ea8), Prefs.pullToRefresh) { Prefs.pullToRefresh = it }

        page.header(getString(app.svetlo.R.string.label_9827140f7c))
        page.row(getString(app.svetlo.R.string.label_eb8d47c8ed), getString(app.svetlo.R.string.label_eb51083cdc))
        page.row(getString(app.svetlo.R.string.label_4d9fd9a9b3), getString(app.svetlo.R.string.label_fe14840cb4))
        page.row(getString(app.svetlo.R.string.label_f130957cec), getString(app.svetlo.R.string.label_d19457bde2))
        page.row(getString(app.svetlo.R.string.label_c8646543a0), getString(app.svetlo.R.string.label_cbe1969c24))

        page.header(getString(app.svetlo.R.string.label_7f2be1ba3d))
        adblockRow = page.row(getString(app.svetlo.R.string.label_356dbd9a03), null) {
            startActivity(Intent(this, if (Prefs.isPrivate) IncognitoAdblockActivity::class.java else AdblockActivity::class.java))
        }

        page.header(getString(app.svetlo.R.string.label_aeb17a3c87))
        if (Build.VERSION.SDK_INT >= 29) {
            page.switchRow(getString(app.svetlo.R.string.label_5c036faae2), getString(app.svetlo.R.string.label_272eec4fdb), Prefs.darkPages) { Prefs.darkPages = it }
        }
        page.row(getString(app.svetlo.R.string.label_b1e23e1ff0), "${Prefs.textZoom}%") { row ->
            val values = listOf(80, 90, 100, 110, 125, 150, 175)
            AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_b1e23e1ff0))
                .setSingleChoiceItems(values.map { "$it%" }.toTypedArray(), values.indexOf(Prefs.textZoom)) { d, i ->
                    Prefs.textZoom = values[i]
                    row.setSummary("${values[i]}%")
                    d.dismiss()
                }.show()
        }
        page.switchRow("JavaScript", getString(app.svetlo.R.string.label_c79e6a9176), Prefs.javascript) { Prefs.javascript = it }

        page.header(getString(app.svetlo.R.string.label_71c599386d))
        page.switchRow(getString(app.svetlo.R.string.label_87079b51d2), getString(app.svetlo.R.string.label_e53ac4dcf6), Prefs.blockThirdPartyCookies) {
            Prefs.blockThirdPartyCookies = it
        }
        page.row(getString(app.svetlo.R.string.label_8ead3cd747)) {
            confirm(getString(app.svetlo.R.string.label_72c81bea7a)) { BrowserDb.clearHistory(); toast(getString(app.svetlo.R.string.label_051ea779d1)) }
        }
        page.row(getString(app.svetlo.R.string.label_19c4595aba), getString(app.svetlo.R.string.label_a29296ac2c)) {
            confirm(getString(app.svetlo.R.string.label_5acaa694fe)) {
                WebView(this).apply { clearCache(true); destroy() }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                toast(getString(app.svetlo.R.string.label_206a34de6a))
            }
        }

        page.header(getString(app.svetlo.R.string.label_cd5ce9bc86))
        page.row(getString(app.svetlo.R.string.reading_list)) { startActivity(Intent(this, ReadingListActivity::class.java)) }
        page.row(getString(app.svetlo.R.string.backup_export)) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "Svetlo-backup.json"), 40)
        }
        page.row(getString(app.svetlo.R.string.backup_import)) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 41)
        }
        page.header(getString(app.svetlo.R.string.label_b9c9ff652d))
        page.row("Svetlo ${BuildConfig.VERSION_NAME}", "Размер установки: ${apkSizeKb()} КБ")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 40) {
            Thread {
                val result = runCatching { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(app.svetlo.Backup.export()) } }
                runOnUiThread { toast(if (result.isSuccess) getString(app.svetlo.R.string.label_a2569c1e10) else getString(app.svetlo.R.string.label_b1980f4b88)) }
            }.start()
        } else if (requestCode == 41) {
            Thread {
                val result = runCatching { contentResolver.openInputStream(uri)!!.use { input ->
                    app.svetlo.Backup.validate(app.svetlo.Backup.read(input))
                } }
                runOnUiThread {
                    val text = result.getOrNull()
                    if (text == null) toast("Не удалось прочитать копию: ${result.exceptionOrNull()?.message}")
                    else confirm(getString(app.svetlo.R.string.label_ccfc74ba96)) {
                        Thread { val imported = runCatching { app.svetlo.Backup.restore(text) }; runOnUiThread { toast(if (imported.isSuccess) getString(app.svetlo.R.string.label_b0345f858b) else getString(app.svetlo.R.string.label_c7894c072c)); if (imported.isSuccess) recreate() } }.start()
                    }
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        adblockRow?.setSummary(
            if (Prefs.adblock) "Включено · ${"%,d".format(AdBlock.ruleCount)} правил" else "Выключено",
        )
    }

    private fun apkSizeKb() = File(applicationInfo.sourceDir).length() / 1024

    private fun confirm(title: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setPositiveButton(getString(app.svetlo.R.string.label_8d2fab2d12)) { _, _ -> action() }
            .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}

class IncognitoSettingsActivity : SettingsActivity()

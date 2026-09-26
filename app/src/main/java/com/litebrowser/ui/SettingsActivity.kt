package com.litebrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import com.litebrowser.BuildConfig
import com.litebrowser.data.BrowserDb
import com.litebrowser.data.Prefs
import com.litebrowser.data.SearchEngine
import com.litebrowser.filter.AdBlock
import java.io.File

class SettingsActivity : Activity() {
    private lateinit var page: Page
    private var adblockRow: Page.Row? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = Page(this, "Настройки")
        setResult(RESULT_OK)

        page.header("Основные")
        page.row("Поисковая система", Prefs.searchEngine.title) { row ->
            val engines = SearchEngine.entries
            AlertDialog.Builder(this).setTitle("Поисковая система")
                .setSingleChoiceItems(engines.map { it.title }.toTypedArray(), engines.indexOf(Prefs.searchEngine)) { d, i ->
                    Prefs.searchEngine = engines[i]
                    row.setSummary(engines[i].title)
                    d.dismiss()
                }.show()
        }
        page.switchRow("Поисковые подсказки", "Предлагать запросы при вводе", Prefs.suggestions) { Prefs.suggestions = it }
        page.switchRow("Адресная строка внизу", "Удобнее для работы одной рукой", Prefs.bottomBar) { Prefs.bottomBar = it }

        page.header("Блокировка рекламы")
        adblockRow = page.row("Блокировка рекламы и трекеров", null) {
            startActivity(Intent(this, AdblockActivity::class.java))
        }

        page.header("Страницы")
        if (Build.VERSION.SDK_INT >= 29) {
            page.switchRow("Тёмная тема для сайтов", "Затемнять сайты в тёмном режиме системы", Prefs.darkPages) { Prefs.darkPages = it }
        }
        page.row("Размер текста", "${Prefs.textZoom}%") { row ->
            val values = listOf(80, 90, 100, 110, 125, 150, 175)
            AlertDialog.Builder(this).setTitle("Размер текста")
                .setSingleChoiceItems(values.map { "$it%" }.toTypedArray(), values.indexOf(Prefs.textZoom)) { d, i ->
                    Prefs.textZoom = values[i]
                    row.setSummary("${values[i]}%")
                    d.dismiss()
                }.show()
        }
        page.switchRow("JavaScript", "Отключение ускоряет страницы, но ломает многие сайты", Prefs.javascript) { Prefs.javascript = it }

        page.header("Конфиденциальность")
        page.switchRow("Блокировать сторонние cookie", "Мешает отслеживанию между сайтами", Prefs.blockThirdPartyCookies) {
            Prefs.blockThirdPartyCookies = it
        }
        page.row("Очистить историю") {
            confirm("Очистить историю посещений?") { BrowserDb.clearHistory(); toast("История очищена") }
        }
        page.row("Очистить кэш и cookie", "Выйдет из аккаунтов на сайтах") {
            confirm("Удалить кэш, cookie и данные сайтов?") {
                WebView(this).apply { clearCache(true); destroy() }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                toast("Данные сайтов удалены")
            }
        }

        page.header("О приложении")
        page.row("LiteBrowser ${BuildConfig.VERSION_NAME}", "Размер установки: ${apkSizeKb()} КБ")
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
            .setPositiveButton("Да") { _, _ -> action() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}

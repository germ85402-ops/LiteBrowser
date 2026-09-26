package com.litebrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.litebrowser.R
import com.litebrowser.data.Prefs
import com.litebrowser.filter.AdBlock
import com.litebrowser.filter.FilterLists

/** Ad blocking settings: global switch, filter lists, site exceptions and custom rules. */
class AdblockActivity : Activity() {
    private lateinit var page: Page

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        build()
    }

    private fun build() {
        page = Page(this, getString(R.string.adblock))
        page.actions.addView(iconButton(this, R.drawable.ic_refresh, "Обновить списки") { updateNow() })

        page.switchRow(
            "Блокировать рекламу",
            "Всего заблокировано: ${"%,d".format(AdBlock.totalBlocked.get())} · правил: ${"%,d".format(AdBlock.ruleCount)}",
            Prefs.adblock,
        ) { Prefs.adblock = it }

        page.header("Списки фильтров")
        FilterLists.all().forEach { sub ->
            val row = page.switchRow(sub.title, summary(sub), FilterLists.isEnabled(sub)) { on ->
                FilterLists.setEnabled(sub, on)
                if (on && !FilterLists.isDownloaded(this, sub)) updateNow(force = false) else AdBlock.rebuild { build() }
            }
            if (sub.custom) row.view.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("Удалить список?").setMessage(sub.url)
                    .setPositiveButton("Удалить") { _, _ -> FilterLists.removeCustom(this, sub); AdBlock.rebuild { build() } }
                    .setNegativeButton("Отмена", null).show()
                true
            }
        }
        page.row("Добавить список по ссылке", "Любой список в формате AdBlock Plus / uBlock / hosts") { addList() }
        page.row("Свои правила", "Например: ||ads.example.com^ или example.com##.banner") { editRules() }

        page.header("Сайты-исключения")
        val wl = AdBlock.whitelist()
        if (wl.isEmpty()) page.row("Нет исключений", "Отключить блокировку для сайта можно через значок щита в адресной строке")
        wl.forEach { host ->
            page.row(host, "Нажмите, чтобы снова блокировать рекламу") {
                AdBlock.setWhitelisted(host, false)
                build()
            }
        }
    }

    private fun summary(sub: FilterLists.Sub): String {
        val upd = FilterLists.updatedAt(sub)
        val rules = FilterLists.ruleCount(sub)
        return when {
            AdBlock.updating && !FilterLists.isDownloaded(this, sub) -> "Загрузка…"
            !FilterLists.isDownloaded(this, sub) -> "Не загружен"
            upd == 0L -> "Встроенный · ${"%,d".format(rules)} правил"
            rules == 0 -> if (AdBlock.updating) "Обработка…" else "Загружен, включится после обновления"
            else -> "${"%,d".format(rules)} правил · обновлён " +
                DateUtils.getRelativeTimeSpanString(upd, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }
    }

    private fun updateNow(force: Boolean = true) {
        Toast.makeText(this, "Обновление списков…", Toast.LENGTH_SHORT).show()
        AdBlock.update(force) { failed ->
            if (isFinishing || isDestroyed) return@update
            Toast.makeText(this, if (failed == 0) "Списки обновлены" else "Не удалось загрузить списков: $failed", Toast.LENGTH_LONG).show()
            build()
        }
    }

    private fun input(hint: String, value: String = "", multiline: Boolean = false): Pair<FrameLayout, EditText> {
        val e = EditText(this).apply {
            this.hint = hint
            setText(value)
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 6
                gravity = Gravity.TOP
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            } else {
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                isSingleLine = true
            }
        }
        val frame = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        return frame to e
    }

    private fun addList() {
        val (frame, edit) = input("https://example.com/filters.txt")
        AlertDialog.Builder(this).setTitle("Добавить список").setView(frame)
            .setPositiveButton("Добавить") { _, _ ->
                val url = edit.text.toString().trim()
                if (!url.startsWith("http")) return@setPositiveButton
                FilterLists.addCustom(url)
                updateNow(force = false)
                build()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun editRules() {
        val file = FilterLists.userRulesFile(this)
        val (frame, edit) = input("По одному правилу в строке", if (file.exists()) file.readText() else "", multiline = true)
        AlertDialog.Builder(this).setTitle("Свои правила").setView(frame)
            .setPositiveButton("Сохранить") { _, _ ->
                file.writeText(edit.text.toString())
                AdBlock.rebuild { build() }
            }
            .setNegativeButton("Отмена", null).show()
    }
}

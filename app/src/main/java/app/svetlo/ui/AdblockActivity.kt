package app.svetlo.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import app.svetlo.R
import app.svetlo.data.Prefs
import app.svetlo.filter.AdBlock
import app.svetlo.filter.FilterLists

/** Ad blocking settings: global switch, filter lists, site exceptions and custom rules. */
open class AdblockActivity : Activity() {
    private lateinit var page: Page

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        build()
    }

    private fun build() {
        page = Page(this, getString(R.string.adblock))
        page.actions.addView(iconButton(this, R.drawable.ic_refresh, getString(app.svetlo.R.string.label_8d0d914d85)) { updateNow() })

        page.switchRow(
            getString(app.svetlo.R.string.label_8acc916316),
            "Всего заблокировано: ${"%,d".format(AdBlock.totalBlocked.get())} · правил: ${"%,d".format(AdBlock.ruleCount)}",
            Prefs.adblock,
        ) { Prefs.adblock = it }

        page.header(getString(app.svetlo.R.string.label_cdbc1db869))
        FilterLists.all().forEach { sub ->
            val row = page.switchRow(sub.title, summary(sub), FilterLists.isEnabled(sub)) { on ->
                FilterLists.setEnabled(sub, on)
                if (on && !FilterLists.isDownloaded(this, sub)) updateNow(force = false) else AdBlock.rebuild { build() }
            }
            if (sub.custom) row.view.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_00d30d1648)).setMessage(sub.url)
                    .setPositiveButton(getString(app.svetlo.R.string.label_86ea33aef5)) { _, _ -> FilterLists.removeCustom(this, sub); AdBlock.rebuild { build() } }
                    .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
                true
            }
        }
        page.row(getString(app.svetlo.R.string.label_99a28dc479), getString(app.svetlo.R.string.label_e02613ca5d)) { addList() }
        page.row(getString(app.svetlo.R.string.label_f72ae77771), "Например: ||ads.example.com^ или example.com##.banner") { editRules() }

        page.header(getString(app.svetlo.R.string.label_aaf3212651))
        val wl = AdBlock.whitelist()
        if (wl.isEmpty()) page.row(getString(app.svetlo.R.string.label_92ea97ec43), getString(app.svetlo.R.string.label_b304e3922c))
        wl.forEach { host ->
            page.row(host, getString(app.svetlo.R.string.label_6f833ed510)) {
                AdBlock.setWhitelisted(host, false)
                build()
            }
        }
    }

    private fun summary(sub: FilterLists.Sub): String {
        val upd = FilterLists.updatedAt(sub)
        val rules = FilterLists.ruleCount(sub)
        return when {
            AdBlock.updating && !FilterLists.isDownloaded(this, sub) -> getString(app.svetlo.R.string.label_b6819e91ff)
            !FilterLists.isDownloaded(this, sub) -> getString(app.svetlo.R.string.label_97a764cda6)
            upd == 0L -> "Встроенный · ${"%,d".format(rules)} правил"
            rules == 0 -> if (AdBlock.updating) getString(app.svetlo.R.string.label_22c04f25ec) else getString(app.svetlo.R.string.label_d57bf49aeb)
            else -> "${"%,d".format(rules)} правил · обновлён " +
                DateUtils.getRelativeTimeSpanString(upd, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }
    }

    private fun updateNow(force: Boolean = true) {
        Toast.makeText(this, getString(app.svetlo.R.string.label_a0f32894d4), Toast.LENGTH_SHORT).show()
        AdBlock.update(force) { failed ->
            if (isFinishing || isDestroyed) return@update
            Toast.makeText(this, if (failed == 0) getString(app.svetlo.R.string.label_4e32bafaef) else "Не удалось загрузить списков: $failed", Toast.LENGTH_LONG).show()
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
        AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_a7eaa4a52d)).setView(frame)
            .setPositiveButton(getString(app.svetlo.R.string.label_559a87f7cc)) { _, _ ->
                val url = edit.text.toString().trim()
                if (!url.startsWith("http")) return@setPositiveButton
                FilterLists.addCustom(url)
                updateNow(force = false)
                build()
            }
            .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
    }

    private fun editRules() {
        val file = FilterLists.userRulesFile(this)
        val (frame, edit) = input(getString(app.svetlo.R.string.label_3cedc01f60), if (file.exists()) file.readText() else "", multiline = true)
        AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_f72ae77771)).setView(frame)
            .setPositiveButton(getString(app.svetlo.R.string.label_4864057d62)) { _, _ ->
                file.writeText(edit.text.toString())
                AdBlock.rebuild { build() }
            }
            .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
    }
}

class IncognitoAdblockActivity : AdblockActivity()

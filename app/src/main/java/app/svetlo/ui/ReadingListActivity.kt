package app.svetlo.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import app.svetlo.R
import app.svetlo.ReadingList

class ReadingListActivity : Activity() {
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val url = data?.getStringExtra(ReaderActivity.EXTRA_URL) ?: return
        if (resultCode == RESULT_OK && app.svetlo.Origin.of(url) != null) startActivity(Intent(this, app.svetlo.MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(url)))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = Page(this, getString(R.string.reading_list))
        Thread {
        val items = ReadingList.list(applicationContext)
        runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        if (items.isEmpty()) page.row(getString(app.svetlo.R.string.label_9231cd7902), getString(app.svetlo.R.string.label_d82860a0f5))
        items.forEach { item ->
            val row = page.row(item.title, hostOf(item.url)) {
                startActivityForResult(Intent(this, ReaderActivity::class.java).putExtra("offline_article", item.token), 1)
            }
            row.view.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(getString(app.svetlo.R.string.label_d426cfebd1))
                    .setPositiveButton(getString(app.svetlo.R.string.label_86ea33aef5)) { _, _ -> ReadingList.remove(this, item.token); recreate() }
                    .setNegativeButton(getString(app.svetlo.R.string.label_0ec753be8d), null).show()
                true
            }
        }
        }
        }.start()
    }
}

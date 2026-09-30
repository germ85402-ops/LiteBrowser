package app.svetlo

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Incognito tabs run in a separate process with their own WebView data directory
 * (Android 9+), so cookies, storage and cache never touch normal tabs and are wiped on exit.
 */
object Incognito {
    const val SUFFIX = "incognito"
    const val ACTION_NEW_TAB = "app.svetlo.NEW_TAB"

    val isolated get() = Build.VERSION.SDK_INT >= 28

    fun activityClass(): Class<*> = if (isolated) IncognitoActivity::class.java else IncognitoLegacyActivity::class.java

    fun intent(ctx: Context, url: String? = null): Intent =
        Intent(ctx, activityClass()).apply {
            if (url != null) { action = Intent.ACTION_VIEW; data = android.net.Uri.parse(url) } else action = ACTION_NEW_TAB
        }

    fun processName(app: Application): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { File("/proc/${Process.myPid()}/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")

    fun isIncognitoProcess(app: Application) = processName(app).endsWith(":$SUFFIX")

    private fun processRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses?.any { it.processName.endsWith(":$SUFFIX") && it.pid != Process.myPid() } == true
    }

    /** Deletes leftover incognito WebView data. Must run before WebView is used in the incognito process. */
    fun wipe(ctx: Context, fromMainProcess: Boolean) {
        if (!isolated || fromMainProcess && processRunning(ctx)) return
        File(ctx.dataDir, "shared_prefs/settings-incognito.xml").delete()
        File(ctx.dataDir, "shared_prefs/settings-incognito.xml.bak").delete()
        listOfNotNull(ctx.dataDir, ctx.cacheDir).forEach { root -> deleteMatching(root, 0) }
    }

    private fun deleteMatching(dir: File, depth: Int) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.contains(SUFFIX)) f.deleteRecursively()
            else if (f.isDirectory && depth < 2 && f.name != "shared_prefs" && f.name != "databases" && f.name != "files") {
                deleteMatching(f, depth + 1)
            }
        }
    }
}

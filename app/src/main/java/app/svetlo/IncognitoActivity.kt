package app.svetlo

import android.content.Context
import android.content.res.Configuration
import android.os.Process

/** Incognito window: always dark, nothing persisted, runs in the `:incognito` process on Android 9+. */
open class IncognitoActivity : MainActivity() {
    override val incognito get() = true

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val cfg = Configuration()
        cfg.uiMode = Configuration.UI_MODE_NIGHT_YES or (base.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        applyOverrideConfiguration(cfg)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drop the whole process so no incognito state survives in memory; data is wiped on next start.
        if (isFinishing && Incognito.isolated) Process.killProcess(Process.myPid())
    }
}

/** Pre-Android 9 fallback in the main process: no history or saved tabs, cache cleared on exit. */
class IncognitoLegacyActivity : IncognitoActivity()

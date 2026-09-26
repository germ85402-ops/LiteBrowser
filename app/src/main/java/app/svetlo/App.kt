package app.svetlo

import android.app.Application
import app.svetlo.data.BrowserDb
import app.svetlo.data.Prefs
import app.svetlo.filter.AdBlock

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Incognito.isIncognitoProcess(this)) {
            Incognito.wipe(this, fromMainProcess = false)
            if (android.os.Build.VERSION.SDK_INT >= 28) android.webkit.WebView.setDataDirectorySuffix(Incognito.SUFFIX)
        } else {
            Incognito.wipe(this, fromMainProcess = true)
        }
        Prefs.init(this)
        DownloadRegistry.init(this)
        BrowserDb.init(this)
        AdBlock.init(this)
    }
}

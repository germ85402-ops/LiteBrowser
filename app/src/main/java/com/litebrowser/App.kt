package com.litebrowser

import android.app.Application
import com.litebrowser.data.BrowserDb
import com.litebrowser.data.Prefs
import com.litebrowser.filter.AdBlock

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
        BrowserDb.init(this)
        AdBlock.init(this)
    }
}

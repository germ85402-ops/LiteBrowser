package com.litebrowser

import android.app.Application
import com.litebrowser.data.BrowserDb
import com.litebrowser.data.Prefs
import com.litebrowser.filter.AdBlock

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        BrowserDb.init(this)
        AdBlock.init(this)
    }
}

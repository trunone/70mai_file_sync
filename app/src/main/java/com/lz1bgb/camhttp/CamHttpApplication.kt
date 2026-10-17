package com.lz1bgb.camhttp

import android.app.Application

class CamHttpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSearchInitializer.init(this)
    }
}

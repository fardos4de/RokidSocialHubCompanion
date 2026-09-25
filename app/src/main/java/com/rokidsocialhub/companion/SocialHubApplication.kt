package com.rokidsocialhub.companion

import android.app.Application

class SocialHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RokidTransport.initialize(this)
    }
}

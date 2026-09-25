package com.rokidsocialhub.companion

import android.app.Application

class SocialHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        DiagnosticLog.log("Application", "Social Hub process started")
        DiagnosticLog.logEnvironmentSnapshot(this, "application_start")
        RokidTransport.initialize(this)
    }
}

package com.example.fcmkeepalive

import android.app.Application
import com.google.android.material.color.DynamicColors

class FcmKeepAliveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

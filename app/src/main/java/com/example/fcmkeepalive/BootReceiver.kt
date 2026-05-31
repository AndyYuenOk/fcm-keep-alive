package com.example.fcmkeepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val serviceIntent = Intent(context, ImeSwitchService::class.java).apply {
            action = ImeSwitchService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}


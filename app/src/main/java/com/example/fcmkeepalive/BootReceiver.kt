package com.example.fcmkeepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val serviceIntent = Intent(context, ImeSwitchService::class.java).apply {
                    this.action = ImeSwitchService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            else -> Unit
        }
    }
}


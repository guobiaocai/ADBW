package com.adbw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, SessionService::class.java).setAction(SessionService.ACTION_START)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (_: Throwable) {}
        }
    }
}

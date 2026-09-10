package com.toolskidukan.otp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the background refresh after a reboot, if the user left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Store.settings(context).optBoolean("background", true) && Store.accounts(context).length() > 0) {
                ScrapeService.start(context)
            }
        }
    }
}

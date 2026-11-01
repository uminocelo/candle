package com.uminocelo.candle.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the server after the phone boots (once it is unlocked) and after an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ServerService.sync(context)
        }
    }
}

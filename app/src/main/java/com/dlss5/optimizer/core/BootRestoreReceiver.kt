package com.dlss5.optimizer.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dlss5.optimizer.util.Logger
import com.dlss5.optimizer.util.Permissions
import com.dlss5.optimizer.util.Prefs

/**
 * Optional boot restore. Off by default (see `Prefs.bootRestore`); when the user opts in and still
 * grants overlay permission, we bring the engine back after a reboot.
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
            val prefs = Prefs.get(context)
            if (!prefs.bootRestore) {
                Logger.d(TAG, "boot restore disabled")
                return
            }
            if (!Permissions.canDrawOverlays(context)) {
                Logger.i(TAG, "boot restore skipped: overlay permission missing")
                return
            }
            prefs.engineRunning = true
            DlssOverlayService.start(context)
            Logger.i(TAG, "boot restore started engine")
        } catch (t: Throwable) {
            Logger.e(TAG, "boot restore failed", t)
        }
    }

    companion object {
        private const val TAG = "BootRestore"
    }
}

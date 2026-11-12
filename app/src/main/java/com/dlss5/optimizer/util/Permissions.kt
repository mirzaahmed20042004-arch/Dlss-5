package com.dlss5.optimizer.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * All permission probing / routing lives here so no screen has to guess.
 * Every method is safe to call on any API level: nothing throws.
 */
object Permissions {

    fun canDrawOverlays(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (t: Throwable) {
        Logger.w("Perms", "canDrawOverlays failed: ${t.message}")
        false
    }

    fun overlaySettingsIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        } else {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        }.apply {
            // Some OEM ROMs ignore the package Uri; keep the raw action as a secondary route.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    /**
     * Opens overlay settings, falling back to the app details page (and finally nothing) so the user
     * is never dead-ended by an OEM that has removed the overlay screen.
     */
    fun openOverlaySettings(context: Context) {
        val candidates = listOf(
            overlaySettingsIntent(context),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (t: Throwable) {
                Logger.w("Perms", "settings route failed: ${t.message}")
            }
        }
        Logger.e("Perms", "no settings activity available on this device")
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notificationPermission(): String = Manifest.permission.POST_NOTIFICATIONS

    fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** `POST_NOTIFICATIONS` on 33+, `null` when the OS has no runtime prompt for it. */
    fun missingRuntimePermissions(context: Context): Array<String> {
        val out = ArrayList<String>(1)
        if (needsNotificationPermission() && !hasNotificationPermission(context)) {
            out.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return out.toTypedArray()
    }
}

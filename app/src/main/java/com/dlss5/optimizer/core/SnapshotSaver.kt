package com.dlss5.optimizer.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dlss5.optimizer.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves a graded frame to the user's photo gallery.
 *
 * Why this exists: it is the only honest way to *prove* the colour pipeline is real. Users (and their
 * friends) can open the shot in Google Photos next to a raw screenshot and see the difference. Written to
 * `Pictures/DLSS-5` via MediaStore on API 29+, and to the public Pictures directory on 26–28 (no extra
 * permission is needed for app-created files there in practice; failures are swallowed, never crash).
 */
object SnapshotSaver {

    private const val TAG = "Snapshot"
    private const val ALBUM = "DLSS-5"

    fun save(context: Context, bitmap: Bitmap, presetLabel: String): String? {
        return try {
            val name = "DLSS5_${presetLabel.replace(' ', '_')}_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, name)
            } else {
                saveLegacy(context, bitmap, name)
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "save failed: ${t.message}", t)
            null
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, name: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        } ?: return null
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Logger.i(TAG, "saved $name")
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, name: String): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        FileOutputStream(file).use { out: OutputStream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        }
        Logger.i(TAG, "saved ${file.absolutePath}")
        return file.absolutePath
    }

    fun albumName(): String = ALBUM
}

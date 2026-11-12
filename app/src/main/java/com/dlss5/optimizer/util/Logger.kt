package com.dlss5.optimizer.util

import android.util.Log
import com.dlss5.optimizer.BuildConfig

/**
 * Thin logging facade so release builds stay quiet and every module logs through one tag prefix.
 */
object Logger {

    private const val PREFIX = "DLSS5/"
    private val verboseEnabled = BuildConfig.DEBUG

    fun v(tag: String, message: String) {
        if (verboseEnabled) Log.v(PREFIX + tag, message)
    }

    fun d(tag: String, message: String) {
        if (verboseEnabled) Log.d(PREFIX + tag, message)
    }

    fun i(tag: String, message: String) = Log.i(PREFIX + tag, message)

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.w(PREFIX + tag, message, t) else Log.w(PREFIX + tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(PREFIX + tag, message, t) else Log.e(PREFIX + tag, message)
    }
}

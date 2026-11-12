package com.dlss5.optimizer.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.dlss5.optimizer.util.DeviceGuards
import com.dlss5.optimizer.util.Logger

/**
 * Snapshot of the hardware we are running on. Everything is probed defensively — a missing value
 * degrades the *quality preset*, it never breaks startup.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val socModel: String,
    val gpuRenderer: String,
    val totalRamMb: Int,
    val cpuCores: Int,
    val maxRefreshHz: Float,
    val currentRefreshHz: Float,
    val supportsEs3: Boolean,
    val tier: SocTier,
    val freeStorageMb: Int
) {
    /** Recommended default render scale for this class of hardware. */
    val defaultRenderScale: Float
        get() = when (tier) {
            SocTier.FLAGSHIP -> 0.85f
            SocTier.HIGH -> 0.78f
            SocTier.MID -> 0.70f
            SocTier.ENTRY -> 0.62f
        }

    val defaultTargetFps: Int
        get() = when {
            maxRefreshHz >= 100f -> 90
            maxRefreshHz >= 75f -> 75
            else -> 60
        }

    fun summary(): String = "$manufacturer $model · Android $androidRelease (API $sdkInt) · " +
        "${socModel} · ${totalRamMb}MB · ${cpuCores} cores · ${maxRefreshHz.toInt()}Hz · tier=${tier.key}"
}

enum class SocTier(val key: String) {
    FLAGSHIP("flagship"),
    HIGH("high"),
    MID("mid"),
    ENTRY("entry");

    companion object {
        fun fromKey(key: String?): SocTier = entries.firstOrNull { it.key == key } ?: MID
    }
}

object DeviceProfiler {

    private const val TAG = "Profiler"

    fun profile(context: Context): DeviceProfile {
        val ramMb = totalRamMb(context)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val gpu = gpuRenderer(context)
        val maxHz = DeviceGuards.maxRefreshRateHz(context)
        val currentHz = DeviceGuards.refreshRateHz(context)
        val es3 = supportsGlEs3(context)
        val tier = classify(ramMb, cores, gpu, Build.VERSION.SDK_INT)

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE.orEmpty()
            } else {
                Build.HARDWARE.orEmpty()
            },
            gpuRenderer = gpu,
            totalRamMb = ramMb,
            cpuCores = cores,
            maxRefreshHz = maxHz,
            currentRefreshHz = currentHz,
            supportsEs3 = es3,
            tier = tier,
            freeStorageMb = freeStorageMb()
        ).also { Logger.i(TAG, it.summary()) }
    }

    fun totalRamMb(context: Context): Int = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(512)
    } catch (t: Throwable) {
        Logger.w(TAG, "ram probe failed: ${t.message}")
        2048
    }

    fun gpuRenderer(context: Context): String = try {
        val pm = context.packageManager
        val featureInfo: Array<FeatureInfo>? = pm.systemAvailableFeatures
        val gl = featureInfo?.firstOrNull {
            it.name == PackageManager.FEATURE_OPENGLES_EXTENSION_PACK || it.reqGlEsVersion > 0
        }?.reqGlEsVersion
        if (gl != null && gl > 0) {
            "GLES ${(gl shr 16)}.${(gl and 0xFFFF)}"
        } else {
            "GLES unknown"
        }
    } catch (t: Throwable) {
        "GLES unknown"
    }

    fun supportsGlEs3(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
    } catch (t: Throwable) {
        false
    }

    private fun freeStorageMb(): Int = try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        (stat.availableBlocksLong * stat.blockSizeLong / (1024L * 1024L)).toInt()
    } catch (t: Throwable) {
        0
    }

    /**
     * Coarse but stable tiering. RAM and core count dominate because GPU strings are inconsistent
     * across OEMs ("Adreno (TM) 640" vs "Adreno 640" vs "Mali-G76 MC4").
     */
    fun classify(ramMb: Int, cores: Int, gpu: String, sdk: Int): SocTier {
        val gpuLower = gpu.lowercase()
        val highEndGpu = listOf("adreno 6", "adreno 7", "mali-g7", "mali-g8", "mali-g9", "immortalis", "xclipse")
            .any { gpuLower.contains(it) }
        val lowEndGpu = listOf("adreno 5", "adreno 3", "adreno 4", "mali-4", "mali-5", "mali-t", "mali-g3", "powervr")
            .any { gpuLower.contains(it) }

        return when {
            ramMb >= 7900 && cores >= 8 && (highEndGpu || sdk >= 33) -> SocTier.FLAGSHIP
            ramMb >= 5600 && cores >= 8 && !lowEndGpu -> SocTier.HIGH
            ramMb >= 3400 && cores >= 6 -> SocTier.MID
            else -> SocTier.ENTRY
        }
    }
}

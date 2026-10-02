package dev.localphoto.enhancer.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import dev.localphoto.core.Profile
import dev.localphoto.core.MemoryPolicy
import kotlin.math.min

data class DeviceCapabilities(
    val cores: Int,
    val heapBytes: Long,
    val availableMemory: Long,
    val storageBytes: Long,
    val architecture: String,
    val androidVersion: Int,
    val thermalStatus: Int,
) {
    val processingBudget: Long get() = min(heapBytes * 45 / 100, availableMemory / 4).coerceAtLeast(24L * 1024 * 1024)
    val maxSourcePixels: Long get() = ((processingBudget - MemoryPolicy.WORKING_RESERVE_BYTES) / 24).coerceAtLeast(128L * 128)
    fun tileSize(profile: Profile): Int = when {
        processingBudget < 64L * 1024 * 1024 || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 64
        profile == Profile.FAST -> 96
        profile == Profile.BALANCED -> 128
        else -> 192
    }
}

object DeviceCapabilityDetector {
    fun detect(context: Context): DeviceCapabilities {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceCapabilities(Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
            memory.availMem, StatFs(context.filesDir.absolutePath).availableBytes,
            Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown", Build.VERSION.SDK_INT, power.currentThermalStatus)
    }
}

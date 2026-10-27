package com.roadvision.roadsense.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

class DeviceHealthSampler(context: Context) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    fun sample(recorder: PerformanceRecorder) {
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val usedMb = (memory.totalMem - memory.availMem) / (1024 * 1024)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null
        recorder.onDeviceHealth(usedMb, thermal)
    }
}

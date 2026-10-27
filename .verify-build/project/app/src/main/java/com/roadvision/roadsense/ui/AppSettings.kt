package com.roadvision.roadsense.ui

import android.content.Context
import com.roadvision.roadsense.inference.ExecutionProvider

data class AppSettings(
    val retentionDays: Int = 30,
    val providerOverride: ExecutionProvider? = null,
    val vehicleConfidence: Float? = null,
    val plateConfidence: Float? = null,
    val laneThreshold: Float? = null,
    val keepScreenOn: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("roadvision-settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        retentionDays = prefs.getInt(KEY_RETENTION_DAYS, 30).coerceIn(1, 365),
        providerOverride = prefs.getString(KEY_PROVIDER, null)?.let { name ->
            enumValues<ExecutionProvider>().firstOrNull { it.name == name }
        },
        vehicleConfidence = optionalFloat(KEY_VEHICLE_CONFIDENCE),
        plateConfidence = optionalFloat(KEY_PLATE_CONFIDENCE),
        laneThreshold = optionalFloat(KEY_LANE_THRESHOLD),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
    )

    fun save(settings: AppSettings) {
        val editor = prefs.edit()
            .putInt(KEY_RETENTION_DAYS, settings.retentionDays.coerceIn(1, 365))
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
        settings.providerOverride?.let { editor.putString(KEY_PROVIDER, it.name) } ?: editor.remove(KEY_PROVIDER)
        putOptionalFloat(editor, KEY_VEHICLE_CONFIDENCE, settings.vehicleConfidence)
        putOptionalFloat(editor, KEY_PLATE_CONFIDENCE, settings.plateConfidence)
        putOptionalFloat(editor, KEY_LANE_THRESHOLD, settings.laneThreshold)
        editor.apply()
    }

    private fun optionalFloat(key: String): Float? =
        if (prefs.contains(key)) prefs.getFloat(key, 0f).coerceIn(0f, 1f) else null

    private fun putOptionalFloat(editor: android.content.SharedPreferences.Editor, key: String, value: Float?) {
        if (value == null) editor.remove(key) else editor.putFloat(key, value.coerceIn(0f, 1f))
    }

    private companion object {
        const val KEY_RETENTION_DAYS = "retentionDays"
        const val KEY_PROVIDER = "provider"
        const val KEY_VEHICLE_CONFIDENCE = "vehicleConfidence"
        const val KEY_PLATE_CONFIDENCE = "plateConfidence"
        const val KEY_LANE_THRESHOLD = "laneThreshold"
        const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    }
}

package com.roadvision.roadsense.privacy

import android.util.Log

object PrivacyLog {
    fun maskPlate(value: String?): String = when {
        value.isNullOrBlank() -> "<none>"
        value.length <= 2 -> "**"
        else -> value.take(1) + "*".repeat(value.length - 2) + value.takeLast(1)
    }

    fun info(tag: String, message: String) = Log.i(tag, message)
    fun event(tag: String, trackId: Long, plate: String?, message: String) =
        Log.i(tag, "track=$trackId plate=${maskPlate(plate)} $message")
}

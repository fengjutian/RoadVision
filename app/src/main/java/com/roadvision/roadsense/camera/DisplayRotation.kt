package com.roadvision.roadsense.camera

import android.view.Surface

/**
 * Validates an Android display/surface rotation before it is handed to CameraX.
 *
 * [Surface.ROTATION_0] to [Surface.ROTATION_270] are the only values CameraX accepts; anything
 * else (for example `-1` while a display is being reconfigured) must not be forwarded, otherwise
 * the analyser frames would be labelled with a rotation nobody can map back to the preview.
 */
fun normalizeSurfaceRotation(rotation: Int): Int? = when (rotation) {
    Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270 -> rotation
    else -> null
}

package com.roadvision.roadsense.ui

/** Composition used for the current window, decided from the measured window size. */
enum class ScreenLayout { PORTRAIT, LANDSCAPE }

/**
 * Picks the composition from the measured window size rather than from the device orientation.
 * Split-screen, free-form windows and foldables can present a portrait-shaped window on a
 * landscape device (and the reverse), and the panel has to follow the window that is actually
 * drawn, not the accelerometer.
 */
fun screenLayoutFor(width: Float, height: Float): ScreenLayout =
    if (height > width) ScreenLayout.PORTRAIT else ScreenLayout.LANDSCAPE

/**
 * Share of the window height the expanded portrait panel may take. It stays below half so the
 * camera picture and its overlay remain visible while diagnostics and events are inspected.
 */
const val PORTRAIT_PANEL_MAX_HEIGHT_FRACTION = 0.55f

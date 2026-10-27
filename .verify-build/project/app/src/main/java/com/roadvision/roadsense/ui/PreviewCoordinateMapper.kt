package com.roadvision.roadsense.ui

data class PixelPoint(val x: Float, val y: Float)

/** Matches PreviewView.ScaleType.FILL_CENTER: scale uniformly, then crop equally on both sides. */
class PreviewCoordinateMapper(
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
) {
    private val scale = maxOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
    private val offsetX = (viewWidth - sourceWidth * scale) / 2f
    private val offsetY = (viewHeight - sourceHeight * scale) / 2f
    private val sourceWidth = sourceWidth.toFloat()
    private val sourceHeight = sourceHeight.toFloat()

    init {
        require(sourceWidth > 0 && sourceHeight > 0 && viewWidth > 0 && viewHeight > 0)
    }

    fun map(normalizedX: Float, normalizedY: Float): PixelPoint = PixelPoint(
        offsetX + normalizedX * sourceWidth * scale,
        offsetY + normalizedY * sourceHeight * scale,
    )
}

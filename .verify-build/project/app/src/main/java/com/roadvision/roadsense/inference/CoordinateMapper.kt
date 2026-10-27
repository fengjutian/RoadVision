package com.roadvision.roadsense.inference

import kotlin.math.min

/** Maps boxes and points between model-input coordinates and the source frame, both normalised. */
interface FrameTransform {
    fun modelToSource(box: NormalizedRect): NormalizedRect
    fun modelToSource(point: LanePoint): LanePoint
}

data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
) : FrameTransform {
    private val scale = minOf(inputWidth.toFloat() / sourceWidth, inputHeight.toFloat() / sourceHeight)
    private val scaledWidth = sourceWidth * scale
    private val scaledHeight = sourceHeight * scale
    private val padX = (inputWidth - scaledWidth) / 2f
    private val padY = (inputHeight - scaledHeight) / 2f

    override fun modelToSource(box: NormalizedRect): NormalizedRect {
        val left = (box.left * inputWidth - padX) / scaledWidth
        val right = (box.right * inputWidth - padX) / scaledWidth
        val top = (box.top * inputHeight - padY) / scaledHeight
        val bottom = (box.bottom * inputHeight - padY) / scaledHeight
        return NormalizedRect(left, top, right, bottom).clamp()
    }

    override fun modelToSource(point: LanePoint): LanePoint = LanePoint(
        ((point.x * inputWidth - padX) / scaledWidth).coerceIn(0f, 1f),
        ((point.y * inputHeight - padY) / scaledHeight).coerceIn(0f, 1f),
    )

    fun sourceToModel(box: NormalizedRect): NormalizedRect = NormalizedRect(
        (box.left * scaledWidth + padX) / inputWidth,
        (box.top * scaledHeight + padY) / inputHeight,
        (box.right * scaledWidth + padX) / inputWidth,
        (box.bottom * scaledHeight + padY) / inputHeight,
    )
}

/**
 * The frame is stretched to the model input, so a normalised coordinate means the same thing on
 * both sides; only rounding can push a value marginally out of range.
 */
class StretchTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
) : FrameTransform {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && inputWidth > 0 && inputHeight > 0)
    }

    override fun modelToSource(box: NormalizedRect): NormalizedRect = box.clamp()

    override fun modelToSource(point: LanePoint): LanePoint =
        LanePoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
}

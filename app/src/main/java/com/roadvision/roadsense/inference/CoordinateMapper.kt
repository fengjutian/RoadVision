package com.roadvision.roadsense.inference

data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
) {
    private val scale = minOf(inputWidth.toFloat() / sourceWidth, inputHeight.toFloat() / sourceHeight)
    private val scaledWidth = sourceWidth * scale
    private val scaledHeight = sourceHeight * scale
    private val padX = (inputWidth - scaledWidth) / 2f
    private val padY = (inputHeight - scaledHeight) / 2f

    fun modelToSource(box: NormalizedRect): NormalizedRect {
        val left = (box.left * inputWidth - padX) / scaledWidth
        val right = (box.right * inputWidth - padX) / scaledWidth
        val top = (box.top * inputHeight - padY) / scaledHeight
        val bottom = (box.bottom * inputHeight - padY) / scaledHeight
        return NormalizedRect(left, top, right, bottom).clamp()
    }

    fun modelToSource(point: LanePoint): LanePoint = LanePoint(
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

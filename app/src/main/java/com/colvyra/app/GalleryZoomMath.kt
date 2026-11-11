package com.colvyra.app

/** Keep image zoom predictable and within a bounded, testable range. */
internal object GalleryZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f

    fun scale(current: Float, factor: Float): Float =
        (current * factor).coerceIn(MIN_SCALE, MAX_SCALE)

    /** Pan is measured in screen pixels; reset to center when zoom returns to 1x. */
    fun pan(
        currentX: Float,
        currentY: Float,
        changeX: Float,
        changeY: Float,
        width: Int,
        height: Int,
        zoom: Float
    ): Pair<Float, Float> {
        if (zoom <= MIN_SCALE || width <= 0 || height <= 0) return 0f to 0f
        val maxX = width * (zoom - MIN_SCALE) / 2f
        val maxY = height * (zoom - MIN_SCALE) / 2f
        return (currentX + changeX).coerceIn(-maxX, maxX) to
            (currentY + changeY).coerceIn(-maxY, maxY)
    }
}

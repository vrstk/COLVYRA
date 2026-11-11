package com.colvyra.app

/**
 * Presentation-only photo zoom. This does not alter image bytes or metadata.
 * Translation is limited to the scaled viewport so zoomed images remain reachable.
 */
internal data class GalleryZoom(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

internal fun constrainGalleryZoom(
    previous: GalleryZoom,
    newScale: Float,
    viewportWidth: Int,
    viewportHeight: Int,
    panX: Float = 0f,
    panY: Float = 0f
): GalleryZoom {
    val scale = (if (newScale.isFinite()) newScale else 1f).coerceIn(1f, 4f)
    if (scale == 1f) return GalleryZoom()
    val width = viewportWidth.coerceAtLeast(0).toFloat()
    val height = viewportHeight.coerceAtLeast(0).toFloat()
    val maxX = (scale - 1f) * width / 2f
    val maxY = (scale - 1f) * height / 2f
    val dx = if (panX.isFinite()) panX else 0f
    val dy = if (panY.isFinite()) panY else 0f
    return GalleryZoom(
        scale = scale,
        offsetX = (previous.offsetX + dx).coerceIn(-maxX, maxX),
        offsetY = (previous.offsetY + dy).coerceIn(-maxY, maxY)
    )
}

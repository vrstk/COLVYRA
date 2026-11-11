package com.colvyra.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryZoomMathTest {
    @Test fun zoomNeverGoesOutsideSupportedRange() {
        assertEquals(1f, GalleryZoomMath.scale(1f, 0.1f), 0f)
        assertEquals(2.5f, GalleryZoomMath.scale(1f, 2.5f), 0f)
        assertEquals(4f, GalleryZoomMath.scale(3f, 2f), 0f)
    }

    @Test fun panIsBoundedByZoomAndViewport() {
        val bounds = GalleryZoomMath.pan(0f, 0f, 2000f, -3000f, 400, 600, 2f)
        assertEquals(200f, bounds.first, 0f)
        assertEquals(-300f, bounds.second, 0f)
    }

    @Test fun resettingZoomAlsoResetsPan() {
        val pan = GalleryZoomMath.pan(95f, 44f, 10f, 20f, 600, 800, 1f)
        assertEquals(0f, pan.first, 0f)
        assertEquals(0f, pan.second, 0f)
    }

    @Test fun missingViewportDoesNotAllowDrift() {
        val pan = GalleryZoomMath.pan(95f, 44f, 10f, 20f, 0, 800, 3f)
        assertEquals(0f, pan.first, 0f)
        assertEquals(0f, pan.second, 0f)
    }
}

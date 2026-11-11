package com.colvyra.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryZoomTest {
    @Test fun resetAtOneRemovesStaleTranslation() {
        assertEquals(
            GalleryZoom(),
            constrainGalleryZoom(GalleryZoom(3f, 100f, -50f), 1f, 400, 800)
        )
    }

    @Test fun clampsScaleBetweenOneAndFour() {
        assertEquals(4f, constrainGalleryZoom(GalleryZoom(), 50f, 400, 800).scale)
        assertEquals(1f, constrainGalleryZoom(GalleryZoom(), -8f, 400, 800).scale)
    }

    @Test fun panStaysWithinViewportBounds() {
        val next = constrainGalleryZoom(
            GalleryZoom(), 2f, viewportWidth = 200, viewportHeight = 400,
            panX = 900f, panY = -900f
        )
        assertEquals(GalleryZoom(2f, 100f, -200f), next)
    }

    @Test fun tinyOrMissingViewportIsSafe() {
        assertEquals(
            GalleryZoom(2f, 0f, 0f),
            constrainGalleryZoom(GalleryZoom(), 2f, 0, -1, 1000f, -1000f)
        )
    }

    @Test fun rejectsNonFiniteGestureValues() {
        assertEquals(
            GalleryZoom(),
            constrainGalleryZoom(GalleryZoom(), Float.NaN, 300, 500)
        )
        assertEquals(
            GalleryZoom(2f),
            constrainGalleryZoom(GalleryZoom(), 2f, 300, 500, Float.NaN, Float.POSITIVE_INFINITY)
        )
    }
}

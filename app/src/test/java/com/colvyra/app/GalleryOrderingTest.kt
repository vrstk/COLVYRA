package com.colvyra.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryOrderingTest {
    private data class Media(val uri: String, val allowed: Boolean = true)

    private fun order(initial: Media, files: List<Media>): List<Media> =
        orderedGalleryItems(initial, files, { it.allowed }, { it.uri })

    @Test fun usesCurrentOrderAndRemovesDuplicateUris() {
        val files = listOf(Media("b"), Media("a"), Media("b"), Media("c"))
        assertEquals(listOf("b", "a", "c"), order(Media("a"), files).map { it.uri })
    }

    @Test fun skipsNonMediaCandidates() {
        val files = listOf(Media("folder", false), Media("photo"), Media("archive", false))
        assertEquals(listOf("photo"), order(Media("photo"), files).map { it.uri })
    }

    @Test fun retainsTappedMediaWhenFilterChanges() {
        assertEquals(listOf("original", "other"),
            order(Media("original"), listOf(Media("other"))).map { it.uri })
    }

    @Test fun supportsSingleFileAndEmptyVisibleList() {
        assertEquals(listOf("only"), order(Media("only"), emptyList()).map { it.uri })
        assertEquals(listOf("only"), order(Media("only"), listOf(Media("only"))).map { it.uri })
    }
}

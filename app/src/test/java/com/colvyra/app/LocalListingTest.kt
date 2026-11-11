package com.colvyra.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalListingTest {
    private data class Item(
        val name: String,
        val mime: String,
        val folder: Boolean = false,
        val date: Long = 0,
        val size: Long = 0
    )

    private val files = listOf(
        Item("Zoo", "vnd.android.document/directory", folder = true),
        Item("Trip.jpg", "image/jpeg", date = 20, size = 40),
        Item("movie.mp4", "video/mp4", date = 40, size = 150),
        Item("notes.txt", "text/plain", date = 10, size = 10),
        Item("Albums", "vnd.android.document/directory", folder = true),
        Item("Beach.png", "image/png", date = 30, size = 60)
    )

    private fun list(
        query: String = "",
        gallery: Boolean = false,
        type: LocalTypeFilter = LocalTypeFilter.ALL,
        sort: LocalSort = LocalSort.NAME,
        reverse: Boolean = false
    ) = localVisibleEntries(
        entries = files, query = query, galleryOnly = gallery, typeFilter = type,
        sort = sort, reverse = reverse,
        name = { it.name }, mime = { it.mime }, folder = { it.folder },
        modified = { it.date }, size = { it.size }
    ).map { it.name }

    @Test fun fileModeShowsEveryTypeButFoldersAlwaysFirst() {
        assertEquals(listOf("Albums", "Zoo", "Beach.png", "movie.mp4", "notes.txt", "Trip.jpg"), list())
    }

    @Test fun galleryExcludesDocumentsWithoutHidingFolders() {
        assertEquals(listOf("Albums", "Zoo", "Beach.png", "movie.mp4", "Trip.jpg"), list(gallery = true))
    }

    @Test fun imageAndVideoFiltersRetainFolderNavigation() {
        assertEquals(listOf("Albums", "Zoo", "Beach.png", "Trip.jpg"),
            list(gallery = true, type = LocalTypeFilter.IMAGES))
        assertEquals(listOf("Albums", "Zoo", "movie.mp4"),
            list(type = LocalTypeFilter.VIDEOS))
    }

    @Test fun descendingNamesKeepDirectoriesAboveFiles() {
        assertEquals(listOf("Zoo", "Albums", "Trip.jpg", "notes.txt", "movie.mp4", "Beach.png"),
            list(reverse = true))
    }

    @Test fun dateAndSizeOrderApplyOnlyWithinGroups() {
        assertEquals(listOf("Albums", "Zoo", "movie.mp4", "Beach.png", "Trip.jpg", "notes.txt"),
            list(sort = LocalSort.DATE))
        assertEquals(listOf("Zoo", "Albums", "notes.txt", "Trip.jpg", "Beach.png", "movie.mp4"),
            list(sort = LocalSort.SIZE, reverse = true))
    }

    @Test fun caseInsensitiveSearchTrimsSurroundingSpaces() {
        assertEquals(listOf("Trip.jpg"), list(query = " TRIP "))
        assertEquals(emptyList<String>(), list(query = "nothing"))
    }
}

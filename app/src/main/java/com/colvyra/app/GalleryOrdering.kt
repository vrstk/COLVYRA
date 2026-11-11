package com.colvyra.app

/**
 * Pure ordering rule for the local gallery. The original visible ordering is
 * preserved, duplicate URIs are dropped, and the tapped item remains viewable
 * even if a filter changes between the click and opening the viewer.
 */
internal fun <T> orderedGalleryItems(
    initial: T,
    candidates: List<T>,
    isMedia: (T) -> Boolean,
    id: (T) -> String
): List<T> {
    val seen = HashSet<String>()
    val visible = candidates.filter { isMedia(it) && seen.add(id(it)) }
    return if (id(initial) in seen) visible else listOf(initial) + visible
}

package com.colvyra.app

/** The local list and gallery use the same filtering and sorting rules. */
internal enum class LocalSort(val label: String) {
    NAME("Имя"), DATE("Дата"), SIZE("Размер")
}

internal enum class LocalTypeFilter(val label: String) {
    ALL("Все типы"), IMAGES("Фото"), VIDEOS("Видео")
}

/**
 * Keeps directories navigable even when only one media type is displayed.
 * Reverse applies within the file/folder groups, never putting files above folders.
 */
internal fun <T> localVisibleEntries(
    entries: List<T>,
    query: String,
    galleryOnly: Boolean,
    typeFilter: LocalTypeFilter,
    sort: LocalSort,
    reverse: Boolean,
    name: (T) -> String,
    mime: (T) -> String,
    folder: (T) -> Boolean,
    modified: (T) -> Long,
    size: (T) -> Long
): List<T> {
    val cleanedQuery = query.trim()
    val sorted = when (sort) {
        LocalSort.NAME -> compareBy<T> { name(it).lowercase() }
        LocalSort.DATE -> compareByDescending<T> { modified(it) }.thenBy { name(it).lowercase() }
        LocalSort.SIZE -> compareByDescending<T> { size(it) }.thenBy { name(it).lowercase() }
    }
    val withinGroups = if (reverse) sorted.reversed() else sorted
    return entries.asSequence().filter { item ->
        val isFolder = folder(item)
        val isImage = mime(item).startsWith("image/")
        val isVideo = mime(item).startsWith("video/")
        val allowedType = when (typeFilter) {
            LocalTypeFilter.ALL -> true
            LocalTypeFilter.IMAGES -> isImage
            LocalTypeFilter.VIDEOS -> isVideo
        }
        (isFolder || ((isImage || isVideo || !galleryOnly) && allowedType)) &&
            name(item).contains(cleanedQuery, ignoreCase = true)
    }.sortedWith(compareBy<T> { !folder(it) }.then(withinGroups)).toList()
}

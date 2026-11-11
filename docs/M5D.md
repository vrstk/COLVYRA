# M5D — Local file browsing filters, sorting and details

Version: `0.5.3-m5d` (version code 14).

## Features
- Local file-manager and gallery views now have a type filter: all entries, images, or videos.
- Folder entries remain accessible under every filter, allowing normal folder navigation.
- Sort entries by name, modified date, or size. Reverse the direction without intermixing directories and files.
- Sort, reverse and type-filter preferences persist separately for file manager and gallery across app restarts.
- Each local file action menu includes **Информация** with name, MIME type, size (files only) and last modification date when supplied by the SAF provider.
- Filtering and sorting are delegated to a pure function covered by six JVM regression tests.

## Manual verification
1. Select a SAF folder containing nested directories, images, videos and documents.
2. Try each type filter in both Файлы and Галерея. Verify that folders stay navigable.
3. Sort by name/date/size in both directions, leave and reopen the app. Verify preferences remain independently saved for each tab.
4. Search with mixed case and spaces, open a resulting image, and check that swipe paging follows the filtered, sorted result set.
5. Open a file's Действия → Информация. Compare name, size and last-modified date to an external Android file manager; unsupported timestamps show «Нет данных».
6. Test existing copy, move, rename, delete and add-to-collection operations with permitted SAF folders.

Metadata is provider-supplied and may be missing, outdated, or approximate; no recursive scanning is introduced. All changes affect only listing/presentation until a user explicitly chooses a file operation. No release signing key is generated. Real-device validation is pending.

# COLVYRA

Open-source Android file manager, gallery and virtual collections.

## Current: M4D (development)

- Local SAF browser, gallery, thumbnails and media viewer.
- Room virtual collections, links, descriptions, tags and favorites.
- HTTPS WebDAV/Mail.ru Cloud: multiple accounts, folder browsing, authenticated media, file operations.
- HTTPS browser, bookmarks, history, share URLs, web links in collections.
- WorkManager download center for direct cloud/web files, progress and cancellation.
- Android share import: user-confirmed copies into private app storage and a chosen collection.

## Build

Java 17, Gradle 8.13, Android SDK 36. Actions → latest successful Android CI → Artifacts → colvyra-m4d-debug-apk.

## Limits

No native Google Drive/Photos, Telegram, VK or Reddit API yet. No protected stream extraction. Downloads require a provider granting persistent write access; redirects are rejected. WorkManager can delay or interrupt jobs, no byte-range resume, no guaranteed very long transfers. Downloads stage data in cache before writing the destination. Imports are copies (not source links); removing a collection does not remove imported originals. App removal deletes private imports and library. Device testing and stable release signing remain pending. No credentials or user data in Git.

See docs/PRODUCT_SPEC.md, docs/ROADMAP.md and docs/M4B.md. Apache-2.0.

M4c: settings with three theme modes and metadata-only JSON library export/merge. Original media and credentials are never exported. Permanent APK signing and swipe gallery are future work.

M4D: full-screen swipe navigation for local folder and collection media, next/previous controls, position counter and active-page video playback. See docs/M4D.md. Device regression testing remains pending.

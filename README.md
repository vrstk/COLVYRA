# COLVYRA

Open-source Android file manager, gallery and virtual collections.

## Current: M5C (development)

- Local SAF browser, gallery, thumbnails and media viewer.
- Room virtual collections, links, descriptions, tags and favorites.
- HTTPS WebDAV/Mail.ru Cloud: multiple accounts, folder browsing, authenticated media, file operations.
- HTTPS browser, bookmarks, history, share URLs, web links in collections.
- WorkManager download center for direct cloud/web files, progress and cancellation.
- Android share import: user-confirmed copies into private app storage and a chosen collection.

## Build

Java 17, Gradle 8.13, Android SDK 36. Actions → latest successful Android CI → Artifacts → colvyra-m5c-debug-apk.

## Limits

No native Google Drive/Photos, Telegram, VK or Reddit API yet. No protected stream extraction. Downloads require a provider granting persistent write access; redirects are rejected. WorkManager can delay or interrupt jobs, no byte-range resume, no guaranteed very long transfers. Downloads stage data in cache before writing the destination. Imports are copies (not source links); removing a collection does not remove imported originals. App removal deletes private imports and library. Device testing and stable release signing remain pending. No credentials or user data in Git.

See docs/PRODUCT_SPEC.md, docs/ROADMAP.md and docs/M4B.md. Apache-2.0.

M4c: settings with three theme modes and metadata-only JSON library export/merge. Original media and credentials are never exported. Permanent APK signing and swipe gallery are future work.

M4D: full-screen swipe navigation for local folder and collection media, next/previous controls, position counter and active-page video playback. See docs/M4D.md. Device regression testing remains pending.

M5A: shared bounded local thumbnail cache and opt-in, secret-backed signed release workflow. The debug APK is not a reliable in-place upgrade across CI runners. Release signing requires user-supplied secrets; see docs/M5A.md. Physical-device regression tests still pending.

M5B: JVM tests for gallery ordering and backup JSON integrity, stricter duplicate/orphan validation on metadata import, and unit tests as a required CI gate. See docs/M5B.md. Physical device tests still pending.

M5C: Settings now displays installed version/build type, signing certificate SHA-256, Android storage permission summary and collection counts. Includes a local-only, privacy-limited copyable diagnostics report. See docs/M5C.md. No secrets are configured, and physical-device verification remains pending.

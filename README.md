# COLVYRA

**Your Files. Your Collections. Your Cloud.**

COLVYRA is a planned open-source Android file manager and media gallery with virtual collections. It is meant for any kind of collection, not just a particular subject.

## Status: M4a websites (development)

The initial version contains:
- five main tabs (Files, Gallery, Collections, Sources, Websites);
- light and dark themes;
- user-granted Android folder access (Storage Access Framework);
- folder navigation and external file opening;
- list/grid, photo/GIF/video previews and an in-app media viewer;
- current-folder search, sort, create folder, rename, confirmed delete and copy/move of individual files;
- Room-backed virtual collections, descriptions, tags, favorites, media covers, and file/folder references;
- import of legacy collection names; originals are never deleted by collection operations;
- CI automation for a debug APK.

Not yet implemented: cross-device backup, cloud references, automatic repair of links after original moves,  recursive gallery indexing, folder copy/move, Mail.ru Cloud/WebDAV, Google Drive/Photos, Telegram, VK, Reddit APIs, embedded browser, downloads and cloud sync. These are milestones, not working features.

## Build the debug APK

GitHub Actions builds automatically on each push to `main`. Open **Actions → Android CI → latest successful run → Artifacts → colvyra-m4a-debug-apk**.

The workflow uses Java 17, Android SDK 36 and Gradle 8.13. The Gradle wrapper is not yet included; for local builds, install Gradle 8.13 (or generate and commit a matching wrapper). The project has not been verified on real devices yet.

## Privacy

No account is required for local functionality. No user media or API secrets are included in this public repository. Users select which Android folder to grant access to. Deleting a virtual collection must never delete original files.

## Docs

- [Product specification](docs/PRODUCT_SPEC.md)
- [Roadmap](docs/ROADMAP.md)

Licensed under Apache-2.0.


M3a adds one saved HTTPS WebDAV account, encrypted credentials, folder listing, MKCOL, upload and download. Real provider testing, multiple accounts, cloud media previews, remote references and transfer queue are pending.


M3b: authenticated image previews, image grid, Media3 streaming video, decoded paths, source switching in Files/Gallery, cloud collection references, rename and confirmed deletion. One account only; cloud video thumbnails, multiple accounts, background transfers, cloud cover thumbnails and cross-device backups remain pending. Cloud references depend on the original account and path.


M3c: multiple encrypted WebDAV accounts with legacy migration, account switching, per-account last folder, account-aware virtual links, optional per-account 80 MiB image disk cache and clear-cache control. Disk cache contains downloaded image data, not resized-only thumbnails. Video thumbnails, persistent transfer queue, and release signing remain pending.


M4a: HTTPS WebView browser, local bookmarks with category and editing, bounded history, direct user-confirmed downloads via Android document picker, share URLs to/from Android, web links in virtual collections, shortcuts to Telegram/VK/Reddit. No native social APIs, scraping, DRM downloads or automatic posting. Browser compatibility and downloads depend on the site. Browser history and bookmarks are local and not encrypted separately.

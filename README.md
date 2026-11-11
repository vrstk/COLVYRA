# COLVYRA

**Your Files. Your Collections. Your Cloud.**

COLVYRA is a planned open-source Android file manager and media gallery with virtual collections. It is meant for any kind of collection, not just a particular subject.

## Status: M1 local browser (development)

The initial version contains:
- five main tabs (Files, Gallery, Collections, Sources, Websites);
- light and dark themes;
- user-granted Android folder access (Storage Access Framework);
- folder navigation and external file opening;
- list/grid, photo/GIF/video previews and an in-app media viewer;
- current-folder search, sort, create folder, rename, confirmed delete and copy/move of individual files;
- local persistent collection names;
- CI automation for a debug APK.

Not yet implemented: recursive gallery indexing, folder copy/move, adding file references to collections, Mail.ru Cloud/WebDAV, Google Drive/Photos, Telegram, VK, Reddit APIs, embedded browser, downloads and cloud sync. These are milestones, not working features.

## Build the debug APK

GitHub Actions builds automatically on each push to `main`. Open **Actions → Android CI → latest successful run → Artifacts → colvyra-m1-debug-apk**.

The workflow uses Java 17, Android SDK 36 and Gradle 8.13. The Gradle wrapper is not yet included; for local builds, install Gradle 8.13 (or generate and commit a matching wrapper). The project has not been verified on real devices yet.

## Privacy

No account is required for local functionality. No user media or API secrets are included in this public repository. Users select which Android folder to grant access to. Deleting a virtual collection must never delete original files.

## Docs

- [Product specification](docs/PRODUCT_SPEC.md)
- [Roadmap](docs/ROADMAP.md)

Licensed under Apache-2.0.

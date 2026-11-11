# M5B — Integrity and JVM regression tests

## Scope
- Android app version: 0.5.1-m5b (versionCode 12).
- Gallery pager ordering is now delegated to a deterministic, JVM-testable helper. Its input is the already visible, sorted set of candidates.
- JSON metadata restore rejects duplicate collection IDs, blank names, missing collection references, duplicate (collection ID, URI) pairs, and oversize catalogs before any database write.
- GitHub Actions runs `testDebugUnitTest` before uploading a debug APK; a failing test blocks the artifact.

## Notes
- Backup import remains metadata-only; it does not restore source media, cloud credentials, SAF permissions, or private app imports.
- Backup validation does not mean imported remote URLs or content URIs are accessible after restore.
- Existing Room insert-conflict policy remains IGNORE: a matching ID in a backup will not overwrite the existing collection. Database restore is transactionally scoped.
- No keystore was generated or uploaded; secure stable-signing secrets must be configured by the repository owner.
- Automated JVM tests cover pure ordering and structural integrity, not UI, SAF providers or a physical Android device.

## Device regression checklist
1. Browse and sort local folders, tap an image, swipe through only the visible media.
2. Import and export a valid metadata JSON file; verify existing collections are retained.
3. Try a backup with duplicate IDs or duplicate references; confirm rejection without partial imports.
4. Check collection search, covers, favorites, theme switching, video and cloud access.
5. Install and upgrade builds with the *same signing certificate* on a test device; do not uninstall without securely copying private imports.

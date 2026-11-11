# M5F — Large local catalogs and bounded thumbnails

Version: `0.5.5-m5f` (versionCode 16).

## Changes
- Local SAF file-list search, filtering and sorting run on `Dispatchers.Default`, not on the Compose/UI thread.
- Obsolete listing computations are cancelled as folder, sort, type or search changes. Loading a SAF directory remains on `Dispatchers.IO`.
- Image and video preview requests are explicitly bounded to **384×384** requested pixels for local file cards, **192×192** for collection reference rows and **512×512** for collection covers. Image requests are inexact to allow efficient downsampling.
- Full-screen media viewing still uses its own viewer request and does not become a permanently low-resolution image.
- Collection cards calculate collection sizes once per observed entry-list update rather than counting all references separately for every card.
- A deterministic regression test checks filter/sort behavior on a 20,000-item synthetic folder.

## Limits
- SAF providers may still take time to enumerate very large or remote folders; directory enumeration is not paginated and provider speed cannot be guaranteed.
- The initial full directory result still occupies memory; improvements focus on responsive UI and bounded preview decoding, not zero-memory or infinite-size catalogs.
- Full-screen images are still limited by device RAM and image decoder behavior. Large videos are opened via their existing playback mechanism.
- Cache contents may use disk space and are automatically managed; the thumbnails are not copies of original user files.
- Release signing requires owner-provided secrets. No key material was created or uploaded.

## Manual Android checks
1. Browse a local SAF folder with hundreds or thousands of mixed files. Rapidly change search text, sort, and filter; verify no ANR and correct final results.
2. Switch repeatedly between the gallery and collections; verify card thumbnails remain responsive and originals are unchanged.
3. Open a high-resolution photograph, pinch and double tap; ensure full-screen viewing remains usable rather than permanently showing a tiny thumbnail.
4. Compare local folder and collection thumb appearance for images and videos; test both grid and list modes.
5. Test folder navigation while filtered, switching themes, and app restart.
6. Check large catalogs on a real Android device, measure memory and scrolling with Android Studio profiler when available.

GitHub CI checks compilation and deterministic JVM logic but not on-device frame-rate, memory usage or SAF-provider latency.

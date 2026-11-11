# M4D: swipeable local viewer

- Full-screen horizontal paging for images and videos from the **currently visible** local SAF folder or virtual collection.
- The order follows the folder's active sort/filter; collection paging follows its visible text filter.
- Counter, previous/next buttons, name, external-open action and close action.
- VideoView is instantiated on the active page only; playback stops on release.
- No original files are copied, modified, deleted or exported by paging.
- Cloud viewing is unchanged; remote authenticated media needs a separately tested pager.

## Manual device regression checklist
1. Open a permitted SAF folder with three images and a video; swipe both ways and use arrow buttons.
2. Check first/last navigation controls and counter, including a single file.
3. Test name search and sort order. Confirm only filtered visible media are paged.
4. Play video, swipe away, return; verify playback doesn't continue in the background.
5. Open media within a collection and test navigation with its text filter.
6. Confirm external-open, back/close, light/dark themes, file operations, cloud browser and JSON backup still work.

Debug CI compilation is necessary but not sufficient for runtime verification. Device testing remains pending.

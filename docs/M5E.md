# M5E — Interactive local photo viewer

Version: `0.5.4-m5e` (`versionCode=15`).

## Implemented
- Pinch to zoom on local images, with scale between 1× and 4×.
- Double-tap a photo to toggle between 1× and 2×.
- Zoom in/out and reset buttons; drag to pan enlarged photos with bounded translation.
- Horizontal page swiping is disabled while the active photo is enlarged, to avoid navigating unexpectedly during panning; reset to 1× to swipe again.
- Scale and offset reset on page change. Videos retain the existing active-page-only playback behavior.
- The transformation is presentation-only: media bytes and metadata are not changed.
- Five JVM tests verify zoom bounds, reset, malformed gestures, and viewport behavior.

## Device checks
1. Open a permitted folder with several JPG/PNG files and MP4 videos.
2. Pinch with two fingers, then drag the enlarged photo; verify the photo cannot be panned entirely off-screen.
3. Double-tap to toggle 1×/2×, test +/- controls and reset.
4. Swipe to another photo from 1×; confirm the zoom resets after page change.
5. Enter and leave the video page; confirm existing playback stops on page exit.
6. Repeat in dark and light modes; verify controls remain usable.
7. Test large files on the real device for memory pressure and gesture responsiveness.

## Not yet verified
Android CI compiles and tests the pure zoom logic but cannot verify physical touch behavior, screen density differences or real-world performance. A stable signed release is still dependent on privately configured signing secrets.

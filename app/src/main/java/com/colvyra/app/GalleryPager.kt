package com.colvyra.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Full-screen, swipeable local-media viewer. Files remain in their original locations.
 * The candidate list comes from the currently visible local folder or collection.
 * Videos are mounted only on the active page, stopping playback when the page leaves.
 */
@Composable
internal fun GalleryPager(initial: Entry, candidates: List<Entry>, loader: ImageLoader, dismiss: () -> Unit) {
    val context = LocalContext.current
    val items = remember(initial.uri, candidates) {
        orderedGalleryItems(initial, candidates, { it.media }, { it.uri.toString() })
    }

    key(initial.uri) {
        val initialPage = remember(items, initial.uri) {
            items.indexOfFirst { it.uri == initial.uri }.coerceAtLeast(0)
        }
        val pager = rememberPagerState(initialPage = initialPage, pageCount = { items.size })
        val scope = rememberCoroutineScope()
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pager.currentPage) { zoomed = false }
        val active = items[pager.currentPage.coerceIn(0, items.lastIndex)]

        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(active.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(active.uri, active.mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (_: Exception) {
                                Toast.makeText(context, "Нет приложения для открытия файла", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Открыть в…") }
                        IconButton(onClick = { shareMedia(context, active) }) {
                            Icon(Icons.Outlined.Share, "Поделиться файлом")
                        }
                        IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть") }
                    }
                    HorizontalPager(
                        state = pager,
                        userScrollEnabled = !zoomed,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) { page ->
                        val item = items[page]
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (item.mime.startsWith("video/") && page == pager.currentPage) {
                                key(item.uri) {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                val controls = MediaController(ctx)
                                                setMediaController(controls)
                                                controls.setAnchorView(this)
                                                setVideoURI(item.uri)
                                                setOnPreparedListener { start() }
                                                setOnErrorListener { _, _, _ ->
                                                    Toast.makeText(ctx, "Не удалось воспроизвести видео", Toast.LENGTH_LONG).show()
                                                    true
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        onRelease = { it.stopPlayback() }
                                    )
                                }
                            } else if (page == pager.currentPage && item.mime.startsWith("image/")) {
                                ZoomablePhoto(item, loader) { zoomed = it }
                            } else {
                                AsyncImage(
                                    model = item.uri,
                                    imageLoader = loader,
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(
                            enabled = pager.currentPage > 0,
                            onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
                        ) { Icon(Icons.Outlined.ChevronLeft, "Предыдущий файл") }
                        Text("${pager.currentPage + 1} / ${items.size}", style = MaterialTheme.typography.labelLarge)
                        IconButton(
                            enabled = pager.currentPage < items.lastIndex,
                            onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
                        ) { Icon(Icons.Outlined.ChevronRight, "Следующий файл") }
                    }
                }
            }
        }
    }
}

/** Gesture-driven image viewing; regular 1x swipes remain handled by the parent pager. */
@Composable
private fun ZoomablePhoto(item: Entry, loader: ImageLoader, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    var viewport by remember(item.uri) { mutableStateOf(IntSize.Zero) }

    val gestures = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = GalleryZoomMath.scale(scale, zoomChange)
        val bounded = GalleryZoomMath.pan(
            offset.x, offset.y, panChange.x, panChange.y,
            viewport.width, viewport.height, newScale
        )
        scale = newScale
        offset = Offset(bounded.first, bounded.second)
    }
    val isZoomed = scale > 1.01f
    LaunchedEffect(isZoomed) { onZoomChanged(isZoomed) }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { viewport = it }
            .transformable(state = gestures, canPan = { scale > 1.01f })
            .pointerInput(item.uri) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1.01f) 1f else 2f
                    offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = item.uri,
            imageLoader = loader,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            contentScale = ContentScale.Fit
        )
    }
}

private fun shareMedia(context: Context, item: Entry) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            clipData = ClipData.newRawUri(item.name, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
    } catch (_: Exception) {
        Toast.makeText(context, "Не удалось поделиться файлом", Toast.LENGTH_LONG).show()
    }
}

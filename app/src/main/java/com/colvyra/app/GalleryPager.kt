package com.colvyra.app

import android.content.Intent
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
        val active = items[pager.currentPage.coerceIn(0, items.lastIndex)]
        var zoom by remember { mutableStateOf(GalleryZoom()) }
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(pager.currentPage) { zoom = GalleryZoom() }
        val isPhoto = active.mime.startsWith("image/")

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
                        IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Закрыть") }
                    }
                    HorizontalPager(
                        state = pager,
                        userScrollEnabled = zoom.scale <= 1f,
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
                            } else if (item.mime.startsWith("image/")) {
                                AsyncImage(
                                    model = item.uri,
                                    imageLoader = loader,
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize().clipToBounds()
                                        .onSizeChanged { if (page == pager.currentPage) viewport = it }
                                        .pointerInput(item.uri) {
                                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                                if (page == pager.currentPage) {
                                                    zoom = constrainGalleryZoom(
                                                        zoom, zoom.scale * gestureZoom,
                                                        viewport.width, viewport.height,
                                                        pan.x, pan.y
                                                    )
                                                }
                                            }
                                        }
                                        .pointerInput(item.uri) {
                                            detectTapGestures(onDoubleTap = {
                                                if (page == pager.currentPage) {
                                                    zoom = if (zoom.scale > 1f) GalleryZoom()
                                                    else constrainGalleryZoom(
                                                        GalleryZoom(), 2f, viewport.width, viewport.height
                                                    )
                                                }
                                            })
                                        }
                                        .graphicsLayer {
                                            if (page == pager.currentPage) {
                                                scaleX = zoom.scale
                                                scaleY = zoom.scale
                                                translationX = zoom.offsetX
                                                translationY = zoom.offsetY
                                            }
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text("Предпросмотр недоступен", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (isPhoto) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(
                                enabled = zoom.scale > 1f,
                                onClick = {
                                    zoom = constrainGalleryZoom(
                                        zoom, zoom.scale - 0.5f, viewport.width, viewport.height
                                    )
                                }
                            ) { Icon(Icons.Outlined.ZoomOut, "Уменьшить") }
                            Text("${zoom.scale}×", style = MaterialTheme.typography.labelLarge)
                            IconButton(
                                enabled = zoom.scale < 4f,
                                onClick = {
                                    zoom = constrainGalleryZoom(
                                        zoom, zoom.scale + 0.5f, viewport.width, viewport.height
                                    )
                                }
                            ) { Icon(Icons.Outlined.ZoomIn, "Увеличить") }
                            IconButton(
                                enabled = zoom.scale > 1f,
                                onClick = { zoom = GalleryZoom() }
                            ) { Icon(Icons.Outlined.RestartAlt, "Сбросить масштаб") }
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

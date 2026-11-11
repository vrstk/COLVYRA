package com.colvyra.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FOLDER = "vnd.android.document/directory"
private fun Context.message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

private fun readReference(context: Context, uri: Uri, folder: Boolean): LibraryReference {
    val cr = context.contentResolver
    cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val normalized = if (folder) DocumentFile.fromTreeUri(context, uri)?.uri ?: error("Папка недоступна") else uri
    var name = normalized.lastPathSegment ?: "Без названия"
    cr.query(normalized, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) name = it.getString(0) ?: name
    }
    return LibraryReference(normalized.toString(), name, if (folder) FOLDER else cr.getType(normalized) ?: "application/octet-stream")
}

@Composable
internal fun AddReferenceDialog(dao: CollectionDao, ref: LibraryReference, dismiss: () -> Unit) {
    val collections by dao.observeCollections().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var create by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun save(id: String) {
        if (busy) return
        busy = true
        scope.launch {
            try { dao.addReference(id, ref); context.message("Добавлено в коллекцию"); dismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { context.message("Не удалось добавить: ${e.localizedMessage}") }
            finally { busy = false }
        }
    }
    if (create) CollectionEditor(null, { create = false }) { record ->
        scope.launch {
            try { dao.insert(record); dao.addReference(record.id, ref); context.message("Коллекция создана"); dismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { context.message("Не удалось сохранить") }
        }
    } else AlertDialog(onDismissRequest = dismiss, title = { Text("Добавить в коллекцию") },
        text = {
            Column {
                Text(ref.name, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(collections, key = { it.id }) { c ->
                        ListItem(headlineContent = { Text(c.name) }, modifier = Modifier.clickable(enabled = !busy) { save(c.id) })
                    }
                }
                TextButton(onClick = { create = true }, enabled = !busy) { Text("Новая коллекция") }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text("Закрыть") } })
}

@Composable
internal fun CollectionScreen(dao: CollectionDao, openFolder: (Uri) -> Unit, openCloud: (LibraryReference) -> Unit, openWeb: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collections by dao.observeCollections().collectAsState(initial = emptyList())
    val entries by dao.observeEntries().collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = collections.find { it.id == selectedId }
    var query by remember(selectedId) { mutableStateOf("") }
    var onlyFavorites by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CollectionRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteCollection by remember { mutableStateOf<CollectionRecord?>(null) }
    var chosen by remember { mutableStateOf<CollectionEntry?>(null) }
    var editEntry by remember { mutableStateOf<CollectionEntry?>(null) }
    var removeEntry by remember { mutableStateOf<CollectionEntry?>(null) }
    var viewer by remember { mutableStateOf<Entry?>(null) }
    var busy by remember { mutableStateOf(false) }
    val loader = remember(context) { LocalMediaImageLoader.get(context) }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { context.message(e.localizedMessage ?: "Операция не выполнена") }
            finally { busy = false }
        }
    }
    fun importUris(id: String, uris: List<Uri>, folder: Boolean) {
        work {
            var count = 0
            for (uri in uris) {
                try {
                    val ref = withContext(Dispatchers.IO) { readReference(context, uri, folder) }
                    dao.addReference(id, ref)
                    count++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            context.message("Добавлено: $count из ${uris.size}")
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedId?.let { importUris(it, uris, false) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) selectedId?.let { importUris(it, listOf(uri), true) }
    }
    fun open(e: CollectionEntry) {
        if (e.mime == "text/uri-list" && safeWebUrl(e.uri) != null) { openWeb(e.uri); return }
        if (isCloudReference(e.uri)) { openCloud(LibraryReference(e.uri,e.name,e.mime)); return }
        work {
            val uri = Uri.parse(e.uri)
            val available = withContext(Dispatchers.IO) {
                try { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true }
                catch (_: Exception) { false }
            }
            if (!available) { context.message("Оригинал недоступен. Проверьте источник или добавьте файл заново."); return@work }
            when {
                e.mime == FOLDER -> {
                    if (DocumentsContract.isTreeUri(uri)) openFolder(uri)
                    else context.message("Для открытия папки добавьте её через выбор папки")
                }
                e.mime.startsWith("image/") || e.mime.startsWith("video/") -> viewer = Entry(uri, e.name, e.mime, 0, 0, 0)
                else -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, e.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) { context.message("Нет приложения для открытия файла") }
            }
        }
    }

    if (selected == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Мои коллекции", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onlyFavorites = !onlyFavorites }) { Icon(Icons.Outlined.Star, "Только избранное", tint = if (onlyFavorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
            IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "Новая коллекция") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Поиск коллекций") })
        val visible = collections.filter { (!onlyFavorites || it.favorite) && (it.name + " " + it.description + " " + it.tags).contains(query, true) }
        if (visible.isEmpty()) Text("Создайте коллекцию или измените фильтр", Modifier.padding(vertical = 12.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            gridItems(visible, key = { it.id }) { c ->
                Card(onClick = { selectedId = c.id }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.4f), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.CollectionsBookmark, null, Modifier.size(48.dp))
                        c.coverUri?.takeUnless { isCloudReference(it) }?.let { AsyncImage(model = Uri.parse(it), imageLoader = loader, contentDescription = c.name,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (c.favorite) Icon(Icons.Outlined.Star, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("${entries.count { it.collectionId == c.id }} материалов", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selectedId = null }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
            Text(selected.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2)
            IconButton(onClick = { work { dao.update(selected.copy(favorite = !selected.favorite)) } }) { Icon(Icons.Outlined.Star, "Избранное", tint = if (selected.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
            IconButton(onClick = { editor = selected }) { Icon(Icons.Outlined.Edit, "Редактировать") }
            IconButton(onClick = { deleteCollection = selected }) { Icon(Icons.Outlined.Delete, "Удалить коллекцию") }
        }
        if (selected.description.isNotBlank()) Text(selected.description)
        if (selected.tags.isNotBlank()) Text(selected.tags, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Файлы") }
            OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy) { Text("Папка") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Поиск по имени и тегам") })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val visible = entries.filter { it.collectionId == selected.id && (it.name + " " + it.tags).contains(query, true) }
        if (visible.isEmpty()) Text("Здесь будут ссылки на оригиналы", Modifier.padding(vertical = 12.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(visible, key = { it.uri }) { e ->
                ListItem(headlineContent = { Text(e.name, maxLines = 2) },
                    supportingContent = { Text(if (e.tags.isNotBlank()) e.tags else if (e.mime == FOLDER) "Ссылка на папку" else e.mime) },
                    leadingContent = {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Icon(if (e.mime == FOLDER) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null)
                            if (!isCloudReference(e.uri) && (e.mime.startsWith("image/") || e.mime.startsWith("video/")))
                                AsyncImage(model = Uri.parse(e.uri), imageLoader = loader, contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    },
                    trailingContent = { IconButton(onClick = { chosen = e }) { Icon(Icons.Outlined.MoreVert, "Действия") } },
                    modifier = Modifier.clickable { open(e) })
                HorizontalDivider()
            }
        }
    }
    if (creating || editor != null) CollectionEditor(editor, { creating = false; editor = null }) { record ->
        val isNew = creating
        creating = false; editor = null
        work { if (isNew) { dao.insert(record); selectedId = record.id } else dao.update(record) }
    }
    deleteCollection?.let { c ->
        AlertDialog(onDismissRequest = { deleteCollection = null }, title = { Text("Удалить коллекцию?") },
            text = { Text("Будут удалены только коллекция и ссылки. Исходные файлы останутся на месте.") },
            confirmButton = { TextButton(onClick = { deleteCollection = null; work { dao.deleteCollection(c.id); selectedId = null } }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteCollection = null }) { Text("Отмена") } })
    }
    chosen?.let { e ->
        AlertDialog(onDismissRequest = { chosen = null }, title = { Text(e.name) }, text = {
            Column {
                TextButton(onClick = { chosen = null; open(e) }) { Text("Открыть") }
                TextButton(onClick = { chosen = null; editEntry = e }) { Text("Теги") }
                TextButton(onClick = { chosen = null; work { dao.updateEntry(e.copy(favorite = !e.favorite)) } }) { Text(if (e.favorite) "Убрать из избранного" else "В избранное") }
                if (!isCloudReference(e.uri) && (e.mime.startsWith("image/") || e.mime.startsWith("video/"))) TextButton(onClick = {
                    chosen = null; work { dao.find(e.collectionId)?.let { dao.update(it.copy(coverUri = e.uri)) } }
                }) { Text("Сделать обложкой") }
                TextButton(onClick = { chosen = null; removeEntry = e }) { Text("Убрать из коллекции") }
            }
        }, confirmButton = { TextButton(onClick = { chosen = null }) { Text("Закрыть") } })
    }
    editEntry?.let { e ->
        var tags by remember(e) { mutableStateOf(e.tags) }
        AlertDialog(onDismissRequest = { editEntry = null }, title = { Text("Теги материала") },
            text = { OutlinedTextField(tags, { tags = it }, label = { Text("Теги через запятую") }) },
            confirmButton = { TextButton(onClick = { editEntry = null; work { dao.updateEntry(e.copy(tags = tags.trim())) } }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editEntry = null }) { Text("Отмена") } })
    }
    removeEntry?.let { e ->
        AlertDialog(onDismissRequest = { removeEntry = null }, title = { Text("Убрать ссылку?") },
            text = { Text("Оригинал не будет удалён.") },
            confirmButton = { TextButton(onClick = { removeEntry = null; work { dao.removeReference(e.collectionId, e.uri) } }) { Text("Убрать") } },
            dismissButton = { TextButton(onClick = { removeEntry = null }) { Text("Отмена") } })
    }
    viewer?.let { current ->
        val media = entries.filter { it.collectionId == selected?.id && (it.name + " " + it.tags).contains(query, true) &&
            !isCloudReference(it.uri) && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) &&
                Uri.parse(it.uri).scheme == "content"
        }.map { Entry(Uri.parse(it.uri), it.name, it.mime, 0, 0, 0) }
        GalleryPager(current, media, loader) { viewer = null }
    }
}

@Composable
private fun CollectionEditor(original: CollectionRecord?, dismiss: () -> Unit, save: (CollectionRecord) -> Unit) {
    var name by remember(original) { mutableStateOf(original?.name ?: "") }
    var description by remember(original) { mutableStateOf(original?.description ?: "") }
    var tags by remember(original) { mutableStateOf(original?.tags ?: "") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (original == null) "Новая коллекция" else "Редактировать коллекцию") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Описание") }, maxLines = 3)
            OutlinedTextField(tags, { tags = it }, label = { Text("Теги через запятую") })
        } },
        confirmButton = { TextButton(onClick = { save((original ?: CollectionRecord(name = name.trim())).copy(name = name.trim(), description = description.trim(), tags = tags.trim())) }, enabled = name.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

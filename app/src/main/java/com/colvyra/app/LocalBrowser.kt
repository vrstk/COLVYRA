package com.colvyra.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

internal data class Entry(val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int) {
    val folder get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    val media get() = mime.startsWith("image/") || mime.startsWith("video/")
    val canRename get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
    val canDelete get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
}
private data class FolderData(val title: String, val entries: List<Entry>, val writable: Boolean)
private data class Transfer(val entry: Entry, val move: Boolean)
private enum class Order(val label: String) { NAME("Имя"), DATE("Дата"), SIZE("Размер") }

private fun listFolder(context: Context, directory: Uri): FolderData {
    val cr = context.contentResolver
    val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
    var title = "Папка"
    var writable = false
    cr.query(directory, cols, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            title = c.getString(1) ?: title
            writable = c.getInt(5) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        }
    }
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory))
    val rows = mutableListOf<Entry>()
    cr.query(children, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0)
            rows.add(Entry(DocumentsContract.buildDocumentUriUsingTree(directory, id),
                c.getString(1) ?: "Без названия", c.getString(2) ?: "application/octet-stream",
                c.getLong(3), c.getLong(4), c.getInt(5)))
        }
    } ?: error("Источник не вернул список файлов")
    return FolderData(title, rows, writable)
}
private fun externalOpen(context: Context, e: Entry) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(e.uri, e.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (_: Exception) { Toast.makeText(context, "Нет приложения для открытия файла", Toast.LENGTH_SHORT).show() }
}
private fun transferFile(context: Context, t: Transfer, dest: Uri): String {
    val cr = context.contentResolver
    val folder = DocumentFile.fromTreeUri(context, dest)?.uri ?: error("Папка недоступна")
    if (listFolder(context, folder).entries.any { it.name == t.entry.name }) error("Файл с таким именем уже существует")
    val created = DocumentsContract.createDocument(cr, folder, t.entry.mime, t.entry.name)
        ?: error("Не удалось создать файл")
    try {
        val input = cr.openInputStream(t.entry.uri) ?: error("Не удалось открыть исходный файл")
        input.use { ins ->
            val output = cr.openOutputStream(created, "w") ?: error("Не удалось открыть файл назначения")
            output.use { ins.copyTo(it) }
        }
    } catch (e: Exception) {
        runCatching { DocumentsContract.deleteDocument(cr, created) }
        throw e
    }
    if (t.move) {
        val deleted = runCatching { DocumentsContract.deleteDocument(cr, t.entry.uri) }.getOrDefault(false)
        if (!deleted) return "Копия создана, оригинал не удалён"
    }
    return if (t.move) "Файл перемещён" else "Файл скопирован"
}

@Composable
internal fun LocalBrowser(directory: Uri, mediaOnly: Boolean, revision: Int, openDirectory: (Uri) -> Unit, refresh: () -> Unit, addToCollection: (LibraryReference) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("colvyra", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var data by remember(directory) { mutableStateOf<FolderData?>(null) }
    var failure by remember(directory) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember(directory) { mutableStateOf("") }
    var grid by remember(mediaOnly) { mutableStateOf(prefs.getBoolean("grid_$mediaOnly", mediaOnly)) }
    var order by remember { mutableStateOf(Order.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    var viewer by remember { mutableStateOf<Entry?>(null) }
    var rename by remember { mutableStateOf<Entry?>(null) }
    var delete by remember { mutableStateOf<Entry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<Transfer?>(null) }
    val loader = remember(context) {
        ImageLoader.Builder(context.applicationContext).components {
            add(VideoFrameDecoder.Factory())
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }

    fun work(action: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) { action() }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.localizedMessage ?: "Операция не выполнена", Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = transfer
        transfer = null
        if (uri != null && pending != null) work { transferFile(context, pending, uri) }
    }

    LaunchedEffect(directory, revision) {
        failure = null
        try { data = withContext(Dispatchers.IO) { listFolder(context, directory) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { data = null; failure = e.localizedMessage ?: "Нет доступа к папке" }
    }
    val visible = remember(data, mediaOnly, query, order) {
        val comparator = when (order) {
            Order.NAME -> compareBy<Entry> { it.name.lowercase() }
            Order.DATE -> compareByDescending<Entry> { it.modified }.thenBy { it.name.lowercase() }
            Order.SIZE -> compareByDescending<Entry> { it.size }.thenBy { it.name.lowercase() }
        }
        data?.entries.orEmpty().filter { (!mediaOnly || it.folder || it.media) && it.name.contains(query, true) }
            .sortedWith(compareBy<Entry> { !it.folder }.then(comparator))
    }
    fun open(e: Entry) {
        if (e.folder) openDirectory(e.uri)
        else if (e.media) viewer = e else externalOpen(context, e)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(data?.title ?: "Папка", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = {
            grid = !grid
            prefs.edit().putBoolean("grid_$mediaOnly", grid).apply()
        }) { Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, "Список или плитка") }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.Sort, "Сортировка") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                Order.entries.forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { order = o; sortMenu = false }) }
            }
        }
        if (data?.writable == true) IconButton(onClick = { newFolder = true }, enabled = !busy) {
            Icon(Icons.Outlined.CreateNewFolder, "Новая папка")
        }
    }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Поиск в папке") })
    if (busy || (data == null && failure == null)) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    failure?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
    if (data != null) Text("Элементов: ${visible.size}", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    if (grid) {
        LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            gridItems(visible, key = { it.uri.toString() }) { e ->
                Card {
                    Column(Modifier.clickable { open(e) }.padding(8.dp)) {
                        EntryPreview(e, loader, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (e.folder) "Папка" else formatBytes(e.size), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { selected = e }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "Действия") }
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(visible, key = { it.uri.toString() }) { e ->
                ListItem(headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(if (e.folder) "Папка" else formatBytes(e.size)) },
                    leadingContent = { EntryPreview(e, loader, Modifier.size(52.dp)) },
                    trailingContent = { IconButton(onClick = { selected = e }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "Действия") } },
                    modifier = Modifier.clickable { open(e) })
                HorizontalDivider()
            }
        }
    }
    selected?.let { e ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(e.name) }, text = {
            Column {
                TextButton(onClick = { selected = null; open(e) }) { Text("Открыть") }
                TextButton(onClick = { selected = null; addToCollection(LibraryReference(e.uri.toString(), e.name, e.mime)) }) { Text("Добавить в коллекцию") }
                if (e.canRename) TextButton(onClick = { selected = null; rename = e }) { Text("Переименовать") }
                if (!e.folder) {
                    TextButton(onClick = { selected = null; transfer = Transfer(e, false); destination.launch(null) }) { Text("Копировать в папку") }
                    if (e.canDelete) TextButton(onClick = { selected = null; transfer = Transfer(e, true); destination.launch(null) }) { Text("Переместить в папку") }
                }
                if (e.canDelete) TextButton(onClick = { selected = null; delete = e }) { Text("Удалить оригинал", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Закрыть") } })
    }
    if (newFolder) NameDialog("Новая папка", "", { newFolder = false }) { name ->
        newFolder = false
        work {
            if (DocumentsContract.createDocument(context.contentResolver, directory, DocumentsContract.Document.MIME_TYPE_DIR, name) == null) error("Папка не создана")
            "Папка создана"
        }
    }
    rename?.let { e -> NameDialog("Переименовать", e.name, { rename = null }) { name ->
        rename = null
        if (name != e.name) work {
            if (DocumentsContract.renameDocument(context.contentResolver, e.uri, name) == null) error("Переименование не выполнено")
            "Имя изменено"
        }
    } }
    delete?.let { e -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Удалить оригинал?") },
        text = { Text(if (e.folder) "Папка «${e.name}» и её содержимое могут быть удалены без возможности восстановления." else "Файл «${e.name}» будет удалён из источника.") },
        confirmButton = { TextButton(onClick = {
            delete = null
            work { if (!DocumentsContract.deleteDocument(context.contentResolver, e.uri)) error("Удаление не выполнено"); "Удалено" }
        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } }) }
    viewer?.let { e -> GalleryPager(e, visible.filter { it.media }, loader) { viewer = null } }
}

@Composable private fun EntryPreview(e: Entry, loader: ImageLoader, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (e.media) {
            Icon(if (e.mime.startsWith("video/")) Icons.Outlined.Movie else Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.primary)
            AsyncImage(model = e.uri, imageLoader = loader, contentDescription = e.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else Icon(if (e.folder) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
    }
}
@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    val valid = name.trim().isNotEmpty() && name.trim() !in listOf(".", "..") && !name.contains('/') && !name.contains('\\')
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { submit(name.trim()) }, enabled = valid) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n Б"
    n < 1024 * 1024 -> "${n / 1024} КБ"
    else -> "${n / (1024 * 1024)} МБ"
}

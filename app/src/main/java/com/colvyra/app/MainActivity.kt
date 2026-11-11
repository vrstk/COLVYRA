package com.colvyra.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private enum class Screen(val title: String) {
    FILES("Файлы"), GALLERY("Галерея"), COLLECTIONS("Коллекции"),
    SOURCES("Источники"), WEBSITES("Сайты")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ColvyraApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColvyraApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("colvyra", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(prefs.getString("themeMode", "system") ?: "system") }
    val darkTheme = when (themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    var screen by remember { mutableStateOf(Screen.FILES) }
    val savedRoot = remember { prefs.getString("rootUri", null) }
    val firstFolder = remember(savedRoot) {
        savedRoot?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.uri }.getOrNull() }
    }
    var folders by remember { mutableStateOf(if (firstFolder == null) emptyList() else listOf(firstFolder)) }
    var revision by remember { mutableIntStateOf(0) }
    var collections by remember { mutableStateOf(readCollections(prefs)) }
    var newCollection by remember { mutableStateOf("") }
    var showNewCollection by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                Toast.makeText(context, "Постоянное разрешение не выдано", Toast.LENGTH_SHORT).show()
            }
            val root = DocumentFile.fromTreeUri(context, uri)?.uri
            folders = if (root == null) emptyList() else listOf(root)
            prefs.edit().putString("rootUri", uri.toString()).apply()
            revision++
        }
    }
    BackHandler(enabled = (screen == Screen.FILES || screen == Screen.GALLERY) && folders.size > 1) {
        folders = folders.dropLast(1)
    }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("COLVYRA") }, actions = {
                    IconButton(onClick = {
                        themeMode = when (themeMode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
                        prefs.edit().putString("themeMode", themeMode).apply()
                    }) {
                        Icon(if (darkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = "Тема: $themeMode. Нажмите для переключения")
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    Screen.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == screen,
                            onClick = { screen = tab },
                            icon = {
                                Icon(when (tab) {
                                    Screen.FILES -> Icons.Outlined.Folder
                                    Screen.GALLERY -> Icons.Outlined.PhotoLibrary
                                    Screen.COLLECTIONS -> Icons.Outlined.CollectionsBookmark
                                    Screen.SOURCES -> Icons.Outlined.Cloud
                                    Screen.WEBSITES -> Icons.Outlined.Language
                                }, contentDescription = null)
                            },
                            label = { Text(tab.title, maxLines = 1) }
                        )
                    }
                }
            }
        ) { inset ->
            Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 12.dp)) {
                when (screen) {
                    Screen.FILES, Screen.GALLERY -> {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { picker.launch(null) }) { Text("Выбрать папку") }
                            if (folders.size > 1) TextButton(onClick = { folders = folders.dropLast(1) }) {
                                Text("Назад")
                            }
                            IconButton(onClick = { revision++ }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить")
                            }
                        }
                        if (folders.isEmpty()) {
                            Text("Выберите папку. COLVYRA получит доступ только к разрешённым файлам.")
                        } else {
                            LocalBrowser(
                                directory = folders.last(),
                                mediaOnly = screen == Screen.GALLERY,
                                revision = revision,
                                openDirectory = { folders = folders + it },
                                refresh = { revision++ }
                            )
                        }
                    }
                    Screen.COLLECTIONS -> {
                        Text("Мои коллекции", style = MaterialTheme.typography.titleLarge)
                        Text("Пока сохраняются только названия. Привязка файлов появится в M2.")
                        Button(onClick = { showNewCollection = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text("Новая коллекция")
                        }
                        collections.forEach { name ->
                            ListItem(headlineContent = { Text(name) },
                                leadingContent = { Icon(Icons.Outlined.CollectionsBookmark, null) })
                        }
                    }
                    Screen.SOURCES -> {
                        Text("Источники", style = MaterialTheme.typography.titleLarge)
                        ListItem(headlineContent = { Text("Память Android") },
                            supportingContent = { Text(if (folders.isEmpty()) "Выбрать папку" else "Подключена") },
                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            modifier = Modifier.clickable { picker.launch(null) })
                        Text("Mail.ru Cloud, WebDAV, Google Drive, Photos и соцсети — в дорожной карте.")
                    }
                    Screen.WEBSITES -> {
                        Text("Сайты", style = MaterialTheme.typography.titleLarge)
                        Text("Встроенный браузер появится в M4. Сейчас откроется внешний браузер.")
                        Button(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
                            } catch (_: Exception) {
                                Toast.makeText(context, "Не найден браузер", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Открыть браузер") }
                    }
                }
            }
        }
        if (showNewCollection) {
            AlertDialog(
                onDismissRequest = { showNewCollection = false },
                title = { Text("Новая коллекция") },
                text = { OutlinedTextField(value = newCollection, onValueChange = { newCollection = it },
                    label = { Text("Название") }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newCollection.trim()
                        if (name.isNotEmpty() && name !in collections) {
                            collections = collections + name
                            writeCollections(prefs, collections)
                        }
                        newCollection = ""
                        showNewCollection = false
                    }) { Text("Создать") }
                },
                dismissButton = { TextButton(onClick = { showNewCollection = false }) { Text("Отмена") } }
            )
        }
    }
}

private fun readCollections(prefs: SharedPreferences): List<String> =
    try {
        val arr = JSONArray(prefs.getString("collections", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

private fun writeCollections(prefs: SharedPreferences, names: List<String>) {
    val arr = JSONArray()
    names.forEach(arr::put)
    prefs.edit().putString("collections", arr.toString()).apply()
}

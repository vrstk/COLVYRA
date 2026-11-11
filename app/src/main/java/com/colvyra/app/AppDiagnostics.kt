package com.colvyra.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Deliberately excludes file names, paths, account names, media URLs and tokens.
 * The APK signing fingerprint is a public certificate identifier, not a secret key.
 */
internal data class DiagnosticsSnapshot(
    val versionName: String,
    val versionCode: Long,
    val debugBuild: Boolean,
    val androidSdk: Int,
    val signingCertificateSha256: String,
    val selectedRoot: Boolean,
    val rootReadable: Boolean,
    val rootWritable: Boolean,
    val persistedGrantCount: Int,
    val collectionCount: Int,
    val referenceCount: Int
) {
    fun supportText(): String = listOf(
        "COLVYRA diagnostics",
        "Version: $versionName ($versionCode)",
        "Build: " + if (debugBuild) "debug" else "release",
        "Android SDK: $androidSdk",
        "Installed APK certificate SHA-256: $signingCertificateSha256",
        "Folder selected: $selectedRoot",
        "Folder read permission: $rootReadable",
        "Folder write permission: $rootWritable",
        "Persisted Android grants: $persistedGrantCount",
        "Collections: $collectionCount",
        "References: $referenceCount"
    ).joinToString("\n")
}

internal object DiagnosticsReader {
    @Suppress("DEPRECATION")
    suspend fun read(context: Context, dao: CollectionDao): DiagnosticsSnapshot =
        withContext(Dispatchers.IO) {
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, flags)
            val certificate = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                packageInfo.signatures?.firstOrNull()
            }
            val fingerprint = certificate?.let { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            } ?: "Недоступно"

            val selectedRoot = context.getSharedPreferences("colvyra", Context.MODE_PRIVATE)
                .getString("rootUri", null)
            val grants = context.contentResolver.persistedUriPermissions
            val grant = grants.firstOrNull { it.uri.toString() == selectedRoot }
            val (collections, references) = dao.snapshot()

            DiagnosticsSnapshot(
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
                    else packageInfo.versionCode.toLong(),
                debugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                androidSdk = Build.VERSION.SDK_INT,
                signingCertificateSha256 = fingerprint,
                selectedRoot = selectedRoot != null,
                rootReadable = grant?.isReadPermission == true,
                rootWritable = grant?.isWritePermission == true,
                persistedGrantCount = grants.size,
                collectionCount = collections.size,
                referenceCount = references.size
            )
        }
}

@Composable
internal fun AppDiagnosticsPanel(dao: CollectionDao) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<DiagnosticsSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(true) }

    LaunchedEffect(dao, refresh) {
        reading = true
        error = null
        try {
            snapshot = DiagnosticsReader.read(context, dao)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            snapshot = null
            error = "Не удалось получить диагностические сведения"
        } finally {
            reading = false
        }
    }

    Text("Диагностика приложения", style = MaterialTheme.typography.titleMedium)
    Text(
        "Здесь можно проверить установленную версию, подпись APK и сохранённые разрешения. " +
            "Пароли, адреса файлов и содержимое коллекций в отчёт не включаются.",
        style = MaterialTheme.typography.bodySmall
    )
    if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    snapshot?.let { info ->
        Text("Версия: ${info.versionName} (${info.versionCode})")
        Text("Тип: ${if (info.debugBuild) "debug" else "release"} · Android SDK ${info.androidSdk}")
        Text("Коллекций: ${info.collectionCount} · ссылок: ${info.referenceCount}")
        Text(
            if (!info.selectedRoot) "Папка устройства ещё не выбрана"
            else if (info.rootReadable) "Доступ к выбранной папке сохранён"
            else "Доступ к папке не сохранён — выберите её повторно",
            color = if (info.selectedRoot && !info.rootReadable) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
        )
        Text("Разрешений Android: ${info.persistedGrantCount}")
        Text("Отпечаток сертификата APK (SHA-256):", style = MaterialTheme.typography.labelMedium)
        Text(info.signingCertificateSha256, style = MaterialTheme.typography.bodySmall)
        Text(
            "Чтобы новая APK установилась поверх старой, необходима совместимая подпись. " +
                "При смене сертификата Android может отклонить обновление.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("COLVYRA diagnostics", info.supportText()))
            Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
        }) { Text("Скопировать безопасный отчёт") }
    }
    TextButton(onClick = { refresh++ }, enabled = !reading) { Text("Обновить диагностику") }
}

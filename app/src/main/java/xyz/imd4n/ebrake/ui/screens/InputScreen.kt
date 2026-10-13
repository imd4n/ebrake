package xyz.imd4n.ebrake.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import xyz.imd4n.ebrake.transcode.TranscodeUiState
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.gallery.VideoGallery
import xyz.imd4n.ebrake.ui.gallery.VideoItem

/**
 * Ввод: галерея роликов с телефона (вместо одиночной кнопки пикера) + карточка
 * с метаданными файла, выбранного на сравнение.
 *
 * Рядом с галереей остались два ручных пути — SAF-пикер (без разрешений, выручает
 * с файлами, которых нет в MediaStore) и выбор папки: тогда галерея показывает
 * только её содержимое, например Downloads.
 *
 * Отметка мультивыборная: ролик №1 идёт на «Правку» под сравнение трёх уровней,
 * остальные кнопкой «Импорт» сразу в очередь с текущим уровнем качества.
 */
@Composable
fun InputScreen(vm: TranscodeViewModel, state: TranscodeUiState) {
    val context = LocalContext.current

    // MediaStore читается только с разрешением; на 13+ это READ_MEDIA_VIDEO,
    // ниже — старое READ_EXTERNAL_STORAGE. Выбранная папка идёт через SAF и
    // разрешения не требует, поэтому проверка нужна только для MediaStore.
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, mediaPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // Ручной выбор: работает всегда, даже без разрешения на галерею.
    val manualPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        // Долгосрочный доступ: задания очереди открывают файлы позже,
        // уже после возврата пикера.
        val picked = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            uri to displayName(context, uri)
        }
        vm.addToSelection(picked)
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { vm.setInputFolder(it) } }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Ввод",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Папка") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Источник: ${state.inputFolderLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.inputFolderChosen) {
                // Вернуться ко всем роликам, не заставляя искать папку заново.
                TextButton(onClick = { vm.setInputFolder(null) }) { Text("Все видео") }
            }
        }
        Card(modifier = Modifier.weight(1f)) {
            VideoGallery(
                selection = state.selection.map { it.uri.toString() },
                treeUri = state.inputTreeUri,
                hasPermission = hasPermission,
                onToggle = { item: VideoItem -> vm.toggleSelection(item.uri, item.name) },
                onRequestPermission = { permissionLauncher.launch(mediaPermission) },
                onPickManually = { manualPicker.launch(arrayOf("video/*")) },
                onShowAll = { vm.setInputFolder(null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state.selection.isNotEmpty()) {
            SelectionBar(
                state = state,
                onImport = { vm.importRest() },
                onClear = { vm.clearSelection() },
            )
        }
        if (state.inputUri != null) {
            // Метаданные FFprobe многострочные, а галерее нужна высота:
            // отдаём ему остаток, но не больше экрана.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 80.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Сравнение: ${state.inputName}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.mediaInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Панель мультивыбора: показывает, сколько роликов отмечено и кто из них первый
 * (сравнение), и даёт одной кнопкой отправить остальные в очередь.
 *
 * Однослойная, а не карточка: экран и так втрое урезан галереей, а панель нужна
 * ровно тогда, когда что-то отмечено.
 */
@Composable
private fun SelectionBar(
    state: TranscodeUiState,
    onImport: () -> Unit,
    onClear: () -> Unit,
) {
    val rest = state.selection.size - 1
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Отмечено: ${state.selection.size}" +
                    if (rest > 0) " · в очередь: $rest" else "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "1 — «${state.inputName}» на сравнение",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onImport, enabled = rest > 0) { Text("Импорт: $rest") }
        TextButton(onClick = onClear) { Text("Сбросить") }
    }
}

/** Имя файла из SAF-пикера; без провайдера — просто video. */
private fun displayName(context: android.content.Context, uri: Uri): String {
    var name = "video"
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
    }
    return name
}

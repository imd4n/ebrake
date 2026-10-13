package xyz.imd4n.ebrake.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import xyz.imd4n.ebrake.transcode.DoneRecord
import xyz.imd4n.ebrake.transcode.JobStatus
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeUiState
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.VideoThumbnail
import xyz.imd4n.ebrake.ui.theme.LogTextStyle

@Composable
fun OutputScreen(vm: TranscodeViewModel, state: TranscodeUiState) {
    val context = LocalContext.current
    // Лог свёрнут: 300 строк ffmpeg-шума перекрывали очередь, а нужен он
    // только когда разбираешь, почему задание упало.
    var logsOpen by remember { mutableStateOf(false) }

    // Папка вывода: по умолчанию app-specific dir, её можно заменить
    // на любую из хранилища. Выбор уходит в сервис, он переживает Activity.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> vm.setOutputTree(uri) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Вывод", style = MaterialTheme.typography.headlineSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                // Добавление в очередь во время кодирования безопасно: цикл
                // сервиса подхватит новые задания после текущего.
                onClick = { vm.enqueueSelected() },
                modifier = Modifier.weight(1f),
            ) { Text("В очередь") }
            if (state.queueRunning) {
                OutlinedButton(
                    onClick = { vm.cancelQueue() },
                    modifier = Modifier.weight(1f),
                ) { Text("Стоп") }
            } else {
                // Сравнение и очередь гоняют один движок: FFmpegKit.cancel()
                // глушит все сессии сразу, поэтому вместе их не запускаем.
                OutlinedButton(
                    onClick = { vm.startQueue() },
                    enabled = !state.compareRunning,
                    modifier = Modifier.weight(1f),
                ) { Text("Старт") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.runEngineDiagnostics() }, modifier = Modifier.weight(1f)) {
                Text("Диагностика")
            }
            OutlinedButton(onClick = { vm.clearFinished() }, modifier = Modifier.weight(1f)) {
                Text("Очистить")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Вывод в: ${state.outputLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Папка") }
            if (state.outputTreeChosen) {
                // Вернуть папку приложения, не заставляя искать исходную.
                TextButton(onClick = { vm.setOutputTree(null) }) { Text("Сброс") }
            }
        }
        Text(
            "Очередь: ${state.queue.size}" + if (state.queueRunning) " · идёт кодирование…" else "",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.queue.isEmpty() && state.done.isEmpty()) {
                item {
                    Text(
                        text = "Очередь пуста. Отметь ролики на «Вводе» и нажми «Импорт» " +
                            "или «Взять в очередь» на «Правке».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(state.queue, key = { it.id }) { job ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        // Один и тот же файл часто стоит в очереди трижды (уровни
                        // качества) — по названию не отличить, обложка помогает.
                        VideoThumbnail(uri = job.inputUri)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(job.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${job.preset.engine.shortName} · ${job.status.title}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (job.status == JobStatus.RUNNING) {
                                LinearProgressIndicator(progress = { job.progress01 })
                            }
                            if (job.status == JobStatus.DONE && job.outputPath.isNotEmpty()) {
                                Text(
                                    "${formatBytes(job.outputSizeBytes)}\n${job.outputPath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (state.done.isNotEmpty()) {
                item {
                    DoneQueueHeader(
                        records = state.done,
                        onClear = { vm.clearDone() },
                    )
                }
                items(state.done, key = { "done_${it.id}" }) { record ->
                    DoneRow(record)
                }
            }
            item {
                LogPanel(
                    logs = state.logs,
                    open = logsOpen,
                    onToggle = { logsOpen = !logsOpen },
                )
            }
        }
    }
}

/** Заголовок выполненной очереди: сколько роликов и сколько места сэкономлено. */
@Composable
private fun DoneQueueHeader(
    records: List<DoneRecord>,
    onClear: () -> Unit,
) {
    val saved = records.sumOf { it.savedBytes }
    val inBytes = records.sumOf { it.inputBytes }
    val percent = if (inBytes > 0) (saved * 100 / inBytes).toInt() else 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Выполнено: ${records.size}", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Сэкономлено ${formatBytes(saved)} из ${formatBytes(inBytes)}" +
                    if (percent > 0) " ($percent%)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = onClear) { Text("Сбросить") }
    }
}

/** Строка выполненного: было → стало, экономия и доля от исходника. */
@Composable
private fun DoneRow(record: DoneRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            VideoThumbnail(uri = record.inputUri)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${record.engineName} · ${record.levelTitle}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val savedLine = if (record.inputBytes > 0) {
                    "${formatBytes(record.inputBytes)} → ${formatBytes(record.outputBytes)} · " +
                        "${if (record.savedBytes > 0) "−" + formatBytes(record.savedBytes) else "без экономии"}" +
                        if (record.savedPercent > 0) " (${record.savedPercent}%)" else ""
                } else {
                    "исходник: размер неизвестен"
                }
                Text(
                    text = savedLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = record.outputPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Лог по кнопке: свёрнут по умолчанию, раскрытый показывает все строки и
 * даёт их скопировать одним касанием.
 */
@Composable
private fun LogPanel(
    logs: List<String>,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onToggle, modifier = Modifier.weight(1f)) {
                Text(if (open) "Скрыть лог" else "Лог (${logs.size})")
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (open) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ebrake-log", logs.joinToString("\n")))
                }) { Text("Копировать") }
            }
        }
        if (open) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.background,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    logs.forEach { line ->
                        Text(line, style = LogTextStyle, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
    }
}

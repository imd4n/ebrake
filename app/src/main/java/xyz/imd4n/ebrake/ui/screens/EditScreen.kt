package xyz.imd4n.ebrake.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.CompareLevel
import xyz.imd4n.ebrake.transcode.TranscodeEngine
import xyz.imd4n.ebrake.transcode.TranscodeFormat
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeUiState
import xyz.imd4n.ebrake.transcode.TranscodeViewModel
import xyz.imd4n.ebrake.ui.FullscreenQualityPreview
import xyz.imd4n.ebrake.ui.PreviewPlayerHolder
import xyz.imd4n.ebrake.ui.PreviewPlayerSurface
import xyz.imd4n.ebrake.ui.QualityLevelSlider

@Composable
fun EditScreen(vm: TranscodeViewModel, state: TranscodeUiState) {
    val context = LocalContext.current

    // Один плеер на весь экран сравнения: качество переключается подменой MediaItem
    // с сохранением позиции, полноэкранный режим переиспользует тот же инстанс.
    val player = remember(context) { PreviewPlayerHolder(context) }
    DisposableEffect(player) { onDispose { player.release() } }

    // Уровни, для которых реально есть файл: если кодирование уровня упало,
    // слайдер не предлагает пустой вариант.
    val levels: List<CompareLevel> =
        CompareLevel.entries.filter { level -> state.previews.any { it.level == level } }
    val selected: CompareLevel? =
        state.preset.compareLevel.takeIf { it in levels } ?: levels.firstOrNull()
    val selectedPreview = state.previews.firstOrNull { it.level == selected }
    val currentPath = selectedPreview?.outputPath.orEmpty()

    var fullscreen by remember { mutableStateOf(false) }
    // runId в ключе: после нового «Сравнить» путь тот же, а файл перезаписан.
    LaunchedEffect(currentPath, state.compareRunId) {
        player.load(currentPath, state.compareRunId)
    }

    val subtitle = if (selectedPreview == null) {
        stringResource(R.string.edit_compare_hint)
    } else {
        stringResource(
            R.string.edit_compare_meta,
            stringResource(selected!!.titleRes),
            formatBytes(context, selectedPreview.fileSizeBytes),
            TranscodeFormat.PREVIEW_SEC,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.tab_edit),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.edit_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(stringResource(R.string.edit_engine), style = MaterialTheme.typography.titleSmall)
            // Сетка 2×N: длинные подписи в один столбец занимали пол-экрана
            // и сравнение уезжало за пределы первого взгляда.
            EngineGrid(
                selected = state.preset.engine,
                isAvailable = { engine ->
                    !state.encodersDetected || engine.ffmpegVideoCodec in state.availableEncoders
                },
                onSelect = vm::setEngine,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.encodersDetected) {
                Text(
                    stringResource(R.string.edit_engine_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // Пока кодирует очередь, движок занят: FFmpegKit.cancel() из
                // очереди погасил бы и сравнение, поэтому кнопку блокируем.
                Button(
                    onClick = { vm.runCompare() },
                    enabled = !state.compareRunning && !state.queueRunning,
                ) {
                    Text(stringResource(R.string.edit_compare))
                }
                if (state.compareRunning) {
                    OutlinedButton(onClick = { vm.cancelCompare() }) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            }
            if (state.compareRunning) {
                LinearProgressIndicator(progress = { state.compareProgress01 }, modifier = Modifier.fillMaxWidth())
                Text(state.compareStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.edit_compare_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(player.videoAspect)
                            // Скругляем и чёрную подложку: без clip углы кадра
                            // торчат острыми углами за пределы карточки.
                            .clip(RoundedCornerShape(PLAYER_CORNER))
                            .background(Color.Black),
                    ) {
                        if (currentPath.isNotEmpty()) {
                            // В фулскрине инлайн-поверхность не компонуется:
                            // два PlayerView на одном ExoPlayer Media3 не держит.
                            if (!fullscreen) {
                                PreviewPlayerSurface(
                                    holder = player,
                                    modifier = Modifier.fillMaxSize(),
                                    // Свернули карточку — замолкаем; фулскрин — не молчим.
                                    pauseOnRelease = !fullscreen,
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.edit_preview_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        IconButton(
                            onClick = { fullscreen = true },
                            enabled = currentPath.isNotEmpty(),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        ) {
                            Icon(
                                Icons.Filled.Fullscreen,
                                contentDescription = stringResource(R.string.cd_preview_fullscreen),
                                tint = Color.White,
                            )
                        }
                    }
                    QualityLevelSlider(
                        levels = levels,
                        selected = selected,
                        onSelect = vm::setCompareLevel,
                        enabled = currentPath.isNotEmpty(),
                    )
                    OutlinedButton(
                        onClick = {
                            selected?.let { vm.setCompareLevel(it) }
                            vm.enqueueSelected()
                        },
                        enabled = selected != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.edit_take_to_queue,
                                selected?.let { stringResource(it.titleRes) } ?: "—",
                            ),
                        )
                    }
                }
            }
        }
    }

    if (fullscreen) {
        FullscreenQualityPreview(
            holder = player,
            levels = levels,
            selected = selected,
            subtitle = subtitle,
            onLevelChange = vm::setCompareLevel,
            onDismiss = { fullscreen = false },
        )
    }
}

/**
 * Сетка движков: две ячейки в ряд, в каждой имя кодера и его характер.
 * Недоступные в этой сборке не блокируются, а гасятся — нажать можно, но
 * выглядит сразу понятно, что чип в ffmpeg не встроен.
 */
@Composable
private fun EngineGrid(
    selected: TranscodeEngine,
    isAvailable: (TranscodeEngine) -> Boolean,
    onSelect: (TranscodeEngine) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TranscodeEngine.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { engine ->
                    EngineCell(
                        engine = engine,
                        selected = engine == selected,
                        available = isAvailable(engine),
                        onClick = { onSelect(engine) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Нечётное число движков: пустая ячейка, чтобы вторая колонка
                // не прыгала к краю.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EngineCell(
    engine: TranscodeEngine,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary.copy(alpha = 0.20f) else scheme.surfaceVariant
    val borderColor = when {
        selected -> scheme.primary
        available -> scheme.onSurfaceVariant.copy(alpha = 0.25f)
        else -> scheme.error.copy(alpha = 0.45f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(engine.shortNameRes),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) scheme.primary else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                if (available) engine.hintRes else R.string.engine_not_in_build,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (available) scheme.onSurfaceVariant else scheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Радиус скругления кадра превью: совпадает с формой самой карточки. */
private val PLAYER_CORNER = 12.dp

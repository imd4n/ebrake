package xyz.imd4n.ebrake.ui.gallery

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.SelectedInput
import xyz.imd4n.ebrake.transcode.TranscodeFormat
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.ui.DockMetrics
import xyz.imd4n.ebrake.ui.PreviewPlayerHolder
import xyz.imd4n.ebrake.ui.PreviewPlayerSurface

/**
 * Плавающая сводка отбора: «Выбрано N роликов · общий размер X МБ» и карусель
 * маленьких превью. Живёт в EbrakeApp рядом с доком: стеклу нужен сиблинг
 * источника, а не потомок внутри пейджера. Ширина и стекло — те же, что у дока.
 *
 * Стекло — то же, что у дока: те же плотность фона, размытие, тень, бордер и
 * радиус из [DockMetrics]. Раньше у карточки были свои числа (0.80 / 16.dp /
 * 14.dp / 18.dp) и без бордера, из-за чего она читалась как отдельная панель,
 * а не как часть одной линейки с доком.
 *
 * Отдельный экран не нужен: отметка — это рабочее состояние, а не место, где
 * что-то происходит. Поэтому карточка поверх сетки, а не в её потоке: счётчик
 * не должен двигать плитки.
 */
@Composable
fun SelectionSummaryCard(
    count: Int,
    totalBytes: Long,
    items: List<SelectedInput>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** null = без стекла, просто подложка (превью, тесты). */
    hazeState: HazeState? = null,
) {
    if (count == 0) return
    val shape = RoundedCornerShape(DockMetrics.Radius)
    val glass = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeDefaults.style(
                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = DockMetrics.GlassAlpha),
                blurRadius = DockMetrics.BlurRadius,
            ),
        )
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(DockMetrics.Shadow, shape, clip = false)
            .clip(shape)
            .then(glass)
            // Тонкая светлая кромка — та же, что у пилюли дока.
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp, horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                text = pluralStringResource(R.plurals.selected_count, count, count),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (totalBytes > 0) {
                    stringResource(R.string.selected_total_size, formatBytes(LocalContext.current, totalBytes))
                } else {
                    stringResource(R.string.selected_size_unknown)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Карусель: показывает состав отбора, а не только его размер. Длинный
        // список уходит за край — по этому и видно, что есть ещё.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(items, key = { it.uri.toString() }) { picked ->
                Thumbnail(
                    uri = picked.uri,
                    size = THUMB_SIZE,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/**
 * Слой отбора: крупные превью по три в ряд, галочка снимает отметку, тап по
 * самому превью открывает ролик с плеером и техдеталями.
 *
 * Инлайн-оверлей, а не Dialog: haze живёт в пределах одного окна, в диалоге
 * вместо блюра остался бы только тинт. Поэтому оба места (просмотр на «Вводе»
 * и пикер на «Правке») хостятся в EbrakeApp поверх дока и делят этот компонент.
 * Панель нижняя и не во весь экран (см. SHEET_FRACTION): сверху остаётся
 * контекст, тап по скриму и системный «назад» закрывают окно.
 *
 * Разделители и острые углы плиток — те же, что у сетки на «Вводе»: одна
 * визуальная логика на обоих экранах, иначе переключение вкладок «щёлкает».
 *
 * [onPick] не null только на «Правке»: там тап по ролику отправляет его на
 * сравнение трёх уровней. На «Вводе» тап только показывает ролик.
 */
@Composable
fun SelectedVideosSheet(
    selection: List<SelectedInput>,
    detail: SelectedInput?,
    detailInfo: String,
    detailLoading: Boolean,
    onToggle: (SelectedInput) -> Unit,
    onOpenDetail: (SelectedInput) -> Unit,
    onCloseDetail: () -> Unit,
    onClearAll: () -> Unit,
    onPick: ((SelectedInput) -> Unit)?,
    onDismiss: () -> Unit,
    /** Стекло дока; null = сплошная подложка (превью, тесты). */
    hazeState: HazeState? = null,
) {
    // Dialog сам гасил «назад», инлайну нужен явный хендлер.
    BackHandler(onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
        // Скри м: затемнение + тап мимо закрывает окно.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(topStart = SHEET_TOP_RADIUS, topEnd = SHEET_TOP_RADIUS)
        val glass = if (hazeState != null) {
            Modifier.hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = DockMetrics.GlassAlpha),
                    blurRadius = DockMetrics.BlurRadius,
                ),
            )
        } else {
            Modifier.background(MaterialTheme.colorScheme.surface)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(SHEET_FRACTION)
                .shadow(DockMetrics.Shadow, shape, clip = false)
                .clip(shape)
                .then(glass)
                // Тонкая светлая кромка — та же, что у дока и карточки.
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                // Гасим тапы по самой панели: иначе долетят до скрима и
                // закроют окно прямо под пальцем.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            SheetHandle()
            SheetHeader(title = stringResource(R.string.selected_title))
            if (detail != null) {
                VideoDetailPane(
                    item = detail,
                    info = detailInfo,
                    loading = detailLoading,
                    onBack = onCloseDetail,
                    onPick = onPick,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(SHEET_COLUMNS),
                    // Вес, а не fillMaxSize: сетка должна отдать подвалу его
                    // место, а не выдавить кнопки за нижний край панели.
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GUTTER),
                    verticalArrangement = Arrangement.spacedBy(GUTTER),
                ) {
                    items(selection, key = { it.uri.toString() }) { picked ->
                        SheetTile(
                            item = picked,
                            marked = true,
                            onToggle = { onToggle(picked) },
                            // На «Правке» тап по плитке сразу отправляет ролик
                            // на сравнение: лишний экран между выбором и
                            // сравнением только мешает, а техдетали остаются
                            // на кнопке «i».
                            onClick = if (onPick != null) {
                                { onPick(picked) }
                            } else {
                                { onOpenDetail(picked) }
                            },
                            onInspect = if (onPick != null) {
                                { onOpenDetail(picked) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            // Кнопки внизу, а не в шапке: в шапке они спорили с заголовком за
            // одну строку и «Сбросить отметки» легко было спутать с закрытием.
            // Контур вместо заливки — оба действия равнозначны, и заливка
            // сделала бы «Сбросить» главным там, где это неверно.
            SheetFooter(onClearAll = onClearAll, onClose = onDismiss)
        }
    }
}

/** Ручка сверху: видно, что это нижний лист, а не полноэкранный слой. */
@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun SheetFooter(onClearAll: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onClick = onClearAll, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_clear_selection))
        }
        Button(onClick = onClose, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            // Лист висит вне Scaffold, и LocalContentColor здесь дефолтный
            // чёрный — без явного цвета заголовок нечитаем на стекле.
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SheetTile(
    item: SelectedInput,
    marked: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onInspect: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val coverPx = with(LocalDensity.current) { maxWidth.toPx() }
                .toInt()
                .coerceIn(MIN_SHEET_COVER_PX, MAX_SHEET_COVER_PX)
            val inspectLabel = stringResource(R.string.action_inspect)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                CoverImage(item.uri, coverPx)
                CheckMark(
                    marked = marked,
                    // Галочка в углу снимает отметку, а не открывает ролик:
                    // снятие не должно требовать промаха мимо превью.
                    onClick = onToggle,
                    size = SHEET_CHECK_SIZE,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
                if (onInspect != null) {
                    // Отдельная кнопка техдеталей: тап по самому превью на
                    // «Правке» занят выбором для сравнения.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(CHECK_TOUCH + SHEET_CHECK_SIZE)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .clickable(onClick = onInspect),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = inspectLabel,
                            tint = Color.White,
                            modifier = Modifier.size(SHEET_CHECK_SIZE * 0.7f),
                        )
                    }
                }
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/**
 * Просмотр одного ролика из отбора: плеер исходника и те же метаданные
 * ffprobe, что показываются для входа сравнения.
 */
@Composable
private fun ColumnScope.VideoDetailPane(
    item: SelectedInput,
    info: String,
    loading: Boolean,
    onBack: () -> Unit,
    onPick: ((SelectedInput) -> Unit)?,
) {
    val context = LocalContext.current
    val player = remember(context) { PreviewPlayerHolder(context) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(item.uri) {
        player.loadSource(item.uri)
        onDispose { player.stopSource() }
    }

    // Статистика сверху, плеер снизу: вертикальное видео раньше выдавливало
    // техдетали за край панели. Теперь остаток уезжает в скролл, а кнопки
    // закреплены внизу и всегда под рукой.
    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.sizeBytes > 0) {
                Text(
                    text = formatBytes(context, item.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (info.isNotEmpty()) {
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(player.videoAspect)
                    .clip(RoundedCornerShape(SHEET_CORNER))
                    .background(Color.Black),
            ) {
                PreviewPlayerSurface(holder = player, modifier = Modifier.fillMaxSize())
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_back))
            }
            if (onPick != null) {
                TextButton(
                    onClick = { onPick(item) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_compare_this))
                }
            }
        }
    }
}

/** Обложка ролика; пока декодируется — заглушка, чтобы плитка не мигала. */
@Composable
private fun CoverImage(uri: android.net.Uri, coverPx: Int) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri, key2 = coverPx) {
        value = VideoLibrary.cover(context, uri, coverPx)
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
    }
}

/** Квадратная обложка фиксированного размера — для карусели сводки. */
@Composable
private fun Thumbnail(
    uri: android.net.Uri,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
        .coerceIn(MIN_SHEET_COVER_PX, MAX_SHEET_COVER_PX)
    Box(modifier = modifier.size(size)) {
        CoverImage(uri, px)
    }
}

/**
 * Галочка отметки. Не `Box` с `Icon`: кликабельную область делаем заметно
 * больше самого кружка, иначе по 20dp попасть тяжело.
 */
@Composable
private fun CheckMark(
    marked: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size + CHECK_TOUCH)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    if (marked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (marked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(size * 0.6f),
                )
            }
        }
    }
}

/** Плиток в ряду в листе отбора: три — крупнее, чем четыре. */
private const val SHEET_COLUMNS = 3

/** Доля высоты экрана под окно отбора: сверху остаётся контекст. */
private const val SHEET_FRACTION = 0.75f

/** Верхние углы листа: читается как лист, а не как второй экран. */
private val SHEET_TOP_RADIUS = 28.dp

/** Превью в карусели сводки. */
private val THUMB_SIZE = 48.dp

/** Галочка в углу крупной плитки. */
private val SHEET_CHECK_SIZE = 22.dp

/** Запас вокруг галочки до кликабельной области — попадать пальцем легче. */
private val CHECK_TOUCH = 10.dp

private val SHEET_CORNER = 10.dp

private const val MIN_SHEET_COVER_PX = 200
private const val MAX_SHEET_COVER_PX = 640

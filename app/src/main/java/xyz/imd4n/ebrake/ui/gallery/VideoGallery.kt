package xyz.imd4n.ebrake.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Галерея роликов в духе ReFra: сетка обложек, разбитая по датам
 * («Сегодня», «Вчера», «12 сентября»).
 *
 * Подписи под плитками убраны: в превью всё равно нужен только кадр, а имена
 * съедали высоту — на телефоне в сетку влезало четыре ряда вместо шести.
 *
 * Мультивыбор: тап добавляет ролик в отметку и рисует его порядковый номер.
 * Первый отмеченный идёт на сравнение качества, остальные — на импорт с
 * текущим уровнем (см. [xyz.imd4n.ebrake.transcode.TranscodeViewModel.importRest]).
 *
 * Источник роликов: выбранная папка SAF, иначе MediaStore. Без доступа к медиа
 * показываем пустое состояние с кнопкой «Выбрать вручную» — путь, который
 * работает вообще без разрешений.
 */
@Composable
fun VideoGallery(
    /** Отмеченные ролики в порядке нажатий: первый — под сравнение. */
    selection: List<String>,
    /** Выбранная папка SAF; null = весь MediaStore. */
    treeUri: Uri?,
    hasPermission: Boolean,
    onToggle: (VideoItem) -> Unit,
    onRequestPermission: () -> Unit,
    onPickManually: () -> Unit,
    onShowAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<VideoItem>?>(null) }
    // null = грузим, пустой список = загрузили и ничего не нашли.
    val loadingKey = listOf(hasPermission, treeUri)
    LaunchedEffect(loadingKey) {
        items = if (treeUri != null || hasPermission) {
            VideoLibrary.videos(context, treeUri)
        } else {
            emptyList()
        }
    }

    // Порядковые номера отметок: позиция в списке = место в сравнении/очереди.
    val order = remember(selection) { selection.withIndex().associate { it.value to it.index + 1 } }

    val loaded = items
    when {
        loaded == null -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        !hasPermission && treeUri == null -> GalleryMessage(
            title = "Нужен доступ к видео",
            text = "Галерея показывает ролики из системного хранилища.",
            actions = listOf(
                "Разрешить" to onRequestPermission,
                "Выбрать вручную" to onPickManually,
            ),
            modifier = modifier,
        )

        loaded.isEmpty() -> GalleryMessage(
            title = "Видео не найдено",
            text = if (treeUri != null) {
                "В выбранной папке нет роликов."
            } else {
                "В галерее телефона нет доступных роликов."
            },
            actions = buildList {
                add("Выбрать вручную" to onPickManually)
                if (onShowAll != null) add("Все видео" to onShowAll)
            },
            modifier = modifier,
        )

        else -> LazyVerticalGrid(
            // Квадраты одинаковой высоты → лесенка не нужна, обычная сетка.
            columns = GridCells.Fixed(3),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            dateSections(loaded).forEach { (label, section) ->
                item(key = "section_$label", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                items(section, key = { it.uri.toString() }) { item ->
                    GalleryTile(
                        item = item,
                        order = order[item.uri.toString()],
                        onClick = { onToggle(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(
    item: VideoItem,
    /** Номер отметки (1 = под сравнение) или null, если ролик не отмечен. */
    order: Int?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val selected = order != null
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        // Обложку тянем под реальную ширину плитки: на планшете сетка шире, и кадр
        // должен быть резче. Верхняя отсечка держит кэш в разумных рамках —
        // на 3x экране без неё одна картинка весит ~0.5 МБ.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val coverPx = with(LocalDensity.current) { maxWidth.toPx() }
                .toInt()
                .coerceIn(MIN_COVER_PX, MAX_COVER_PX)
            val cover by produceState<Bitmap?>(
                initialValue = null,
                key1 = item.uri,
                key2 = coverPx,
            ) {
                value = VideoLibrary.cover(context, item.uri, coverPx)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Квадрат под кадр: обложка кропается по центру, поэтому
                    // вертикальные ролики не превращают плитку в колонку.
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val bitmap = cover
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Обложка ещё декодируется — заглушка, чтобы не мигало.
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                }
                if (selected) {
                    // Номер отметки объясняет, кто пойдёт на сравнение, а кто
                    // просто в очередь — иначе отметки выглядят одинаковыми.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(BADGE_SIZE)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = order.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                if (item.durationMs > 0) {
                    Text(
                        text = formatDuration(item.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryMessage(
    title: String,
    text: String,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        actions.forEachIndexed { index, (label, action) ->
            if (index == 0) {
                Button(onClick = action) { Text(label) }
            } else {
                TextButton(onClick = action) { Text(label) }
            }
        }
    }
}

/** Ролики, сгруппированные по дням: сегодня / вчера / дата. */
private fun dateSections(items: List<VideoItem>): List<Pair<String, List<VideoItem>>> {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val midnightToday = calendar.timeInMillis / 1000
    val midnightYesterday = midnightToday - 86_400L
    val dayFormat = SimpleDateFormat("d MMMM", Locale.getDefault())
    val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)

    return items
        .groupBy { video ->
            val added = video.dateAddedSec
            when {
                added >= midnightToday -> "Сегодня"
                added >= midnightYesterday -> "Вчера"
                else -> {
                    val date = Date(added * 1000)
                    val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
                    if (year == thisYear) dayFormat.format(date) else dateFormat.format(date)
                }
            }
        }
        // Порядок групп задаёт самый свежий ролик в них.
        .toList()
        .sortedByDescending { (_, videos) -> videos.maxOf { it.dateAddedSec } }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    // Локаль фиксируем: %d на некоторых локалях (например арабской) подставляет
    // восточно-арабские цифры, и таймкод в галерее выглядит чужеродно.
    val loc = Locale.forLanguageTag("ru")
    return if (h > 0) "%d:%02d:%02d".format(loc, h, m, s) else "%d:%02d".format(loc, m, s)
}

/** Границы декодируемой обложки, px: снизу — чтобы не декодировать 20px, сверху — кэш. */
private const val MIN_COVER_PX = 200
private const val MAX_COVER_PX = 720

/** Кружок с номером отметки в углу плитки. */
private val BADGE_SIZE = 22.dp

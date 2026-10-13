package xyz.imd4n.ebrake.ui

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.CompareLevel
import java.io.File
import kotlin.math.roundToInt

/**
 * Плеер превью-сравнения для вкладки «Правка».
 *
 * Один ExoPlayer на весь экран сравнения (вместо трёх инстансов по карточкам):
 *  - [load] запоминает позицию и playWhenReady ДО подмены MediaItem, поэтому
 *    смена уровня качества посреди ролика продолжает его с того же кадра;
 *  - тот же объект плеера отдаётся полноэкранному оверлею, поэтому вход и выход
 *    из фулскрина тоже не сбрасывают позицию;
 *  - реальные пропорции кадра берутся из [onVideoSizeChanged], поэтому ролик
 *    «вписывается по ширине» без чёрных полей и без обрезки.
 *
 * Экземпляр живёт дольше одного PlayerView: Media3 не держит два PlayerView
 * на одном ExoPlayer, поэтому в полноэкранном режиме инлайн-поверхность
 * не компонуется вовсе — плеер просто переподключается к другому view.
 */
class PreviewPlayerHolder(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** Пропорции кадра (ширина/высота) для «вписать по ширине». */
    var videoAspect: Float by mutableFloatStateOf(DEFAULT_ASPECT)
        private set

    private var loadedPath: String? = null
    private var loadedRunId = 0
    /** Кэш позиции: нужен, пока MediaItem не готов и currentPosition ещё невалиден. */
    private var positionMs = 0L
    private var playWhenReady = false

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = savePosition()

        override fun onIsPlayingChanged(isPlaying: Boolean) = savePosition()

        override fun onPlaybackStateChanged(playbackState: Int) = savePosition()
    }

    init {
        player.addListener(listener)
    }

    /**
     * Показывает клип [path], сохраняя позицию и состояние воспроизведения.
     *
     * @param runId номер запуска сравнения: если он изменился, содержимое файла
     *   новое (движок/уровень пересобраны) — стартуем с нуля, а не со старой позиции.
     */
    fun load(path: String, runId: Int) {
        if (path.isEmpty() || !File(path).exists()) {
            // Сравнение сброшено/перезапущено: замолкаем, новый клип подхватим позже.
            if (player.currentMediaItem != null) player.pause()
            return
        }
        if (path == loadedPath && runId == loadedRunId) return

        savePosition()
        val freshRun = runId != loadedRunId
        val resumeAt = if (freshRun) 0L else positionMs
        val resumePlaying = if (freshRun) false else playWhenReady

        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))), resumeAt)
        player.prepare()
        player.playWhenReady = resumePlaying

        positionMs = resumeAt
        playWhenReady = resumePlaying
        loadedPath = path
        loadedRunId = runId
    }

    private fun savePosition() {
        playWhenReady = player.playWhenReady
        if (player.currentMediaItem == null) return
        val pos = runCatching { player.currentPosition }.getOrDefault(positionMs)
        if (pos >= 0) positionMs = pos
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }

    companion object {
        /** Пока размер кадра неизвестен (первый кадр ещё не пришёл) — 16:9. */
        const val DEFAULT_ASPECT = 16f / 9f
    }
}

/**
 * PlayerView поверх общего [holder]. Размер задаёт вызывающий код:
 * в карточке — по ширине с реальными пропорциями, в фулскрине — на всё окно.
 */
@Composable
fun PreviewPlayerSurface(
    holder: PreviewPlayerHolder,
    modifier: Modifier = Modifier,
    pauseOnRelease: Boolean = false,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = holder.player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier,
        onRelease = { if (pauseOnRelease) holder.player.pause() },
    )
}

/**
 * Слайдер выбора качества (высокое / баланс / эко) — общий для карточки и фулскрина.
 *
 * Значение применяется по [Slider.onValueChangeFinished], то есть в момент
 * отпускания: перетаскивание не дёргает плеер на каждом шаге.
 */
@Composable
fun QualityLevelSlider(
    levels: List<CompareLevel>,
    selected: CompareLevel?,
    onSelect: (CompareLevel) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (levels.size < 2) {
        // Остальные уровни не собрались — слайдеру не на что опереться.
        Text(
            stringResource(
                R.string.level_label,
                selected?.let { stringResource(it.titleRes) } ?: "—",
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val selectedIdx = levels.indexOf(selected).coerceAtLeast(0)
    var draftIdx by remember(levels, selectedIdx) { mutableIntStateOf(selectedIdx) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = draftIdx.toFloat(),
            onValueChange = { draftIdx = it.roundToInt() },
            onValueChangeFinished = { levels.getOrNull(draftIdx)?.let(onSelect) },
            valueRange = 0f..(levels.size - 1).toFloat(),
            steps = levels.size - 2,
            enabled = enabled,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            levels.forEachIndexed { index, level ->
                Text(
                    stringResource(level.titleRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == draftIdx) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Полноэкранный просмотр того же клипа, что и в карточке: системные панели
 * прячутся, видео занимает всё окно, слайдер качества остаётся под ним.
 */
@Composable
fun FullscreenQualityPreview(
    holder: PreviewPlayerHolder,
    levels: List<CompareLevel>,
    selected: CompareLevel?,
    subtitle: String,
    onLevelChange: (CompareLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    // Иммерсивный режим: окно диалога прячет системные панели и возвращает их при выходе.
    DisposableEffect(Unit) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(subtitle, style = MaterialTheme.typography.titleSmall, color = Color.White)
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.FullscreenExit,
                        contentDescription = stringResource(R.string.cd_fullscreen_exit),
                        tint = Color.White,
                    )
                }
            }
            // resizeMode=FIT: вертикальные клипы не растягиваются, а вписываются по высоте.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                PreviewPlayerSurface(holder = holder, modifier = Modifier.fillMaxSize())
            }
            QualityLevelSlider(
                levels = levels,
                selected = selected,
                onSelect = onLevelChange,
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

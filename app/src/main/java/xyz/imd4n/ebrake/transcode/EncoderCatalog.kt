package xyz.imd4n.ebrake.transcode

import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что удалось вытащить из `-encoders`. Пустое detected = детект ещё не проходил. */
data class EncoderState(
    val names: Set<String> = emptySet(),
    val detected: Boolean = false,
)

/**
 * Каталог энкодеров сборки: нужен и сравнению (ViewModel), и очереди (сервис).
 * Раньше обе стороны держали своё состояние в [TranscodeUiState] и могли
 * разойтись по мнению о доступности чипов.
 */
object EncoderCatalog {
    private val _state = MutableStateFlow(EncoderState())
    val state: StateFlow<EncoderState> = _state.asStateFlow()

    /**
     * Однократный разбор `-encoders`. Вызывать из IO-потока: `execute()` блокирует.
     * synchronized — сравнение и очередь могут дёрнуть одновременно, а ffmpeg-kit
     * не любит параллельные сессии.
     */
    @Synchronized
    fun ensureDetected() {
        if (_state.value.detected) return
        val names = runCatching {
            FFmpegKit.execute("-hide_banner -encoders").getOutput().orEmpty()
        }.getOrDefault("")
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")).getOrNull(1).orEmpty() }
            .filter { it.isNotEmpty() && it != "----" }
            .toSet()
        publish(names)
        TranscodeQueue.appendLog("Детект энкодеров: всего ${names.size}.")
    }

    /** Для ручного детекта из диагностики — результат не логируем повторно. */
    fun publish(names: Set<String>) {
        _state.value = EncoderState(names, true)
    }

    fun available(engine: TranscodeEngine): Boolean {
        val s = _state.value
        return !s.detected || engine.ffmpegVideoCodec in s.names
    }

    /** Цепочка fallback: HW H.264 → HW HEVC → x264 → MPEG-4 (есть всегда). */
    fun effective(wanted: TranscodeEngine): TranscodeEngine {
        val have = _state.value.names
        if (have.isEmpty() || wanted.ffmpegVideoCodec in have) return wanted
        return listOf(
            TranscodeEngine.HW_H264,
            TranscodeEngine.HW_HEVC,
            TranscodeEngine.SW_X264,
            TranscodeEngine.SW_MPEG4,
        ).firstOrNull { it.ffmpegVideoCodec in have } ?: TranscodeEngine.SW_MPEG4
    }
}

package xyz.imd4n.ebrake.transcode

import android.net.Uri

/**
 * Движок кодирования для одного задания.
 *
 * [title] — длинная подпись для логов и уведомлений, [shortName] + [hint] —
 * разложение для сетки чипов на «Правке», где ширина одной ячейки мала.
 */
enum class TranscodeEngine(
    val title: String,
    val ffmpegVideoCodec: String,
    val shortName: String,
    val hint: String,
) {
    HW_H264("HW H.264 (MediaCodec, быстро)", "h264_mediacodec", "HW H.264", "быстро"),
    HW_HEVC("HW H.265 (MediaCodec, компактно)", "hevc_mediacodec", "HW H.265", "компактнее"),
    SW_X264("SW x264 (точный CRF, медленно)", "libx264", "SW x264", "точный CRF"),
    SW_MPEG4("SW MPEG-4 (везде работает)", "mpeg4", "SW MPEG-4", "везде работает"),
    ;

    override fun toString(): String = title
}

/** Три уровня сжатия для экрана сравнения. */
enum class CompareLevel(val title: String) {
    HIGH("Высокое"),
    BALANCED("Баланс"),
    ECO("Эко"),
}

/** Параметры кодирования, общие для превью и финальной очереди. */
data class TranscodePreset(
    val engine: TranscodeEngine = TranscodeEngine.HW_H264,
    val compareLevel: CompareLevel = CompareLevel.BALANCED,
)

/** Одно задание в очереди вывода. */
data class TranscodeJob(
    val id: Long,
    val inputUri: Uri,
    val displayName: String,
    val preset: TranscodePreset,
    val status: JobStatus = JobStatus.QUEUED,
    val progress01: Float = 0f,
    val outputPath: String = "",
    val outputSizeBytes: Long = 0L,
    /** Размер исходника: из него считается экономия в выполненной очереди. */
    val inputSizeBytes: Long = 0L,
    /** Имя файла без суффикса уровня — для списка выполненных. */
    val inputName: String = "",
)

/**
 * Ролик, отмеченный на «Вводе». Первый в списке — тот, который идёт на
 * сравнение качества; остальные импортируются сразу с выбранным уровнем.
 */
data class SelectedInput(
    val uri: Uri,
    val name: String,
)

/**
 * Запись выполненной очереди: что перекодировали и сколько места сэкономили
 * относительно исходника. Живёт отдельно от [TranscodeJob], чтобы «очередь»
 * можно было очистить, а историю — нет.
 */
data class DoneRecord(
    val id: Long,
    val name: String,
    val inputUri: Uri,
    val inputBytes: Long,
    val outputBytes: Long,
    /** Короткое имя движка («HW H.264») — в карточке истории длинная подпись не нужна. */
    val engineName: String,
    val levelTitle: String,
    val outputPath: String,
) {
    val savedBytes: Long get() = Savings.savedBytes(inputBytes, outputBytes)

    val savedPercent: Int get() = Savings.savedPercent(inputBytes, outputBytes)
}

/**
 * Арифметика экономии, вынесенная отдельно от [DoneRecord]: чистые функции
 * без Uri и Android-типов, поэтому покрываются обычными юнит-тестами
 * (`./gradlew :app:testDebugUnitTest`) без Robolectric.
 */
object Savings {
    /** Сэкономлено на диске; отрицательная дельта (файл вырос) — ноль. */
    fun savedBytes(inputBytes: Long, outputBytes: Long): Long =
        (inputBytes - outputBytes).coerceAtLeast(0L)

    /**
     * Доля экономии в процентах. Нулевой размер исходника означает «размер
     * неизвестен» (stat не дал) — делить на ноль нельзя, показываем 0, а не
     * правдоподобное отрицательное число.
     */
    fun savedPercent(inputBytes: Long, outputBytes: Long): Int {
        if (inputBytes <= 0L) return 0
        return (savedBytes(inputBytes, outputBytes) * 100 / inputBytes).toInt()
    }
}

/** Результат превью-сравнения (10-секундный отрезок, реальный файл в cacheDir). */
data class PreviewResult(
    val level: CompareLevel,
    val fileSizeBytes: Long,
    val note: String,
    val outputPath: String = "",
)

enum class JobStatus(val title: String) {
    QUEUED("В очереди"),
    RUNNING("Кодируется"),
    DONE("Готово"),
    FAILED("Ошибка"),
}

/**
 * Построение ffmpeg-команды для заданного движка и уровня.
 *
 * HW идёт через constant-quality / VBR (без фиксированного CBR):
 *  - bitrate_mode cq/vbr + ограничения qp/qmin/qmax.
 *    Опции существуют, только если в сборке есть mediacodec-обёртка!
 * SW x264 — классический CRF (нужен --enable-gpl при сборке).
 * SW mpeg4 — -q:v, фиксированный квантователь = constant quality,
 *    есть в любой сборке ffmpeg. Шкала 1 (лучше) — 31.
 *
 * Возвращает команду одной строкой для FFmpegKit.executeAsync().
 */
object FfmpegCommands {
    fun previewArgs(
        inputSafUrl: String,
        outputPath: String,
        preset: TranscodePreset,
        seekSec: Int = 30,
        durationSec: Int = 10,
    ): String = when (preset.engine) {
        TranscodeEngine.HW_H264 -> {
            val hw = hwQuality(preset.compareLevel)
            "-ss $seekSec -t $durationSec -i $inputSafUrl " +
                "-c:v h264_mediacodec ${hwArgs(hw)} " +
                "-c:a aac -b:a 128k -y $outputPath"
        }
        TranscodeEngine.HW_HEVC -> {
            val hw = hwQuality(preset.compareLevel)
            "-ss $seekSec -t $durationSec -i $inputSafUrl " +
                "-c:v hevc_mediacodec ${hwArgs(hw)} " +
                "-c:a aac -b:a 128k -y $outputPath"
        }
        TranscodeEngine.SW_X264 -> {
            val crf = swCrf(preset.compareLevel)
            "-ss $seekSec -t $durationSec -i $inputSafUrl " +
                "-c:v libx264 -crf $crf -preset ultrafast " +
                "-c:a aac -b:a 128k -y $outputPath"
        }
        TranscodeEngine.SW_MPEG4 -> {
            val q = mpeg4q(preset.compareLevel)
            "-ss $seekSec -t $durationSec -i $inputSafUrl " +
                "-c:v mpeg4 -q:v $q " +
                "-c:a aac -b:a 128k -y $outputPath"
        }
    }

    fun fullArgs(
        inputSafUrl: String,
        outputSafUrl: String,
        preset: TranscodePreset,
    ): String = when (preset.engine) {
        TranscodeEngine.HW_H264 -> {
            "-i $inputSafUrl -c:v h264_mediacodec ${hwArgs(hwQuality(preset.compareLevel))} " +
                "-c:a aac -b:a 128k -y $outputSafUrl"
        }
        TranscodeEngine.HW_HEVC -> {
            "-i $inputSafUrl -c:v hevc_mediacodec ${hwArgs(hwQuality(preset.compareLevel))} " +
                "-c:a aac -b:a 128k -y $outputSafUrl"
        }
        TranscodeEngine.SW_X264 -> {
            val crf = swCrf(preset.compareLevel)
            "-i $inputSafUrl -c:v libx264 -crf $crf -preset veryfast " +
                "-c:a aac -b:a 128k -y $outputSafUrl"
        }
        TranscodeEngine.SW_MPEG4 -> {
            val q = mpeg4q(preset.compareLevel)
            "-i $inputSafUrl -c:v mpeg4 -q:v $q " +
                "-c:a aac -b:a 128k -y $outputSafUrl"
        }
    }

    /**
     * Уровень качества HW.
     *
     * Уроки с устройства (28.09.2026, диагностика):
     * - `bitrate_mode` — int-опция; значение `cq` чип отвергает
     *   («Could not open encoder before EOF», -22). Везде используем `vbr`.
     * - Одни qp-границы без целевого битрейта чип игнорирует: Баланс (q26)
     *   и Эко (q32) дали одинаковый размер. Поэтому лестница задаётся через
     *   VBR-таргет -b:v/-maxrate (это НЕ фиксированный CBR — битрейт плавает
     *   по содержимому), а qp-границы остаются страховкой от развалов качества.
     */
    private data class HwQuality(val bitrate: String, val maxrate: String, val qMin: Int, val qMax: Int)

    private fun hwQuality(level: CompareLevel): HwQuality = when (level) {
        CompareLevel.HIGH -> HwQuality("8M", "12M", 18, 25)
        CompareLevel.BALANCED -> HwQuality("3M", "4.5M", 22, 30)
        CompareLevel.ECO -> HwQuality("1.2M", "1.8M", 26, 34)
    }

    private fun hwArgs(hw: HwQuality) =
        "-bitrate_mode vbr -b:v ${hw.bitrate} -maxrate ${hw.maxrate} " +
            "-bufsize ${hw.maxrate} -qmin ${hw.qMin} -qmax ${hw.qMax} " +
            "-qp_i_max ${hw.qMax} -qp_p_max ${hw.qMax}"

    private fun swCrf(level: CompareLevel): Int = when (level) {
        CompareLevel.HIGH -> 20
        CompareLevel.BALANCED -> 26
        CompareLevel.ECO -> 32
    }

    /** mpeg4 -q:v: меньше = лучше, шкала 1–31. */
    private fun mpeg4q(level: CompareLevel): Int = when (level) {
        CompareLevel.HIGH -> 2
        CompareLevel.BALANCED -> 4
        CompareLevel.ECO -> 8
    }
}

/**
 * Форматирование размеров/времени/логов. Лежит рядом с моделями, а не во
 * ViewModel, потому что пользуются и UI, и фоновый сервис (уведомление).
 */
object TranscodeFormat {
    /** Длина превью-отрезка для сравнения качества, сек. */
    const val PREVIEW_SEC = 10

    fun formatBytes(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f ГБ".format(b / 1_000_000_000.0)
        b >= 1_000_000 -> "%.1f МБ".format(b / 1_000_000.0)
        b >= 1_000 -> "%.0f КБ".format(b / 1_000.0)
        else -> "$b Б"
    }

    fun formatSec(d: Double): String {
        val s = d.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Дельта размера со знаком: «−132,0 МБ» / «+4,1 МБ». */
    fun formatDelta(bytes: Long): String {
        val sign = if (bytes < 0) "−" else "+"
        return sign + formatBytes(if (bytes < 0) -bytes else bytes)
    }

    fun lastLogLines(output: String?, n: Int): String =
        output?.lineSequence()?.toList()?.takeLast(n)?.joinToString("\n") ?: "(нет вывода)"
}

package xyz.imd4n.ebrake.transcode

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import java.util.Locale
import xyz.imd4n.ebrake.R

/**
 * Движок кодирования для одного задания.
 *
 * Подписи — не строки, а id ресурсов: иначе они останутся русскими у
 * англоязычного пользователя. Экраны берут текст через `stringResource`,
 * логи и уведомления — через `getString`.
 *
 * [titleRes] — длинная подпись для логов и уведомлений, [shortNameRes] +
 * [hintRes] — разложение для сетки чипов на «Правке», где ширина ячейки мала.
 */
enum class TranscodeEngine(
    @param:StringRes val titleRes: Int,
    val ffmpegVideoCodec: String,
    @param:StringRes val shortNameRes: Int,
    @param:StringRes val hintRes: Int,
) {
    HW_H264(
        R.string.engine_hw_h264_title, "h264_mediacodec",
        R.string.engine_hw_h264_short, R.string.engine_hw_h264_hint,
    ),
    HW_HEVC(
        R.string.engine_hw_hevc_title, "hevc_mediacodec",
        R.string.engine_hw_hevc_short, R.string.engine_hw_hevc_hint,
    ),
    SW_X264(
        R.string.engine_sw_x264_title, "libx264",
        R.string.engine_sw_x264_short, R.string.engine_sw_x264_hint,
    ),
    SW_MPEG4(
        R.string.engine_sw_mpeg4_title, "mpeg4",
        R.string.engine_sw_mpeg4_short, R.string.engine_sw_mpeg4_hint,
    ),
    ;

    /** Короткое имя для логов и файлов: язык тут ни при чём, имя кодера. */
    override fun toString(): String = ffmpegVideoCodec
}

/** Три уровня сжатия для экрана сравнения. */
enum class CompareLevel(@param:StringRes val titleRes: Int) {
    HIGH(R.string.level_high),
    BALANCED(R.string.level_balanced),
    ECO(R.string.level_eco),
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
    /**
     * Движок и уровень хранятся перечислениями, а не строками: запись живёт
     * дольше, чем может смениться язык телефона, и сохранённая подпись осталась
     * бы на старом языке. Экран берёт текст из ресурсов сам.
     */
    val engine: TranscodeEngine,
    val level: CompareLevel,
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

enum class JobStatus(@param:StringRes val titleRes: Int) {
    QUEUED(R.string.status_queued),
    RUNNING(R.string.status_running),
    DONE(R.string.status_done),
    FAILED(R.string.status_failed),
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
 *
 * Локаль передаётся явно, а не берётся из `Locale.getDefault()`. Иначе
 * получается ровно то, что уже случалось: число печатается разделителем
 * системы («31.3»), а единица — из ресурсов («МБ»). Дальше это расходится по
 * логам, уведомлениям и сохранённой истории, где язык один, а локаль — нет.
 *
 * Само форматирование чисел остаётся чистым: ресурсы нужны только за единицами
 * измерения, поэтому их подставляет вызывающий через [formatBytes] в UI или
 * через `getString` в сервисе. Тесты бьют по чистой части — см. [sizeParts].
 */
object TranscodeFormat {
    /** Длина превью-отрезка для сравнения качества, сек. */
    const val PREVIEW_SEC = 10

    /** Единица измерения размера. */
    enum class SizeUnit(@param:StringRes val labelRes: Int) {
        BYTES(R.string.unit_b),
        KILOBYTES(R.string.unit_kb),
        MEGABYTES(R.string.unit_mb),
        GIGABYTES(R.string.unit_gb),
    }

    /**
     * Разбор размера на число и единицу — без строк и ресурсов, чтобы
     * покрывалось обычным юнит-тестом.
     */
    fun sizeParts(bytes: Long): Pair<Double, SizeUnit> = when {
        bytes >= 1_000_000_000 -> bytes / 1_000_000_000.0 to SizeUnit.GIGABYTES
        bytes >= 1_000_000 -> bytes / 1_000_000.0 to SizeUnit.MEGABYTES
        bytes >= 1_000 -> bytes / 1_000.0 to SizeUnit.KILOBYTES
        else -> bytes.toDouble() to SizeUnit.BYTES
    }

    /** Число в локали пользователя; цифры и разделитель — как у него в системе. */
    fun number(value: Double, locale: Locale, decimals: Int = 1): String =
        if (decimals == 0) {
            String.format(locale, "%.0f", value)
        } else {
            String.format(locale, "%\u002e1f", value)
        }

    /** Размер с единицей: [context] — источник перевода «МБ»/«MB». */
    fun formatBytes(context: Context, bytes: Long, locale: Locale = currentLocale(context)): String {
        val (value, unit) = sizeParts(bytes)
        val decimals = if (unit == SizeUnit.KILOBYTES) 0 else if (unit == SizeUnit.BYTES) 0 else 1
        return context.getString(unit.labelRes, number(value, locale, decimals))
    }

    /** Локаль приложения: с учётом выбора языка в настройках (13+). */
    fun currentLocale(context: Context): Locale =
        context.resources.configuration.locales[0]

    fun formatSec(d: Double, locale: Locale): String {
        val s = d.toInt()
        return String.format(locale, "%d:%02d", s / 60, s % 60)
    }

    /** Дельта размера со знаком: «−132,0 МБ» / «+4,1 МБ». */
    fun formatDelta(context: Context, bytes: Long, locale: Locale = currentLocale(context)): String {
        // Минус — U+2212, а не дефис: в строке «−132,0 МБ» он длиннее
        // и не слипается с цифрой. Сам знак не переводится.
        val sign = if (bytes < 0) "−" else "+"
        return context.getString(
            R.string.delta_signed,
            sign,
            formatBytes(context, if (bytes < 0) -bytes else bytes, locale),
        )
    }

    fun lastLogLines(context: Context, output: String?, n: Int): String =
        output?.lineSequence()?.toList()?.takeLast(n)?.joinToString("\n")
            ?: context.getString(R.string.log_no_output)
}

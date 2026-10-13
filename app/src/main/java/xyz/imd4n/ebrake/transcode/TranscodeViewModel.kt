package xyz.imd4n.ebrake.transcode

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import xyz.imd4n.ebrake.transcode.TranscodeFormat.PREVIEW_SEC
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatSec
import xyz.imd4n.ebrake.transcode.TranscodeFormat.lastLogLines

data class TranscodeUiState(
    val inputUri: Uri? = null,
    val inputName: String = "Файл не выбран",
    val mediaInfo: String = "Выбери видео на экране «Ввод»",
    val durationSec: Double = 0.0,
    /**
     * Отмеченные на «Вводе» ролики в порядке нажатий. Первый — тот, который
     * идёт на сравнение качества; остальные импортируются сразу с выбранным
     * уровнем (минуя сравнение).
     */
    val selection: List<SelectedInput> = emptyList(),
    val preset: TranscodePreset = TranscodePreset(),
    val previews: List<PreviewResult> = emptyList(),
    /**
     * Счётчик запусков сравнения. Растёт на каждом `runCompare()`: плеер превью
     * по нему понимает, что файл с тем же путём перезаписан и его нужно
     * перезагрузить с нуля, а не продолжать со старой позиции.
     */
    val compareRunId: Int = 0,
    val compareRunning: Boolean = false,
    val compareProgress01: Float = 0f,
    val compareStatus: String = "",
    // Ниже — зеркало состояния, которым владеет TranscodeQueue/EncoderCatalog.
    // Сами очереди и лог живут там: кодирование крутит TranscodeService, он
    // переживает Activity, а ViewModel — нет. Экраны читают только ui.
    val queue: List<TranscodeJob> = emptyList(),
    val queueRunning: Boolean = false,
    /** Выполненные задания: список роликов и суммарная экономия места. */
    val done: List<DoneRecord> = emptyList(),
    val logs: List<String> = TranscodeQueue.logs.value,
    /** Куда пишет очередь: выбранная папка или app-specific dir. */
    val outputLabel: String = "",
    /** true = выбрана пользовательская папка (нужна кнопка «Сброс»). */
    val outputTreeChosen: Boolean = false,
    /** Откуда галерея берёт ролики: выбранная папка или весь MediaStore. */
    val inputFolderLabel: String = InputTarget.ALL_VIDEOS_LABEL,
    /** true = выбрана папка ввода (галерея лишена разрешения MediaStore). */
    val inputFolderChosen: Boolean = false,
    /** Дерево SAF выбранной папки; null = весь MediaStore. */
    val inputTreeUri: Uri? = null,
    /** Имена энкодеров из `-encoders`. Пусто = ещё не детектили. */
    val availableEncoders: Set<String> = emptySet(),
    val encodersDetected: Boolean = false,
)

class TranscodeViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(TranscodeUiState())
    val ui: StateFlow<TranscodeUiState> = _ui.asStateFlow()

    /** Многоразовый SAF-URL входа (ffkitsaf://), освобождается при смене файла. */
    private var inputSafUrl: String? = null

    init {
        // Одноразовые SAF-URL (дефолт библиотеки) не открываются в executeAsync:
        // на устройстве падало с "Failed to open SAF id N: callback returned 0".
        // Делаем все URL многоразовыми и освобождаем вручную после каждого задания.
        // (TranscodeService ставит то же самое — сервис может стартовать первым.)
        FFmpegKitConfig.setSafUrlsReusable(true)

        // Зеркалим состояние очереди/лога/энкодеров в ui одним потоком,
        // чтобы экраны не знали про TranscodeQueue и EncoderCatalog.
        viewModelScope.launch {
            TranscodeQueue.queue.collect { q -> _ui.update { it.copy(queue = q) } }
        }
        viewModelScope.launch {
            TranscodeQueue.running.collect { r -> _ui.update { it.copy(queueRunning = r) } }
        }
        viewModelScope.launch {
            TranscodeQueue.logs.collect { l -> _ui.update { it.copy(logs = l) } }
        }
        viewModelScope.launch {
            TranscodeQueue.done.collect { d -> _ui.update { it.copy(done = d) } }
        }
        viewModelScope.launch {
            EncoderCatalog.state.collect { s ->
                _ui.update {
                    it.copy(availableEncoders = s.names, encodersDetected = s.detected)
                }
            }
        }
        val context = getApplication<Application>()
        _ui.update {
            it.copy(
                outputLabel = OutputTarget.label(context),
                outputTreeChosen = OutputTarget.hasTree(context),
                inputFolderLabel = InputTarget.label(context),
                inputFolderChosen = InputTarget.hasTree(context),
                inputTreeUri = InputTarget.treeUri(context),
            )
        }
    }

    /**
     * Папка вывода от системного пикера. null — вернуться к папке приложения.
     * Выбор сохраняется в SharedPreferences: сервис переживает Activity.
     */
    fun setOutputTree(uri: Uri?) {
        val context = getApplication<Application>()
        OutputTarget.setTree(context, uri)
        _ui.update {
            it.copy(
                outputLabel = OutputTarget.label(context),
                outputTreeChosen = OutputTarget.hasTree(context),
            )
        }
        appendLog("Папка вывода: ${OutputTarget.label(context)}")
    }

    /**
     * Папка ввода от системного пикера: галерея покажет ровно её содержимое
     * (например, только Downloads) и без разрешения на MediaStore. null —
     * вернуться ко всем роликам телефона.
     */
    fun setInputFolder(uri: Uri?) {
        val context = getApplication<Application>()
        InputTarget.setTree(context, uri)
        _ui.update {
            it.copy(
                inputFolderLabel = InputTarget.label(context),
                inputFolderChosen = InputTarget.hasTree(context),
                inputTreeUri = InputTarget.treeUri(context),
            )
        }
        appendLog("Папка ввода: ${InputTarget.label(context)}")
    }

    /**
     * Отметка ролика в галерее. Первый отмеченный становится входом сравнения;
     * если отметку сняли с него — входом становится следующий по порядку.
     */
    fun toggleSelection(uri: Uri, name: String) {
        val state = _ui.value
        val key = uri.toString()
        val current = state.selection
        val next = if (current.any { it.uri.toString() == key }) {
            current.filterNot { it.uri.toString() == key }
        } else {
            current + SelectedInput(uri, name)
        }
        _ui.update { it.copy(selection = next) }
        val primary = next.firstOrNull()
        // Метаданные и превью относятся к текущему «первому», а не ко всей
        // отметке, поэтому перечитываем их только при смене входа.
        if (primary?.uri != state.inputUri) {
            if (primary == null) clearInput() else applyInput(primary.uri, primary.name)
        }
    }

    /** Импорт нескольких файлов из ручного пикера: первым идёт в сравнение. */
    fun addToSelection(items: List<Pair<Uri, String>>) {
        items.forEach { (uri, name) ->
            if (_ui.value.selection.none { it.uri == uri }) {
                _ui.update { it.copy(selection = it.selection + SelectedInput(uri, name)) }
            }
        }
        val primary = _ui.value.selection.firstOrNull()
        if (primary != null && primary.uri != _ui.value.inputUri) applyInput(primary.uri, primary.name)
    }

    fun clearSelection() {
        _ui.update { it.copy(selection = emptyList()) }
        clearInput()
    }

    private fun clearInput() {
        _ui.update {
            it.copy(
                inputUri = null,
                inputName = "Файл не выбран",
                mediaInfo = "Выбери видео на экране «Ввод»",
                durationSec = 0.0,
                previews = emptyList(),
            )
        }
    }

    fun setInput(uri: Uri, name: String) {
        // Одиночный выбор тоже должен попадать в отметку, иначе «первый файл»
        // разъедется с входом сравнения. Поднимаем ролик на первое место.
        val current = _ui.value.selection
        _ui.update {
            it.copy(
                selection = listOf(SelectedInput(uri, name)) + current.filter { s -> s.uri != uri },
            )
        }
        applyInput(uri, name)
    }

    private fun applyInput(uri: Uri, name: String) {
        inputSafUrl?.let { runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(it) } }
        inputSafUrl = null
        _ui.update {
            it.copy(
                inputUri = uri,
                inputName = name,
                mediaInfo = "Читаю метаданные…",
                durationSec = 0.0,
                previews = emptyList(),
            )
        }
        appendLog("Ввод: $name")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val saf = FFmpegKitConfig.getSafParameterForRead(ctx, uri, true)
                inputSafUrl = saf
                val session = FFprobeKit.getMediaInformation(saf)
                val info = session.getMediaInformation()
                if (info == null) {
                    _ui.update {
                        it.copy(mediaInfo = "FFprobe не смог прочитать файл.\n${lastLogLines(session.getOutput(), 5)}")
                    }
                    appendLog("FFprobe: нет MediaInformation")
                    return@launch
                }
                val dur = info.getDuration()?.toDoubleOrNull() ?: 0.0
                val streams = prettyStreams(info)
                _ui.update {
                    it.copy(
                        durationSec = dur,
                        mediaInfo = buildString {
                            append("Формат: ${info.getFormat()} (${info.getLongFormat()})\n")
                            append("Длительность: ${formatSec(dur)}, размер: ${formatBytes(info.getSize()?.toLongOrNull() ?: 0)}\n")
                            append("Битрейт: ${info.getBitrate() ?: "?"} bps\n")
                            append(streams)
                        },
                    )
                }
                appendLog("FFprobe: ${info.getFormat()}, ${formatSec(dur)}")
            } catch (e: Exception) {
                _ui.update { it.copy(mediaInfo = "Ошибка чтения: ${e.message}") }
                appendLog("FFprobe упал: ${e.message}")
            }
        }
    }

    fun setEngine(engine: TranscodeEngine) {
        _ui.update { it.copy(preset = it.preset.copy(engine = engine), previews = emptyList()) }
        appendLog("Движок: ${engine.title}")
    }

    fun setCompareLevel(level: CompareLevel) {
        _ui.update { it.copy(preset = it.preset.copy(compareLevel = level)) }
    }

    /**
     * Реальное сравнение: 3 кодирования отрезка 10 сек (середина файла)
     * синхронными execute() в IO-потоке, последовательно. Прогресс грубый —
     * по числу готовых уровней; отмена через FFmpegKit.cancel().
     */
    fun runCompare() {
        val state = _ui.value
        val saf = inputSafUrl
        if (state.inputUri == null || saf == null) {
            appendLog("Сравнение: сначала выбери файл на экране «Ввод».")
            return
        }
        if (state.compareRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    compareRunning = true,
                    compareProgress01 = 0f,
                    previews = emptyList(),
                    compareRunId = it.compareRunId + 1,
                )
            }
            EncoderCatalog.ensureDetected()
            val wanted = state.preset.engine
            val engine = EncoderCatalog.effective(wanted)
            if (engine != wanted) {
                appendLog("Движок ${wanted.ffmpegVideoCodec} отсутствует в сборке → использую ${engine.ffmpegVideoCodec}.")
            }
            val seek = ((state.durationSec - PREVIEW_SEC) / 2).coerceAtLeast(0.0).toInt()
            appendLog("Сравнение: отрезок 10 сек со ${seek}с → 3 уровня (${engine.title})…")
            val results = mutableListOf<PreviewResult>()
            CompareLevel.entries.forEachIndexed { idx, level ->
                if (!ui.value.compareRunning) return@forEachIndexed // отменили
                val outFile = File(getApplication<Application>().cacheDir, "preview_${level.name.lowercase()}.mp4")
                val cmd = FfmpegCommands.previewArgs(
                    inputSafUrl = saf,
                    outputPath = outFile.absolutePath,
                    preset = state.preset.copy(engine = engine, compareLevel = level),
                    seekSec = seek,
                    durationSec = PREVIEW_SEC,
                )
                _ui.update {
                    it.copy(compareStatus = "${level.title} (${idx + 1}/3)…")
                }
                // Команду в лог не пишем: она длинная и перебивает ход событий,
                // а по ошибке ffmpeg всё равно отдаёт свой хвост вывода.
                appendLog("Сравнение: ${level.title} (${idx + 1}/3)…")
                val session = FFmpegKit.execute(cmd)
                val rc = session.getReturnCode()
                if (ReturnCode.isSuccess(rc) && outFile.exists()) {
                    results += PreviewResult(level, outFile.length(), cmd, outFile.absolutePath)
                    appendLog("${level.title}: готово, ${formatBytes(outFile.length())}")
                } else {
                    appendLog(
                        "${level.title}: ОШИБКА rc=${rc?.value}\n" +
                            lastLogLines(session.getOutput(), 8),
                    )
                }
                _ui.update { it.copy(compareProgress01 = (idx + 1) / 3f, previews = results.toList()) }
            }
            val done = ui.value.compareRunning
            _ui.update { it.copy(compareRunning = false, compareStatus = "") }
            appendLog(if (done) "Сравнение готово: выбери уровень кнопкой под карточкой." else "Сравнение отменено.")
        }
    }

    fun cancelCompare() {
        if (!_ui.value.compareRunning) return
        FFmpegKit.cancel()
        _ui.update { it.copy(compareRunning = false, compareStatus = "") }
        appendLog("Сравнение: отмена запрошена…")
    }

    fun enqueueSelected() {
        val state = _ui.value
        val uri = state.inputUri ?: run {
            appendLog("Очередь: нечего добавлять — выбери файл.")
            return
        }
        addJob(uri, state.inputName, state.preset)
    }

    /**
     * Импорт пачки: в очередь уходят все отмеченные, кроме первого — он
     * остаётся на «Правке» под сравнение трёх уровней. Сравнение нужно
     * одному файлу: гонять его для каждого ролика — это минуты впустую.
     */
    fun importRest() {
        val state = _ui.value
        val rest = state.selection.drop(1)
        if (rest.isEmpty()) {
            appendLog("Импорт: отметь хотя бы два ролика — первый идёт на сравнение.")
            return
        }
        var added = 0
        rest.forEach { picked ->
            if (addJob(picked.uri, picked.name, state.preset)) added++
        }
        appendLog(
            "Импорт: в очередь ушло $added из ${state.selection.size - 1}. " +
                "Сравнение — по «${state.inputName}».",
        )
    }

    /**
     * Добавляет задание. false, если такой ролик уже ждёт в очереди: повторный
     * тап «Импорт» иначе набивал бы очередь копиями одного и того же файла.
     */
    private fun addJob(uri: Uri, name: String, preset: TranscodePreset): Boolean {
        val pending = TranscodeQueue.queue.value
            .any { it.inputUri == uri && it.status in PENDING_STATUSES }
        if (pending) {
            appendLog("Уже в очереди: $name — пропускаю.")
            return false
        }
        val job = TranscodeJob(
            id = TranscodeQueue.nextId(),
            inputUri = uri,
            inputName = name,
            displayName = "$name · ${preset.compareLevel.title}",
            preset = preset,
        )
        TranscodeQueue.add(job)
        appendLog("В очередь добавлено: ${job.displayName} [${job.preset.engine.shortName}]")
        return true
    }

    fun clearDone() {
        TranscodeQueue.clearDone()
        appendLog("История выполненных очищена.")
    }

    fun clearFinished() {
        if (_ui.value.queueRunning) {
            appendLog("Очистка: сначала останови очередь.")
            return
        }
        TranscodeQueue.clearFinished()
    }

    /**
     * Старт очереди: кодирование уезжает в [TranscodeService], ViewModel только
     * поднимает сервис. Само задание (в SAF-URL, ffmpeg-команда, прогресс) —
     * в сервисе, см. TranscodeService.runOneJob.
     */
    fun startQueue() {
        if (_ui.value.queueRunning) return
        if (!TranscodeQueue.hasQueued()) {
            appendLog("Старт: в очереди нет заданий со статусом «В очереди».")
            return
        }
        TranscodeService.start(getApplication())
    }

    fun cancelQueue() {
        if (!_ui.value.queueRunning) return
        TranscodeService.cancel(getApplication())
    }

    fun appendLog(line: String) {
        TranscodeQueue.appendLog(line)
    }

    /**
     * Расширенная диагностика: версия, есть ли mediacodec/libx264/libx265,
     * счётчики энкодеров/декодеров, полные -h обоих HW-энкодеров.
     */
    fun runEngineDiagnostics() {
        appendLog("Диагностика движка…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val version = FFmpegKit.execute("-version")
                val firstLine = version.getOutput()?.lineSequence()?.firstOrNull() ?: "?"
                appendLog("ffmpeg: $firstLine (rc=${version.getReturnCode()?.value})")

                val enc = FFmpegKit.execute("-hide_banner -encoders").getOutput().orEmpty()
                val encLines = enc.lineSequence().toList()
                val videoEnc = encLines.filter { it.startsWith(" V") }
                val names = encLines.map { it.trim().split(Regex("\\s+")).getOrNull(1).orEmpty() }
                    .filter { it.isNotEmpty() && it != "----" }.toSet()
                EncoderCatalog.publish(names)
                appendLog("энкодеров всего строк: ${encLines.size}, видео: ${videoEnc.size}")
                appendLog("видео-энкодеры:\n" + videoEnc.joinToString("\n"))
                listOf("h264_mediacodec", "hevc_mediacodec", "libx264", "libx265", " aac ").forEach { key ->
                    appendLog("has[$key] = ${encLines.any { it.contains(key) }}")
                }

                val dec = FFmpegKit.execute("-hide_banner -decoders").getOutput().orEmpty()
                val decLines = dec.lineSequence().toList()
                appendLog("декодеров строк: ${decLines.size}, mediacodec: ${decLines.count { it.contains("mediacodec") }}")

                listOf("h264_mediacodec", "hevc_mediacodec").forEach { name ->
                    val h = FFmpegKit.execute("-hide_banner -h encoder=$name").getOutput().orEmpty()
                    val kept = h.lineSequence()
                        .filter { l ->
                            l.contains("bitrate_mode") || l.contains(" -cq") ||
                                l.contains("qp_") || l.contains("Encoder ") || l.contains("not found")
                        }
                        .take(25)
                        .toList()
                    appendLog("$name:\n" + kept.joinToString("\n").ifEmpty { "(пусто)" })
                }
            } catch (e: Exception) {
                appendLog("диагностика упала: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        inputSafUrl?.let { runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(it) } }
        super.onCleared()
    }

    fun engineAvailable(engine: TranscodeEngine): Boolean = EncoderCatalog.available(engine)

    private fun prettyStreams(info: com.arthenica.ffmpegkit.MediaInformation): String =
        info.getStreams()?.joinToString("\n") { s ->
            when (s.getType()) {
                "video" -> "видео: ${s.getCodec()} ${s.getWidth()}x${s.getHeight()} ${s.getAverageFrameRate()} fps"
                "audio" -> "аудио: ${s.getCodec()} ${s.getSampleRate()} Hz"
                else -> "${s.getType()}: ${s.getCodec()}"
            }
        }.orEmpty()

    private companion object {
        /** Статусы, при которых задание ещё можно увидеть в очереди как ждущее. */
        val PENDING_STATUSES = setOf(JobStatus.QUEUED, JobStatus.RUNNING)
    }
}

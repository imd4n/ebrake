package xyz.imd4n.ebrake.transcode

import android.app.Application
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.TranscodeFormat.PREVIEW_SEC
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatBytes
import xyz.imd4n.ebrake.transcode.TranscodeFormat.formatSec
import xyz.imd4n.ebrake.transcode.TranscodeFormat.lastLogLines

data class TranscodeUiState(
    val inputUri: Uri? = null,
    /**
     * Пусто = файл не выбран. Раньше здесь стоял русский текст «Файл не
     * выбран», но состояние создаётся до того, как доступен Context: строка
     * зафиксировала бы язык навсегда. Экран сам берёт подпись из ресурсов,
     * когда имя пустое.
     */
    val inputName: String = "",
    val mediaInfo: String = "",
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
    val inputFolderLabel: String = "",
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

    private val context: Application get() = getApplication()

    /**
     * Форма из ресурсов с правильной формой слова. Количественное «с ошибкой»,
     * «задание» и т. п. отличаются по числу, и склейка через `str` напечатала
     * «с ошибкой 5».
     */
    private fun plural(
        @PluralsRes id: Int,
        quantity: Int,
        vararg args: Any?,
    ): String = context.resources.getQuantityString(id, quantity, *args)

    /**
     * Строка из ресурсов. Логи и подписи состояния живут вне Compose, поэтому
     * `stringResource` там недоступен — но язык здесь всё равно правильный:
     * берём его у текущей конфигурации, а не у системы.
     *
     * Аргументы nullable и правда nullable (например `e.message` у исключения):
     * `getString` сам печатает «null», и это честнее, чем падать на ровном месте.
     */
    private fun str(@StringRes id: Int, vararg args: Any?): String =
        context.getString(id, *args)

    /** Многоразовый SAF-URL входа (ffkitsaf://), освобождается при смене файла. */
    private var inputSafUrl: String? = null

    init {
        // Одноразовые SAF-URL (дефолт библиотеки) не открываются в executeAsync:
        // на устройстве падало с "Failed to open SAF id N: callback returned 0".
        // Делаем все URL многоразовыми и освобождаем вручную после каждого задания.
        // (TranscodeService ставит то же самое — сервис может стартовать первым.)
        FFmpegKitConfig.setSafUrlsReusable(true)

        // Приветствие в лог: из ресурсов, иначе строка осталась бы русской.
        // ViewModel переживает поворот экрана, а TranscodeQueue — процесс,
        // поэтому дубликат не появляется.
        TranscodeQueue.appendLog(str(R.string.log_engine_ready))

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
        appendLog(str(R.string.log_output_folder, OutputTarget.label(context)))
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
        appendLog(str(R.string.log_input_folder, InputTarget.label(context)))
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
                inputName = "",
                mediaInfo = "",
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
                mediaInfo = str(R.string.input_reading),
                durationSec = 0.0,
                previews = emptyList(),
            )
        }
        appendLog(str(R.string.log_input, name))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val saf = FFmpegKitConfig.getSafParameterForRead(ctx, uri, true)
                inputSafUrl = saf
                val session = FFprobeKit.getMediaInformation(saf)
                val info = session.getMediaInformation()
                if (info == null) {
                    _ui.update {
                        it.copy(
                            mediaInfo = str(R.string.meta_no_media_information) +
                                "\n" + lastLogLines(context, session.getOutput(), 5),
                        )
                    }
                    appendLog(str(R.string.log_ffprobe_none))
                    return@launch
                }
                val dur = info.getDuration()?.toDoubleOrNull() ?: 0.0
                val streams = prettyStreams(info)
                val locale = TranscodeFormat.currentLocale(ctx)
                _ui.update {
                    it.copy(
                        durationSec = dur,
                        mediaInfo = buildString {
                            append(
                                str(
                                    R.string.meta_format,
                                    info.getFormat(),
                                    info.getLongFormat(),
                                ),
                            )
                            append("\n")
                            append(
                                str(
                                    R.string.meta_duration,
                                    formatSec(dur, locale),
                                    formatBytes(ctx, info.getSize()?.toLongOrNull() ?: 0),
                                ),
                            )
                            append("\n")
                            append(str(R.string.meta_bitrate, info.getBitrate() ?: "?"))
                            append("\n")
                            append(streams)
                        },
                    )
                }
                appendLog(
                    str(
                        R.string.log_ffprobe_summary,
                        info.getFormat(),
                        formatSec(dur, locale),
                    ),
                )
            } catch (e: Exception) {
                _ui.update { it.copy(mediaInfo = str(R.string.meta_read_error, e.message)) }
                appendLog(str(R.string.log_ffprobe_failed, e.message))
            }
        }
    }

    fun setEngine(engine: TranscodeEngine) {
        _ui.update { it.copy(preset = it.preset.copy(engine = engine), previews = emptyList()) }
        appendLog(str(R.string.log_engine_set, str(engine.titleRes)))
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
            appendLog(str(R.string.log_compare_no_file))
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
            EncoderCatalog.ensureDetected(getApplication())
            val wanted = state.preset.engine
            val engine = EncoderCatalog.effective(wanted)
            if (engine != wanted) {
                appendLog(
                    str(
                        R.string.log_engine_missing,
                        wanted.ffmpegVideoCodec,
                        engine.ffmpegVideoCodec,
                    ),
                )
            }
            val seek = ((state.durationSec - PREVIEW_SEC) / 2).coerceAtLeast(0.0).toInt()
            appendLog(
                str(R.string.log_compare_start, seek, str(engine.titleRes)),
            )
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
                    it.copy(
                        compareStatus = "${str(level.titleRes)} (${idx + 1}/3)…",
                    )
                }
                // Команду в лог не пишем: она длинная и перебивает ход событий,
                // а по ошибке ffmpeg всё равно отдаёт свой хвост вывода.
                appendLog(str(R.string.log_compare_level, str(level.titleRes), idx + 1))
                val session = FFmpegKit.execute(cmd)
                val rc = session.getReturnCode()
                if (ReturnCode.isSuccess(rc) && outFile.exists()) {
                    results += PreviewResult(level, outFile.length(), cmd, outFile.absolutePath)
                    appendLog(
                        str(
                            R.string.log_level_done,
                            str(level.titleRes),
                            formatBytes(getApplication<Application>(), outFile.length()),
                        ),
                    )
                } else {
                    appendLog(
                        str(R.string.log_level_failed, str(level.titleRes), rc?.value) +
                            "\n" + lastLogLines(context, session.getOutput(), 8),
                    )
                }
                _ui.update { it.copy(compareProgress01 = (idx + 1) / 3f, previews = results.toList()) }
            }
            val done = ui.value.compareRunning
            _ui.update { it.copy(compareRunning = false, compareStatus = "") }
            appendLog(
                if (done) str(R.string.log_compare_done) else str(R.string.log_compare_cancelled),
            )
        }
    }

    fun cancelCompare() {
        if (!_ui.value.compareRunning) return
        FFmpegKit.cancel()
        _ui.update { it.copy(compareRunning = false, compareStatus = "") }
        appendLog(str(R.string.log_compare_cancel_requested))
    }

    fun enqueueSelected() {
        val state = _ui.value
        val uri = state.inputUri ?: run {
            appendLog(str(R.string.log_queue_nothing))
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
            appendLog(str(R.string.log_import_too_few))
            return
        }
        var added = 0
        rest.forEach { picked ->
            if (addJob(picked.uri, picked.name, state.preset)) added++
        }
        appendLog(
            plural(
                R.plurals.log_import_done,
                added,
                added,
                state.selection.size - 1,
                // Имя первого ролика: в этот момент он точно есть (rest не пуст).
                state.inputName.ifEmpty { str(R.string.input_none_selected) },
            ),
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
            appendLog(str(R.string.log_already_queued, name))
            return false
        }
        val job = TranscodeJob(
            id = TranscodeQueue.nextId(),
            inputUri = uri,
            inputName = name,
            displayName = name,
            preset = preset,
        )
        TranscodeQueue.add(job)
        appendLog(
            str(
                R.string.log_enqueued,
                job.displayName,
                str(job.preset.engine.shortNameRes),
            ),
        )
        return true
    }

    fun clearDone() {
        TranscodeQueue.clearDone()
        appendLog(str(R.string.log_done_cleared))
    }

    fun clearFinished() {
        if (_ui.value.queueRunning) {
            appendLog(str(R.string.log_clear_while_running))
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
            appendLog(str(R.string.log_start_empty))
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
        appendLog(str(R.string.log_diagnostics_start))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val version = FFmpegKit.execute("-version")
                val firstLine = version.getOutput()?.lineSequence()?.firstOrNull() ?: "?"
                appendLog(
                    str(
                        R.string.log_diagnostics_ffmpeg,
                        firstLine,
                        version.getReturnCode()?.value,
                    ),
                )

                val enc = FFmpegKit.execute("-hide_banner -encoders").getOutput().orEmpty()
                val encLines = enc.lineSequence().toList()
                val videoEnc = encLines.filter { it.startsWith(" V") }
                val names = encLines.map { it.trim().split(Regex("\\s+")).getOrNull(1).orEmpty() }
                    .filter { it.isNotEmpty() && it != "----" }.toSet()
                EncoderCatalog.publish(names)
                appendLog(
                    str(
                        R.string.log_diagnostics_encoder_count,
                        encLines.size,
                        videoEnc.size,
                    ),
                )
                appendLog(str(R.string.log_diagnostics_video_encoders) + "\n" + videoEnc.joinToString("\n"))
                listOf("h264_mediacodec", "hevc_mediacodec", "libx264", "libx265", " aac ").forEach { key ->
                    appendLog("has[$key] = ${encLines.any { it.contains(key) }}")
                }

                val dec = FFmpegKit.execute("-hide_banner -decoders").getOutput().orEmpty()
                val decLines = dec.lineSequence().toList()
                appendLog(
                    str(
                        R.string.log_diagnostics_decoder_count,
                        decLines.size,
                        decLines.count { it.contains("mediacodec") },
                    ),
                )

                listOf("h264_mediacodec", "hevc_mediacodec").forEach { name ->
                    val h = FFmpegKit.execute("-hide_banner -h encoder=$name").getOutput().orEmpty()
                    val kept = h.lineSequence()
                        .filter { l ->
                            l.contains("bitrate_mode") || l.contains(" -cq") ||
                                l.contains("qp_") || l.contains("Encoder ") || l.contains("not found")
                        }
                        .take(25)
                        .toList()
                    appendLog(
                        "$name:\n" +
                            kept.joinToString("\n").ifEmpty { str(R.string.log_empty) },
                    )
                }
            } catch (e: Exception) {
                appendLog(str(R.string.log_diagnostics_failed, e.message))
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
                "video" -> str(
                    R.string.stream_video,
                    s.getCodec(),
                    s.getWidth(),
                    s.getHeight(),
                    s.getAverageFrameRate(),
                )
                "audio" -> str(R.string.stream_audio, s.getCodec(), s.getSampleRate())
                else -> str(R.string.stream_other, s.getType(), s.getCodec())
            }
        }.orEmpty()

    private companion object {
        /** Статусы, при которых задание ещё можно увидеть в очереди как ждущее. */
        val PENDING_STATUSES = setOf(JobStatus.QUEUED, JobStatus.RUNNING)
    }
}

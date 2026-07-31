package com.voicetomessenger.capture

import android.content.Context
import com.voicetomessenger.R
import com.voicetomessenger.asr.AudioRecorder
import com.voicetomessenger.asr.OnDeviceRecognizer
import com.voicetomessenger.data.AsrMode
import com.voicetomessenger.data.NotesRepository
import com.voicetomessenger.data.ProcessingMode
import com.voicetomessenger.data.Settings
import com.voicetomessenger.data.SettingsRepository
import com.voicetomessenger.localizedString
import com.voicetomessenger.net.BackendClient
import com.voicetomessenger.processing.LocalProcessor
import com.voicetomessenger.processing.ProcessRequest
import com.voicetomessenger.processing.ServerProcessor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что происходит с текущей диктовкой. */
sealed interface CaptureState {
    data object Idle : CaptureState

    /**
     * Идёт запись: [partial] — гипотеза распознавания, [level] — громкость 0..1.
     *
     * [canPause] верно только для серверного распознавания: там пишется файл и
     * `MediaRecorder` умеет паузу. Системный `SpeechRecognizer` паузы не поддерживает.
     */
    data class Listening(
        val partial: String = "",
        val level: Float = 0f,
        val paused: Boolean = false,
        val canPause: Boolean = false,
    ) : CaptureState

    /** Аудио ушло на серверный Whisper. */
    data object Transcribing : CaptureState

    /** Сырой текст получен, готовое сообщение набирается по кускам. */
    data class Processing(val raw: String, val text: String) : CaptureState

    data class Done(val raw: String, val text: String) : CaptureState

    data class Failed(val message: String) : CaptureState
}

/**
 * Один проход: микрофон → текст → готовое сообщение.
 *
 * Живёт в [scope] владельца (ViewModel или сервис пузыря) и не знает ничего про UI,
 * поэтому экран приложения и пузырь используют одну и ту же логику.
 */
class VoicePipeline(
    private val scope: CoroutineScope,
    // Тексты [CaptureState.Failed] показываются как есть — и на экране, и в пузыре.
    private val context: Context,
    private val settings: SettingsRepository,
    private val backend: BackendClient,
    private val notes: NotesRepository,
    private val recognizer: OnDeviceRecognizer,
    private val recorder: AudioRecorder,
) {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var processJob: Job? = null
    private var lastRaw: String = ""
    private var activeMode: AsrMode = AsrMode.ON_DEVICE

    /**
     * Готовые тексты за текущую сессию диктовки.
     *
     * Перебор стилей и языков — главный источник лишних обращений к LLM: пользователь
     * переключает чипы туда-сюда, и каждый тап стоил бы отдельной генерации. Уже
     * посчитанный вариант достаём из памяти, пока не началась новая запись.
     */
    private val results = mutableMapOf<ResultKey, String>()
    private var currentKey: ResultKey? = null

    /**
     * Кредиты, потраченные на текущее сообщение: распознавание плюс каждая генерация.
     *
     * Именно сумма за сессию, а не за последний прогон: перебор стилей стоит денег,
     * и честнее показывать, во что обошлось сообщение целиком. Обнуляется новой записью.
     * Ноль означает «ничего не потрачено» — локальная обработка или старый бэкенд.
     */
    private val _spentCredits = MutableStateFlow(0)
    val spentCredits: StateFlow<Int> = _spentCredits.asStateFlow()

    /** Уже посчитанные id: один и тот же запрос не должен прибавиться дважды. */
    private val countedUsage = mutableSetOf<String>()

    /**
     * Спрашивает стоимость запроса и прибавляет её к счётчику. Молча, в фоне.
     *
     * Всё, что трогает [countedUsage] и счётчик, делается на главном потоке: вызывают
     * отсюда и с главного (после распознавания), и с IO (из колбэка стрима), а обычный
     * set и `value +=` не атомарны — одновременное прибавление потеряло бы одно из двух.
     */
    private fun countUsage(usageId: String?) {
        if (usageId == null) return
        scope.launch(Dispatchers.Main.immediate) {
            if (!countedUsage.add(usageId)) return@launch
            backend.usageCredits(usageId)?.let { credits ->
                _spentCredits.update { it + credits }
            }
        }
    }

    private data class ResultKey(
        val raw: String,
        val styleId: String,
        val targetLang: String,
        val asList: Boolean,
        val emoji: Boolean,
        val processingMode: ProcessingMode,
    )

    val isCapturing: Boolean
        get() = _state.value is CaptureState.Listening

    private val isPaused: Boolean
        get() = (_state.value as? CaptureState.Listening)?.paused == true

    fun toggle() = when {
        !isCapturing -> start()
        isPaused -> resume()
        else -> stop()
    }

    fun start() {
        if (isCapturing) return
        captureJob?.cancel()
        processJob?.cancel()
        forgetResults()
        _state.value = CaptureState.Listening()

        captureJob = scope.launch(Dispatchers.Main.immediate) {
            val current = settings.current()
            activeMode = current.asrMode
            updateListening { it.copy(canPause = activeMode == AsrMode.SERVER) }
            when (activeMode) {
                AsrMode.ON_DEVICE -> listenOnDevice(current.asrLanguage)
                AsrMode.SERVER -> recordForServer()
            }
        }
    }

    /** Пауза записи. Работает только в серверном режиме — см. [CaptureState.Listening.canPause]. */
    fun pause() {
        val current = _state.value
        if (current !is CaptureState.Listening || current.paused || !current.canPause) return
        if (!recorder.pause()) return
        _state.value = current.copy(paused = true, level = 0f)
    }

    fun resume() {
        val current = _state.value
        if (current !is CaptureState.Listening || !current.paused) return
        if (!recorder.resume()) return
        _state.value = current.copy(paused = false)
    }

    fun stop() {
        if (!isCapturing) return
        when (activeMode) {
            // Итоговый текст придёт событием Final — captureJob всё ещё ждёт его.
            AsrMode.ON_DEVICE -> recognizer.stop()
            AsrMode.SERVER -> {
                captureJob?.cancel()
                captureJob = null
                processJob = scope.launch(Dispatchers.Main.immediate) { transcribeAndProcess() }
            }
        }
    }

    /** Переобработать последнюю расшифровку — например, после смены стиля. */
    fun reprocess(styleId: String? = null) {
        val raw = lastRaw
        if (raw.isBlank()) return
        processJob?.cancel()
        processJob = scope.launch(Dispatchers.Main.immediate) {
            styleId?.let { settings.setStyle(it) }

            val key = keyFor(raw, settings.current())
            val ready = results[key]
            if (ready != null) {
                currentKey = key
                _state.value = CaptureState.Done(raw, ready)
                return@launch
            }

            // Пока пользователь перебирает чипы, запрос не уходит: следующий вызов
            // отменит этот job раньше, чем истечёт задержка.
            delay(REPROCESS_DEBOUNCE_MS)
            runProcessing(raw)
        }
    }

    /** Правки пользователя в готовом тексте. */
    fun editResult(text: String) {
        val current = _state.value
        if (current !is CaptureState.Done) return
        _state.value = current.copy(text = text)
        // Правку помним вместе с вариантом: вернувшись к этому стилю, пользователь
        // увидит свой текст, а не исходную генерацию.
        currentKey?.let { results[it] = text }
    }

    fun reset() {
        captureJob?.cancel()
        processJob?.cancel()
        captureJob = null
        processJob = null
        recognizer.cancel()
        recorder.cancel()
        lastRaw = ""
        forgetResults()
        _state.value = CaptureState.Idle
    }

    /** Новая диктовка — прошлые варианты уже не пригодятся. */
    private fun forgetResults() {
        results.clear()
        currentKey = null
        // Счётчик кредитов живёт ровно столько же, сколько кэш результатов: и то и другое
        // относится к одному сообщению. Вызывается и из start(), и из reset().
        _spentCredits.value = 0
        countedUsage.clear()
    }

    fun release() {
        reset()
        recognizer.release()
    }

    private suspend fun listenOnDevice(languageTag: String) {
        // Подписываемся до старта распознавателя, иначе первые события потеряются.
        val terminal = recognizer.events
            .onSubscription { recognizer.start(languageTag) }
            .onEach { event ->
                when (event) {
                    is OnDeviceRecognizer.Event.Partial -> updateListening { it.copy(partial = event.text) }
                    is OnDeviceRecognizer.Event.Level -> updateListening { it.copy(level = event.value) }
                    else -> Unit
                }
            }
            .first { it is OnDeviceRecognizer.Event.Final || it is OnDeviceRecognizer.Event.Failed }

        when (terminal) {
            is OnDeviceRecognizer.Event.Final -> runProcessing(terminal.text)
            is OnDeviceRecognizer.Event.Failed -> _state.value = CaptureState.Failed(terminal.message)
            else -> Unit
        }
    }

    private suspend fun recordForServer() {
        val started = withContext(Dispatchers.IO) { recorder.start() }
        if (!started) {
            _state.value = CaptureState.Failed(context.localizedString(R.string.err_record_start))
            return
        }
        while (recorder.isRecording) {
            updateListening { it.copy(level = recorder.level()) }
            delay(LEVEL_POLL_MS)
        }
    }

    private suspend fun transcribeAndProcess() {
        _state.value = CaptureState.Transcribing
        val file = withContext(Dispatchers.IO) { recorder.stop() }
        if (file == null) {
            _state.value = CaptureState.Failed(context.localizedString(R.string.err_record_empty))
            return
        }
        val transcription = try {
            backend.transcribe(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value =
                CaptureState.Failed(e.message ?: context.localizedString(R.string.err_transcribe))
            return
        } finally {
            // Аудио не храним — удаляем сразу после отправки, даже если запрос отменили.
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
        countUsage(transcription.usageId)

        val raw = transcription.text
        if (raw.isBlank()) {
            _state.value = CaptureState.Failed(context.localizedString(R.string.err_nothing_recognized))
            return
        }
        runProcessing(raw)
    }

    private suspend fun runProcessing(raw: String) {
        lastRaw = raw
        val current = settings.current()
        val key = keyFor(raw, current)
        currentKey = key

        results[key]?.let { ready ->
            _state.value = CaptureState.Done(raw, ready)
            return
        }

        val processor = when (current.processingMode) {
            ProcessingMode.SERVER -> ServerProcessor(backend)
            ProcessingMode.LOCAL -> LocalProcessor()
        }
        val request = buildRequest(raw, current)

        _state.value = CaptureState.Processing(raw, "")
        val builder = StringBuilder()
        try {
            // Считаем расход сразу по заголовку, не дожидаясь конца потока: если генерацию
            // прервут на середине, токены всё равно потрачены и должны попасть в счётчик.
            processor.process(request, ::countUsage).collect { chunk ->
                builder.append(chunk)
                _state.value = CaptureState.Processing(raw, builder.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = CaptureState.Failed(e.message ?: context.localizedString(R.string.err_process))
            return
        }

        val result = builder.toString().trim()
        _state.value = if (result.isEmpty()) {
            CaptureState.Failed(context.localizedString(R.string.err_empty_result))
        } else {
            results[key] = result
            CaptureState.Done(raw, result)
        }
    }

    private fun keyFor(raw: String, settings: Settings) =
        ResultKey(raw, settings.styleId, settings.targetLang, settings.asList, settings.emoji, settings.processingMode)

    /** Собирает запрос к обработчику. Для «Моего стиля» подтягивает примеры из заметок. */
    private suspend fun buildRequest(raw: String, s: Settings): ProcessRequest {
        // Список — это перечень, тон к нему не применяем: пустой стиль → сервер не добавит тон.
        val styleId = if (s.asList) "" else s.styleId
        val examples = if (styleId == MY_STYLE_ID) {
            notes.recentTexts(EXAMPLE_LIMIT).filter { it.length in EXAMPLE_MIN_LEN..EXAMPLE_MAX_LEN }
        } else {
            emptyList()
        }
        return ProcessRequest(
            raw = raw,
            styleId = styleId,
            targetLang = s.targetLang,
            asList = s.asList,
            emoji = s.emoji,
            examples = examples,
        )
    }

    private fun updateListening(transform: (CaptureState.Listening) -> CaptureState.Listening) {
        val current = _state.value
        if (current is CaptureState.Listening) _state.value = transform(current)
    }

    private companion object {
        const val LEVEL_POLL_MS = 100L

        /** Пауза перед запросом, чтобы быстрый перебор чипов не улетал на сервер целиком. */
        const val REPROCESS_DEBOUNCE_MS = 350L

        /** id стиля, который берёт тон из примеров-заметок пользователя. */
        const val MY_STYLE_ID = "mine"

        /** Сколько последних заметок брать как примеры и какой длины (в символах). */
        const val EXAMPLE_LIMIT = 5
        const val EXAMPLE_MIN_LEN = 3
        const val EXAMPLE_MAX_LEN = 400
    }
}

package com.voicetomessenger.capture

import com.voicetomessenger.asr.AudioRecorder
import com.voicetomessenger.asr.OnDeviceRecognizer
import com.voicetomessenger.data.AsrMode
import com.voicetomessenger.data.ProcessingMode
import com.voicetomessenger.data.SettingsRepository
import com.voicetomessenger.net.BackendClient
import com.voicetomessenger.processing.LocalProcessor
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что происходит с текущей диктовкой. */
sealed interface CaptureState {
    data object Idle : CaptureState

    /** Идёт запись: [partial] — гипотеза распознавания, [level] — громкость 0..1. */
    data class Listening(val partial: String = "", val level: Float = 0f) : CaptureState

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
    private val settings: SettingsRepository,
    private val backend: BackendClient,
    private val recognizer: OnDeviceRecognizer,
    private val recorder: AudioRecorder,
) {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var processJob: Job? = null
    private var lastRaw: String = ""
    private var activeMode: AsrMode = AsrMode.ON_DEVICE

    val isCapturing: Boolean
        get() = _state.value is CaptureState.Listening

    fun toggle() = if (isCapturing) stop() else start()

    fun start() {
        if (isCapturing) return
        captureJob?.cancel()
        processJob?.cancel()
        _state.value = CaptureState.Listening()

        captureJob = scope.launch(Dispatchers.Main.immediate) {
            val current = settings.current()
            activeMode = current.asrMode
            when (activeMode) {
                AsrMode.ON_DEVICE -> listenOnDevice(current.asrLanguage)
                AsrMode.SERVER -> recordForServer()
            }
        }
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
            runProcessing(raw)
        }
    }

    /** Правки пользователя в готовом тексте. */
    fun editResult(text: String) {
        val current = _state.value
        if (current is CaptureState.Done) _state.value = current.copy(text = text)
    }

    fun reset() {
        captureJob?.cancel()
        processJob?.cancel()
        captureJob = null
        processJob = null
        recognizer.cancel()
        recorder.cancel()
        lastRaw = ""
        _state.value = CaptureState.Idle
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
            _state.value = CaptureState.Failed("Не удалось начать запись — проверь доступ к микрофону")
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
            _state.value = CaptureState.Failed("Запись пустая или слишком короткая")
            return
        }
        val raw = try {
            backend.transcribe(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = CaptureState.Failed(e.message ?: "Не удалось расшифровать запись")
            return
        } finally {
            // Аудио не храним — удаляем сразу после отправки, даже если запрос отменили.
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }

        if (raw.isBlank()) {
            _state.value = CaptureState.Failed("Ничего не распознано — попробуй ещё раз")
            return
        }
        runProcessing(raw)
    }

    private suspend fun runProcessing(raw: String) {
        lastRaw = raw
        val current = settings.current()
        val processor = when (current.processingMode) {
            ProcessingMode.SERVER -> ServerProcessor(backend)
            ProcessingMode.LOCAL -> LocalProcessor()
        }

        _state.value = CaptureState.Processing(raw, "")
        val builder = StringBuilder()
        try {
            processor.process(raw, current.styleId, current.targetLang).collect { chunk ->
                builder.append(chunk)
                _state.value = CaptureState.Processing(raw, builder.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = CaptureState.Failed(e.message ?: "Не удалось обработать текст")
            return
        }

        val result = builder.toString().trim()
        _state.value = if (result.isEmpty()) {
            CaptureState.Failed("Сервер вернул пустой ответ")
        } else {
            CaptureState.Done(raw, result)
        }
    }

    private fun updateListening(transform: (CaptureState.Listening) -> CaptureState.Listening) {
        val current = _state.value
        if (current is CaptureState.Listening) _state.value = transform(current)
    }

    private companion object {
        const val LEVEL_POLL_MS = 100L
    }
}

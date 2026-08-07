package com.voicetomessenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voicetomessenger.AppContainer
import com.voicetomessenger.R
import com.voicetomessenger.VoiceApp
import com.voicetomessenger.asr.LivePcmRecorder
import com.voicetomessenger.data.LiveLanguage
import com.voicetomessenger.live.SpeechGate
import com.voicetomessenger.live.TranslationPlayer
import com.voicetomessenger.localizedString
import com.voicetomessenger.net.LiveConnection
import com.voicetomessenger.net.LiveEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Одна реплика в ленте перевода. */
data class LiveLine(
    val id: Int,
    val original: String,
    val translation: String? = null,
    /** Говорят на том же языке, на который переводим, — переводить было нечего. */
    val sameLanguage: Boolean = false,
)

enum class LiveStatus {
    /** Микрофон выключен. */
    IDLE,

    /** Сокет открыт, ждём готовности распознавания. */
    CONNECTING,

    /** Слушаем. */
    RUNNING,

    /** Нажали «стоп»: сервер договаривает последнюю фразу. */
    FINISHING,
}

data class LiveUiState(
    val status: LiveStatus = LiveStatus.IDLE,
    val targetLang: String = LiveLanguage.DEFAULT,
    /** Язык говорящего, заданный человеком. Пусто — определяет модель. */
    val sourceLang: String = LiveLanguage.AUTO,
    val speak: Boolean = true,
    /** Код языка говорящего, определённый моделью. */
    val detectedLanguage: String? = null,
    /** Незакрытая фраза — то, что распознано, но ещё не переведено. */
    val partial: String = "",
    val lines: List<LiveLine> = emptyList(),
    val elapsedSeconds: Int = 0,
    val limitSeconds: Int = 0,
    val credits: Int = 0,
    /** Громкость для индикатора, 0..1. */
    val level: Float = 0f,
    /** Ворота открыты: речь уходит в распознавание (и тарифицируется). */
    val hearing: Boolean = false,
    val speaking: Boolean = false,
    val headset: Boolean = true,
) {
    val active: Boolean get() = status != LiveStatus.IDLE

    /** Сколько осталось до автостопа. */
    val remainingSeconds: Int get() = (limitSeconds - elapsedSeconds).coerceAtLeast(0)
}

/**
 * Экран живого перевода.
 *
 * Своя ViewModel, а не общая [MainViewModel]: живой перевод держит собственный
 * микрофон и сокет, и переживать закрытие экрана он не должен — сессия обязана
 * закончиться вместе с ним, иначе счётчик продолжит крутиться незаметно.
 */
class LiveViewModel(private val container: AppContainer) : ViewModel() {

    private val recorder = LivePcmRecorder()
    private val player = TranslationPlayer(container.appContext)
    private val client = container.liveTranslate

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var connection: LiveConnection? = null
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = container.settings.current()
            _state.update {
                it.copy(
                    targetLang = settings.liveTargetLang,
                    sourceLang = settings.liveSourceLang,
                    speak = settings.liveSpeak,
                    headset = player.headsetConnected(),
                )
            }
        }
        viewModelScope.launch {
            player.playing.collect { speaking -> _state.update { it.copy(speaking = speaking) } }
        }
    }

    fun onMicClick() {
        if (_state.value.active) stop() else start()
    }

    fun setTargetLang(code: String) {
        _state.update { it.copy(targetLang = code) }
        viewModelScope.launch { container.settings.setLiveTargetLang(code) }
        push()
    }

    /** Пустой код — язык говорящего снова определяет модель. */
    fun setSourceLang(code: String) {
        _state.update { it.copy(sourceLang = code) }
        viewModelScope.launch { container.settings.setLiveSourceLang(code) }
        push()
    }

    fun setSpeak(enabled: Boolean) {
        // Заодно пересматриваем наушники: их обычно и подключают, увидев подсказку.
        _state.update { it.copy(speak = enabled, headset = player.headsetConnected()) }
        viewModelScope.launch { container.settings.setLiveSpeak(enabled) }
        push()
        if (!enabled) player.clear()
    }

    /** Досылает текущие настройки в открытую сессию — переподключаться не нужно. */
    private fun push() {
        val state = _state.value
        connection?.reconfigure(state.targetLang, state.sourceLang, state.speak)
    }

    private fun start() {
        if (sessionJob?.isActive == true) return
        _state.update {
            it.copy(
                status = LiveStatus.CONNECTING,
                lines = emptyList(),
                partial = "",
                detectedLanguage = null,
                elapsedSeconds = 0,
                credits = 0,
                headset = player.headsetConnected(),
            )
        }
        sessionJob = viewModelScope.launch { runSession() }
    }

    /**
     * Останавливает сессию, но не рвёт соединение: сервер ещё договаривает последнюю
     * фразу, дозвучивает её и дописывает расход. Микрофон при этом умолкает сразу —
     * его поток проверяет статус на каждой порции.
     */
    fun stop() {
        if (!_state.value.active) return
        _state.update { it.copy(status = LiveStatus.FINISHING, partial = "") }
        connection?.stop()
    }

    private suspend fun runSession() {
        val current = _state.value
        val live = runCatching { client.open(current.targetLang, current.sourceLang, current.speak) }
            .getOrElse { error ->
                _messages.tryEmit(error.message ?: string(R.string.err_live_connection))
                finish()
                return
            }
        connection = live
        // Успели нажать «стоп», пока открывалось соединение.
        if (_state.value.status == LiveStatus.FINISHING) live.stop()

        // Слушаем сервер в отдельной корутине: поток микрофона ниже занимает свою.
        // takeWhile завершает сбор на «done» — иначе join() ниже висел бы вечно:
        // SharedFlow сам по себе не заканчивается.
        val events = viewModelScope.launch {
            live.events.takeWhile { it !is LiveEvent.Done }.collect { onEvent(it) }
            onEvent(LiveEvent.Done)
        }

        try {
            streamMicrophone(live)
        } catch (e: Exception) {
            _messages.tryEmit(e.message ?: string(R.string.err_live_mic))
            live.stop()
        }

        // Ждём прощания сервера, но не бесконечно: если он замолчал, экран всё равно
        // обязан разблокироваться.
        if (withTimeoutOrNull(FAREWELL_TIMEOUT_MS) { events.join() } == null) {
            events.cancel()
            live.close()
        }
        connection = null
        finish()
    }

    /**
     * Гоняет звук в сокет, пока сессия жива.
     *
     * Тишину не отправляем ([SpeechGate]) — она стоит тех же денег, что и речь.
     * Первые порции после открытия ворот берём из кольца: без них начало фразы
     * («Здравствуйте» → «дравствуйте») уезжало бы в модель обрезанным.
     */
    private suspend fun streamMicrophone(live: LiveConnection) {
        val gate = SpeechGate()
        val preroll = ArrayDeque<ByteArray>()
        var wasOpen = false

        recorder.chunks().takeWhile { _state.value.active && !finishing() }.collect { chunk ->
            val state = _state.value
            // Без наушников озвучка попадает обратно в микрофон, и перевод переводит
            // сам себя. На время реплики глушим вход.
            val muted = state.speaking && !state.headset
            // Ворота крутим и до готовности сессии: пока идёт подключение, они успевают
            // оценить фоновый шум, зато оплачиваемый звук раньше времени не уходит.
            val open = gate.update(chunk.rms) && !muted && state.status == LiveStatus.RUNNING

            if (open) {
                if (!wasOpen) preroll.forEach { live.sendAudio(it) }
                preroll.clear()
                live.sendAudio(chunk.bytes)
            } else {
                preroll.addLast(chunk.bytes)
                while (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
            }
            wasOpen = open

            _state.update { it.copy(level = chunk.rms, hearing = open) }
            tick()
        }
    }

    /** Секундомер сессии. Считаем по порциям звука: они идут ровно по 100 мс. */
    private var chunkCounter = 0

    private fun tick() {
        chunkCounter++
        val perSecond = 1000 / LivePcmRecorder.CHUNK_MS
        if (chunkCounter % perSecond != 0) return
        val seconds = chunkCounter / perSecond
        _state.update { it.copy(elapsedSeconds = seconds) }
    }

    private fun onEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.Ready -> _state.update {
                it.copy(status = LiveStatus.RUNNING, limitSeconds = event.limitSeconds)
            }

            is LiveEvent.Language -> _state.update { it.copy(detectedLanguage = event.code) }

            is LiveEvent.Partial -> _state.update { it.copy(partial = event.text) }

            is LiveEvent.Unit -> _state.update {
                it.copy(partial = "", lines = it.lines + LiveLine(event.id, event.original))
            }

            is LiveEvent.Translation -> _state.update { state ->
                state.copy(
                    lines = state.lines.map { line ->
                        if (line.id == event.id) {
                            line.copy(translation = event.text, sameLanguage = event.sameLanguage)
                        } else {
                            line
                        }
                    },
                )
            }

            is LiveEvent.Audio -> if (_state.value.speak) player.enqueue(event.id, event.mp3)

            // Режим одной модели: озвучка идёт потоком, а не файлом на фразу.
            is LiveEvent.AudioStream ->
                if (_state.value.speak) player.stream(event.pcm, event.sampleRate)

            is LiveEvent.Credits -> _state.update { it.copy(credits = event.spent) }

            LiveEvent.LimitReached -> {
                _messages.tryEmit(string(R.string.live_limit_reached))
                _state.update { it.copy(status = LiveStatus.FINISHING) }
            }

            is LiveEvent.Failed -> _messages.tryEmit(event.message)

            LiveEvent.Done -> {
                // Микрофон гасим через статус: его поток проверяет состояние на каждой порции.
                _state.update { it.copy(status = LiveStatus.FINISHING) }
            }
        }
    }

    private fun finishing(): Boolean = _state.value.status == LiveStatus.FINISHING

    private fun finish() {
        chunkCounter = 0
        _state.update {
            it.copy(status = LiveStatus.IDLE, partial = "", level = 0f, hearing = false)
        }
    }

    private fun string(id: Int): String = container.appContext.localizedString(id)

    override fun onCleared() {
        super.onCleared()
        // Экран закрыли — сессия обязана закончиться здесь же, иначе микрофон и счётчик
        // остались бы жить в фоне.
        connection?.close()
        connection = null
        sessionJob?.cancel()
        player.clear()
    }

    companion object {
        /** Сколько порций тишины держим «на всякий случай» перед началом речи (300 мс). */
        private const val PREROLL_CHUNKS = 3

        /** Сколько ждём прощального сообщения сервера после «стоп». */
        private const val FAREWELL_TIMEOUT_MS = 30_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VoiceApp
                LiveViewModel(app.container)
            }
        }
    }
}

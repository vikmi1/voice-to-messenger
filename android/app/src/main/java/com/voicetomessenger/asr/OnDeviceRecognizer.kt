package com.voicetomessenger.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Распознавание речи системным [SpeechRecognizer].
 *
 * Все методы вызываются с главного потока — этого требует сам SpeechRecognizer.
 * Результаты приходят в [events], поэтому один и тот же распознаватель одинаково
 * используют и экран приложения, и пузырь поверх других приложений.
 */
class OnDeviceRecognizer(private val context: Context) {

    sealed interface Event {
        /** Промежуточная гипотеза — показываем, пока человек говорит. */
        data class Partial(val text: String) : Event

        /** Итоговый текст. */
        data class Final(val text: String) : Event

        /** Громкость для индикатора, 0..1. */
        data class Level(val value: Float) : Event

        data class Failed(val message: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    @MainThread
    fun start(languageTag: String) {
        if (!isAvailable) {
            _events.tryEmit(Event.Failed("На устройстве нет распознавания речи — включи серверный Whisper в настройках"))
            return
        }
        release()

        val instance = SpeechRecognizer.createSpeechRecognizer(context)
        instance.setRecognitionListener(listener)
        recognizer = instance

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        instance.startListening(intent)
    }

    /** Остановить запись; итоговый текст ещё придёт событием [Event.Final]. */
    @MainThread
    fun stop() {
        recognizer?.stopListening()
    }

    @MainThread
    fun cancel() {
        recognizer?.cancel()
        release()
    }

    @MainThread
    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // SpeechRecognizer отдаёт примерно -2..10 дБ; приводим к 0..1.
            _events.tryEmit(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { _events.tryEmit(Event.Partial(it)) }
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (text.isNullOrBlank()) {
                _events.tryEmit(Event.Failed("Ничего не распознано — попробуй ещё раз"))
            } else {
                _events.tryEmit(Event.Final(text))
            }
            release()
        }

        override fun onError(error: Int) {
            _events.tryEmit(Event.Failed(describe(error)))
            release()
        }

        private fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи звука"
        SpeechRecognizer.ERROR_CLIENT -> "Распознавание прервано"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Нет связи с сервисом распознавания"
        SpeechRecognizer.ERROR_NO_MATCH -> "Ничего не распознано — попробуй ещё раз"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, повтори через секунду"
        SpeechRecognizer.ERROR_SERVER -> "Сервис распознавания вернул ошибку"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не услышана"
        else -> "Не удалось распознать речь (код $error)"
    }
}

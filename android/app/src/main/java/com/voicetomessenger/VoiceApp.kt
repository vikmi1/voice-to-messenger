package com.voicetomessenger

import android.app.Application
import android.content.Context
import com.voicetomessenger.asr.AudioRecorder
import com.voicetomessenger.asr.OnDeviceRecognizer
import com.voicetomessenger.capture.VoicePipeline
import com.voicetomessenger.data.AppDatabase
import com.voicetomessenger.data.NotesRepository
import com.voicetomessenger.data.SettingsRepository
import com.voicetomessenger.net.AdminClient
import com.voicetomessenger.net.BackendClient
import com.voicetomessenger.net.LiveTranslateClient
import com.voicetomessenger.sound.SoundEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Ручной DI: зависимостей мало, отдельная библиотека тут была бы лишней. */
class AppContainer(context: Context) {

    /** Для строк за пределами Compose — см. `Context.localizedString`. */
    val appContext: Context = context.applicationContext

    /**
     * Живёт столько же, сколько процесс. Нужен для записей, которые обязаны дойти,
     * даже если тот, кто их начал, уже закрывается — например, сервис пузыря, гасящий сам себя.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(appContext)
    val backend = BackendClient(settings, appContext)
    val admin = AdminClient(settings)

    /** Живой перевод. Отдельный клиент: у него своё соединение и свои таймауты. */
    val liveTranslate = LiveTranslateClient(settings, appContext)
    val notes = NotesRepository(AppDatabase.get(appContext).noteDao())

    /** Синтезированные звуки игр. Флаг [SoundEngine.enabled] следует за настройкой звука. */
    val sound = SoundEngine()

    init {
        appScope.launch {
            settings.settings.collect { sound.enabled = it.soundEnabled }
        }
    }

    /**
     * Распознаватель и рекордер держат состояние микрофона, поэтому у каждого
     * потребителя (экран, пузырь) свой экземпляр конвейера.
     */
    fun newPipeline(scope: CoroutineScope) = VoicePipeline(
        scope = scope,
        context = appContext,
        settings = settings,
        backend = backend,
        notes = notes,
        recognizer = OnDeviceRecognizer(appContext),
        recorder = AudioRecorder(appContext),
    )

    /** Подтянуть стили и языки из админ-панели. Молча игнорирует отсутствие сети. */
    suspend fun refreshOptions() {
        runCatching { settings.cacheOptions(backend.options()) }
    }
}

class VoiceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as VoiceApp).container

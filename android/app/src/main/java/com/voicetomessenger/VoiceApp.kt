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
import kotlinx.coroutines.CoroutineScope

/** Ручной DI: зависимостей мало, отдельная библиотека тут была бы лишней. */
class AppContainer(private val context: Context) {

    val settings = SettingsRepository(context)
    val backend = BackendClient(settings)
    val admin = AdminClient(settings)
    val notes = NotesRepository(AppDatabase.get(context).noteDao())

    /**
     * Распознаватель и рекордер держат состояние микрофона, поэтому у каждого
     * потребителя (экран, пузырь) свой экземпляр конвейера.
     */
    fun newPipeline(scope: CoroutineScope) = VoicePipeline(
        scope = scope,
        settings = settings,
        backend = backend,
        recognizer = OnDeviceRecognizer(context),
        recorder = AudioRecorder(context),
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

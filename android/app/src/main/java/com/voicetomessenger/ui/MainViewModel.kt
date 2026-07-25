package com.voicetomessenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voicetomessenger.AppContainer
import com.voicetomessenger.VoiceApp
import com.voicetomessenger.capture.CaptureState
import com.voicetomessenger.data.AsrMode
import com.voicetomessenger.data.Note
import com.voicetomessenger.data.Options
import com.voicetomessenger.data.ProcessingMode
import com.voicetomessenger.data.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val pipeline = container.newPipeline(viewModelScope)

    val capture: StateFlow<CaptureState> = pipeline.state

    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val options: StateFlow<Options> = container.settings.options
        .stateIn(viewModelScope, SharingStarted.Eagerly, Options.DEFAULT)

    val notes: StateFlow<List<Note>> = container.notes.notes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Короткие сообщения для снекбара. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { container.refreshOptions() }
    }

    // --- Диктовка ---------------------------------------------------------

    fun onMicClick() = pipeline.toggle()

    fun reset() = pipeline.reset()

    fun editResult(text: String) = pipeline.editResult(text)

    fun selectStyle(id: String) {
        viewModelScope.launch {
            container.settings.setStyle(id)
            pipeline.reprocess()
        }
    }

    fun selectTargetLang(code: String) {
        viewModelScope.launch {
            container.settings.setTargetLang(code)
            pipeline.reprocess()
        }
    }

    // --- Заметки ----------------------------------------------------------

    fun saveNote(text: String) {
        viewModelScope.launch {
            container.notes.add(text)
            _messages.tryEmit("Сохранено в заметки")
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { container.notes.delete(id) }
    }

    fun clearNotes() {
        viewModelScope.launch { container.notes.deleteAll() }
    }

    // --- Настройки --------------------------------------------------------

    fun setServer(url: String, token: String) {
        viewModelScope.launch {
            container.settings.setServer(url, token)
            container.refreshOptions()
            _messages.tryEmit("Настройки сервера сохранены")
        }
    }

    fun setAsrMode(mode: AsrMode) {
        viewModelScope.launch { container.settings.setAsrMode(mode) }
    }

    fun setAsrLanguage(tag: String) {
        viewModelScope.launch { container.settings.setAsrLanguage(tag) }
    }

    fun setProcessingMode(mode: ProcessingMode) {
        viewModelScope.launch { container.settings.setProcessingMode(mode) }
    }

    fun setBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setBubbleEnabled(enabled) }
    }

    fun checkConnection() {
        viewModelScope.launch {
            val message = runCatching { container.backend.options() }
                .fold(
                    onSuccess = { options ->
                        container.settings.cacheOptions(options)
                        "Связь есть: стилей — ${options.styles.size}"
                    },
                    onFailure = { it.message ?: "Не удалось связаться с сервером" },
                )
            _messages.tryEmit(message)
        }
    }

    override fun onCleared() {
        pipeline.release()
        super.onCleared()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VoiceApp
                MainViewModel(app.container)
            }
        }
    }
}

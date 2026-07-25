package com.voicetomessenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voicetomessenger.AppContainer
import com.voicetomessenger.VoiceApp
import com.voicetomessenger.data.AdminConfig
import com.voicetomessenger.data.LanguageConfig
import com.voicetomessenger.data.ProviderConfig
import com.voicetomessenger.data.ProviderKind
import com.voicetomessenger.data.StyleConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TEST_SAMPLE =
    "ну это самое короче слушай надо бы завтра встретиться где-то часа в три " +
        "ну или в четыре обсудить вопрос по проекту"

data class AdminUiState(
    val loading: Boolean = true,
    /** Черновик: правки живут здесь, на сервер уходят только по «Сохранить». */
    val draft: AdminConfig? = null,
    /** Последняя загруженная с сервера версия — чтобы понимать, есть ли несохранённое. */
    val saved: AdminConfig? = null,
    val loadError: String? = null,
    val saving: Boolean = false,
    /** Списки моделей по ключу провайдера, подтянутые кнопкой «Список». */
    val models: Map<String, List<String>> = emptyMap(),
    val loadingModelsFor: String? = null,
    val test: TestState = TestState(),
) {
    val hasChanges: Boolean get() = draft != null && draft != saved
}

data class TestState(
    val input: String = TEST_SAMPLE,
    val output: String = "",
    /** Пусто — брать активного провайдера / модель из его настроек. */
    val providerKey: String = "",
    val model: String = "",
    val styleId: String = "",
    val targetLang: String = "off",
    val running: Boolean = false,
)

class AdminViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            runCatching { container.admin.loadConfig() }.fold(
                onSuccess = { cfg ->
                    _state.update {
                        it.copy(
                            loading = false,
                            draft = cfg,
                            saved = cfg,
                            test = it.test.copy(
                                styleId = it.test.styleId.ifBlank {
                                    cfg.styles.firstOrNull()?.id.orEmpty()
                                },
                            ),
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(loading = false, loadError = e.message ?: "Не удалось загрузить настройки")
                    }
                },
            )
        }
    }

    fun save() {
        val state = _state.value
        val draft = state.draft ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { container.admin.saveConfig(draft) }.fold(
                onSuccess = {
                    // Если токены поменяли прямо здесь — подхватываем их на этом устройстве,
                    // иначе телефон сам себя отрезал бы от сервера следующим же запросом.
                    if (draft.adminToken != state.saved?.adminToken) {
                        container.settings.setAdminToken(draft.adminToken)
                    }
                    if (draft.apiToken != state.saved?.apiToken) {
                        container.settings.setApiToken(draft.apiToken)
                    }
                    _state.update { it.copy(saving = false, saved = draft) }
                    // Стили и языки могли поменяться — обновляем то, что видит основной экран.
                    container.refreshOptions()
                    _messages.tryEmit("Сохранено")
                },
                onFailure = { e ->
                    _state.update { it.copy(saving = false) }
                    _messages.tryEmit(e.message ?: "Не удалось сохранить")
                },
            )
        }
    }

    // --- Правка черновика -------------------------------------------------

    private fun edit(transform: (AdminConfig) -> AdminConfig) {
        _state.update { s -> s.copy(draft = s.draft?.let(transform)) }
    }

    fun setActiveProvider(key: String) = edit { it.copy(activeProvider = key) }

    fun updateProvider(provider: ProviderConfig) = edit { it.withProvider(provider) }

    fun addProvider(key: String) = edit { cfg ->
        val clean = key.trim()
        if (clean.isBlank() || cfg.provider(clean) != null) return@edit cfg
        cfg.copy(
            providers = cfg.providers + ProviderConfig(
                key = clean,
                label = clean,
                kind = ProviderKind.OPENAI,
                baseUrl = "",
                apiKey = "",
                model = "",
            ),
        )
    }

    fun removeProvider(key: String) = edit { cfg ->
        // Активный и ASR-провайдеры должны существовать, иначе бэкенд упадёт на запросе.
        if (key == cfg.activeProvider || key == cfg.asrProvider) return@edit cfg
        cfg.copy(providers = cfg.providers.filterNot { it.key == key })
    }

    fun setAsrProvider(key: String) = edit { it.copy(asrProvider = key) }

    fun setAsrModel(model: String) = edit { it.copy(asrModel = model) }

    fun setBasePrompt(text: String) = edit { it.copy(basePrompt = text) }

    fun updateStyle(index: Int, style: StyleConfig) = edit { cfg ->
        cfg.copy(styles = cfg.styles.mapIndexed { i, s -> if (i == index) style else s })
    }

    fun addStyle() = edit { it.copy(styles = it.styles + StyleConfig("", "", "")) }

    fun removeStyle(index: Int) = edit { cfg ->
        cfg.copy(styles = cfg.styles.filterIndexed { i, _ -> i != index })
    }

    fun updateLanguage(index: Int, language: LanguageConfig) = edit { cfg ->
        cfg.copy(languages = cfg.languages.mapIndexed { i, l -> if (i == index) language else l })
    }

    fun addLanguage() = edit { it.copy(languages = it.languages + LanguageConfig("", "")) }

    fun removeLanguage(index: Int) = edit { cfg ->
        cfg.copy(languages = cfg.languages.filterIndexed { i, _ -> i != index })
    }

    fun setApiToken(token: String) = edit { it.copy(apiToken = token) }

    fun setAdminToken(token: String) = edit { it.copy(adminToken = token) }

    /** Новый токен генерируем на устройстве — сервер принимает любую непустую строку. */
    fun regenerate(): String = buildString {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        repeat(32) { append(alphabet.random()) }
    }

    // --- Модели -----------------------------------------------------------

    fun loadModels(providerKey: String) {
        _state.update { it.copy(loadingModelsFor = providerKey) }
        viewModelScope.launch {
            runCatching { container.admin.models(providerKey) }.fold(
                onSuccess = { list ->
                    _state.update {
                        it.copy(
                            loadingModelsFor = null,
                            models = it.models + (providerKey to list.sorted()),
                        )
                    }
                    _messages.tryEmit("Моделей: ${list.size}")
                },
                onFailure = { e ->
                    _state.update { it.copy(loadingModelsFor = null) }
                    _messages.tryEmit(e.message ?: "Не удалось получить список моделей")
                },
            )
        }
    }

    // --- Тестовый прогон --------------------------------------------------

    fun editTest(transform: (TestState) -> TestState) {
        _state.update { it.copy(test = transform(it.test)) }
    }

    fun runTest() {
        val s = _state.value
        val test = s.test
        if (test.input.isBlank() || test.running) return
        // Тест идёт через сохранённые на сервере настройки — предупреждаем о черновике.
        if (s.hasChanges) _messages.tryEmit("Есть несохранённые правки — тест идёт по сохранённым")

        _state.update { it.copy(test = it.test.copy(running = true, output = "")) }
        viewModelScope.launch {
            runCatching {
                container.admin.test(
                    text = test.input,
                    providerKey = test.providerKey.ifBlank { s.draft?.activeProvider.orEmpty() },
                    model = test.model,
                    styleId = test.styleId,
                    targetLang = test.targetLang,
                )
            }.fold(
                onSuccess = { text ->
                    _state.update { it.copy(test = it.test.copy(running = false, output = text)) }
                },
                onFailure = { e ->
                    _state.update { it.copy(test = it.test.copy(running = false)) }
                    _messages.tryEmit(e.message ?: "Ошибка прогона")
                },
            )
        }
    }

    /** Выход из панели: забываем админ-токен, раздел исчезает из приложения. */
    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            container.settings.setAdminToken("")
            onDone()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VoiceApp
                AdminViewModel(app.container)
            }
        }
    }
}

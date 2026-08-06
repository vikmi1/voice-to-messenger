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
import com.voicetomessenger.data.LiveConfig
import com.voicetomessenger.data.ModelCatalog
import com.voicetomessenger.data.ProviderConfig
import com.voicetomessenger.data.ProviderKind
import com.voicetomessenger.data.StyleConfig
import com.voicetomessenger.data.UsageReport
import com.voicetomessenger.data.VoiceCatalog
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
    /** Подобранные модели с ценами. Пустой, если бэкенд старый и каталога не отдаёт. */
    val catalog: ModelCatalog = ModelCatalog.EMPTY,
    /** Списки моделей по ключу провайдера, подтянутые кнопкой «Все модели». */
    val models: Map<String, List<String>> = emptyMap(),
    val loadingModelsFor: String? = null,
    val test: TestState = TestState(),
    val usage: UsageState = UsageState(),
    val voices: VoicesState = VoicesState(),
) {
    val hasChanges: Boolean get() = draft != null && draft != saved
}

/** Вкладка «Расход». Данные тянутся при первом открытии, а не вместе с настройками. */
data class UsageState(
    val days: Int = 30,
    val report: UsageReport? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Голоса синтеза речи выбранного провайдера — вкладка «Живой перевод».
 *
 * [gender] и [name] — промежуточный выбор в рядах кнопок: пока голос не выбран
 * до конца, он ещё не записан в конфиг, но ряды уже должны его помнить.
 */
data class VoicesState(
    val catalog: VoiceCatalog = VoiceCatalog.EMPTY,
    val loadedFor: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val gender: String? = null,
    val name: String? = null,
)

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
            // Каталог не критичен: без него поля моделей просто останутся без подсказок,
            // а старый бэкенд его вообще не отдаёт — поэтому ошибку глотаем.
            runCatching { container.admin.catalog() }.onSuccess { loaded ->
                _state.update { it.copy(catalog = loaded) }
            }
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
                asrModel = "",
            ),
        )
    }

    fun removeProvider(key: String) = edit { cfg ->
        // Активный и ASR-провайдеры должны существовать, иначе бэкенд упадёт на запросе.
        if (key == cfg.activeProvider || key == cfg.asrProvider) return@edit cfg
        cfg.copy(providers = cfg.providers.filterNot { it.key == key })
    }

    fun setAsrProvider(key: String) = edit { it.copy(asrProvider = key) }

    fun setLive(live: LiveConfig) = edit { it.copy(live = live) }

    /**
     * Сменили провайдера потокового распознавания — подставляем и его модель.
     *
     * Раньше менялся только провайдер, а в поле модели оставалась чужая: с парой
     * «OpenAI + voxtral-…-realtime» живой перевод падал не с внятным отказом, а с
     * «HTTP 503» от шлюза OpenAI, по которому причину не угадать.
     */
    fun setLiveSttProvider(key: String) = setLiveProvider(key) { live, baseUrl ->
        live.copy(
            sttProvider = key,
            sttModel = _state.value.catalog.realtimeAsrFor(baseUrl).firstOrNull()?.id.orEmpty(),
        )
    }

    /** То же для перевода: mistral-small-latest у OpenAI не существует. */
    fun setLiveTranslateProvider(key: String) = setLiveProvider(key) { live, baseUrl ->
        live.copy(
            translateProvider = key,
            translateModel = _state.value.catalog.llmFor(baseUrl).firstOrNull()?.id.orEmpty(),
        )
    }

    private fun setLiveProvider(key: String, change: (LiveConfig, String) -> LiveConfig) {
        val draft = _state.value.draft ?: return
        val baseUrl = draft.provider(key)?.baseUrl.orEmpty()
        edit { it.copy(live = change(it.live, baseUrl)) }
    }

    /**
     * Сменили провайдера озвучки: старые голос и модель ему заведомо не принадлежат
     * (voxtral у OpenAI не существует, как и gpt-*-tts у Mistral), а оставшаяся чужая
     * пара даёт 400 при первой же озвученной фразе.
     */
    fun setTtsProvider(key: String) {
        val draft = _state.value.draft ?: return
        val baseUrl = draft.provider(key)?.baseUrl.orEmpty()
        edit {
            it.copy(live = it.live.copy(
                ttsProvider = key,
                ttsModel = _state.value.catalog.tts(baseUrl).firstOrNull()?.id.orEmpty(),
                ttsVoice = "",
            ))
        }
        _state.update { it.copy(voices = VoicesState()) }
        loadVoices()
    }

    /** Голоса провайдера озвучки. Тянутся при открытии вкладки и при смене провайдера. */
    fun loadVoices() {
        val draft = _state.value.draft ?: return
        val key = draft.live.ttsProvider
        val voices = _state.value.voices
        if (key.isBlank() || voices.loading || voices.loadedFor == key) return

        _state.update { it.copy(voices = it.voices.copy(loading = true, error = null)) }
        viewModelScope.launch {
            runCatching { container.admin.voices(key) }.fold(
                onSuccess = { loaded ->
                    _state.update {
                        it.copy(voices = it.voices.copy(
                            catalog = loaded, loadedFor = key, loading = false,
                        ))
                    }
                },
                onFailure = { e ->
                    // Не беда: поле голоса остаётся обычным текстовым вводом.
                    _state.update {
                        it.copy(voices = it.voices.copy(
                            loading = false, loadedFor = key,
                            error = e.message ?: "Не удалось получить голоса",
                        ))
                    }
                },
            )
        }
    }

    /** Выбор в ряду «пол»: имя сбрасываем — у другого пола оно другое. */
    fun pickVoiceGender(gender: String) {
        _state.update { it.copy(voices = it.voices.copy(gender = gender, name = null)) }
    }

    /** Выбор в ряду «имя». Если у имени один голос — он же и выбирается. */
    fun pickVoiceName(name: String) {
        val state = _state.value
        val voices = state.voices
        val gender = voices.gender ?: voices.catalog.find(state.draft?.live?.ttsVoice.orEmpty())?.gender.orEmpty()
        _state.update { it.copy(voices = it.voices.copy(name = name)) }
        val variants = voices.catalog.variants(gender, name)
        if (variants.size == 1) setLive(state.draft!!.live.copy(ttsVoice = variants.first().id))
    }

    fun pickVoice(id: String) = edit { it.copy(live = it.live.copy(ttsVoice = id)) }

    fun pickMood(id: String) = edit { it.copy(live = it.live.copy(ttsMood = id)) }

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

    // --- Расход -----------------------------------------------------------

    /**
     * Тянет сводку расхода. Вызывается при открытии вкладки и при смене периода —
     * не вместе с настройками: запрос перебирает все строки за период, а панель
     * чаще открывают ради провайдеров и промпта.
     */
    fun loadUsage(days: Int = _state.value.usage.days) {
        if (_state.value.usage.loading) return
        _state.update { it.copy(usage = it.usage.copy(days = days, loading = true, error = null)) }
        viewModelScope.launch {
            runCatching { container.admin.usage(days) }.fold(
                onSuccess = { report ->
                    _state.update { it.copy(usage = it.usage.copy(loading = false, report = report)) }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            usage = it.usage.copy(
                                loading = false,
                                error = e.message ?: "Не удалось загрузить расход",
                            ),
                        )
                    }
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

package com.voicetomessenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.voicetomessenger.data.AdminConfig
import com.voicetomessenger.data.CatalogModel
import com.voicetomessenger.data.LanguageConfig
import com.voicetomessenger.data.LiveConfig
import com.voicetomessenger.data.ModelCatalog
import com.voicetomessenger.data.ProviderConfig
import com.voicetomessenger.data.ProviderKind
import com.voicetomessenger.data.StyleConfig

private val TABS = listOf(
    "Провайдеры", "Распознавание", "Живой перевод", "Промпт", "Стили", "Языки",
    "Тест", "Расход", "Доступ",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.hasChanges) "Админ •" else "Админ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.hasChanges && !state.saving,
                    ) { Text(if (state.saving) "Сохраняю…" else "Сохранить") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            val draft = state.draft
            when {
                state.loading -> Centered { CircularProgressIndicator() }

                draft == null -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(state.loadError ?: "Настройки недоступны")
                        Button(onClick = viewModel::reload) { Text("Повторить") }
                    }
                }

                else -> {
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                        TABS.forEachIndexed { i, title ->
                            Tab(selected = i == tab, onClick = { tab = i }, text = { Text(title) })
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        when (tab) {
                            0 -> ProvidersTab(state, draft, viewModel)
                            1 -> AsrTab(draft, viewModel)
                            2 -> LiveTab(state, draft, viewModel)
                            3 -> PromptTab(draft, viewModel)
                            4 -> StylesTab(draft, viewModel)
                            5 -> LanguagesTab(draft, viewModel)
                            6 -> TestTab(state, draft, viewModel)
                            7 -> UsageTab(state, viewModel)
                            else -> AccessTab(draft, viewModel, onBack)
                        }
                    }
                }
            }
        }
    }
}

// --- Вкладки ---------------------------------------------------------------

@Composable
private fun ProvidersTab(state: AdminUiState, draft: AdminConfig, viewModel: AdminViewModel) {
    var newProviderKey by rememberSaveable { mutableStateOf<String?>(null) }

    Text("Активный провайдер", style = MaterialTheme.typography.titleMedium)
    ChipRow(
        options = draft.providers.map { it.key to it.label.ifBlank { it.key } },
        selected = draft.activeProvider,
        onSelect = viewModel::setActiveProvider,
    )
    Text(
        "Кто переписывает надиктованный текст. Модель берётся из карточки этого провайдера " +
            "ниже — там же задаётся и модель распознавания речи.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    draft.providers.forEach { provider ->
        ProviderCard(
            provider = provider,
            catalog = state.catalog,
            models = state.models[provider.key].orEmpty(),
            loadingModels = state.loadingModelsFor == provider.key,
            canDelete = provider.key != draft.activeProvider && provider.key != draft.asrProvider,
            isAsrProvider = provider.key == draft.asrProvider,
            onChange = viewModel::updateProvider,
            onLoadModels = { viewModel.loadModels(provider.key) },
            onDelete = { viewModel.removeProvider(provider.key) },
        )
    }

    OutlinedButton(onClick = { newProviderKey = "" }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text("  Добавить провайдера")
    }

    newProviderKey?.let { current ->
        AlertDialog(
            onDismissRequest = { newProviderKey = null },
            title = { Text("Новый провайдер") },
            text = {
                OutlinedTextField(
                    value = current,
                    onValueChange = { newProviderKey = it },
                    label = { Text("Ключ (латиницей)") },
                    placeholder = { Text("groq") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addProvider(current)
                        newProviderKey = null
                    },
                    enabled = current.isNotBlank(),
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { newProviderKey = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    catalog: ModelCatalog,
    models: List<String>,
    loadingModels: Boolean,
    canDelete: Boolean,
    isAsrProvider: Boolean,
    onChange: (ProviderConfig) -> Unit,
    onLoadModels: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(provider.key, style = MaterialTheme.typography.titleMedium)
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить провайдера")
                    }
                }
            }

            OutlinedTextField(
                value = provider.label,
                onValueChange = { onChange(provider.copy(label = it)) },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Тип API", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = ProviderKind.entries.map { it to it.title },
                selected = provider.kind,
                onSelect = { onChange(provider.copy(kind = it)) },
            )

            ModelField(
                label = "Модель для обработки текста",
                model = provider.model,
                suggestions = catalog.llmFor(provider.baseUrl),
                tierTitle = catalog::tierTitle,
                models = models,
                loading = loadingModels,
                onChange = { onChange(provider.copy(model = it)) },
                onLoadModels = onLoadModels,
            )

            // У Anthropic нет /audio/transcriptions — распознавать речь ему нечем.
            if (provider.kind == ProviderKind.OPENAI) {
                ModelField(
                    label = "Модель распознавания речи",
                    model = provider.asrModel,
                    suggestions = catalog.asrFor(provider.baseUrl),
                    tierTitle = catalog::tierTitle,
                    // Живой список моделей провайдера сюда не годится: /models отдаёт
                    // всё вперемешку, а транскрипцию умеют единицы.
                    models = emptyList(),
                    loading = false,
                    onChange = { onChange(provider.copy(asrModel = it)) },
                    onLoadModels = null,
                    footnote = if (isAsrProvider) {
                        "Этот провайдер сейчас распознаёт речь — см. вкладку «Распознавание»."
                    } else {
                        null
                    },
                )
            }

            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { onChange(provider.copy(baseUrl = it)) },
                label = { Text("Base URL") },
                singleLine = true,
                supportingText = { Text("По нему подбирается список моделей в выпадашках") },
                modifier = Modifier.fillMaxWidth(),
            )

            SecretField(
                value = provider.apiKey,
                onValueChange = { onChange(provider.copy(apiKey = it)) },
                label = "API-ключ",
                supporting = "Пусто — бэкенд возьмёт ключ из переменной " +
                    "${provider.key.uppercase()}_API_KEY на сервере",
            )
        }
    }
}

/**
 * Поле модели: выпадашка подобранных моделей с ценами — плюс ручной ввод.
 *
 * [suggestions] — короткий подобранный список с бэкенда (каталог). [models] — живой
 * список всех моделей провайдера, подтягивается по кнопке; для распознавания речи он
 * бесполезен, поэтому [onLoadModels] там null и кнопки нет.
 */
@Composable
private fun ModelField(
    label: String,
    model: String,
    suggestions: List<CatalogModel>,
    tierTitle: (String) -> String,
    models: List<String>,
    loading: Boolean,
    onChange: (String) -> Unit,
    onLoadModels: (() -> Unit)?,
    footnote: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var awaitingList by remember { mutableStateOf(false) }

    // Список приходит асинхронно — открываем меню, когда он доехал.
    LaunchedEffect(models) {
        if (awaitingList && models.isNotEmpty()) {
            expanded = true
            awaitingList = false
        }
    }

    val chosen = suggestions.firstOrNull { it.id == model.trim() }
    val hint = when {
        chosen != null -> "${tierTitle(chosen.tier)} · ${chosen.price} — ${chosen.note}"
        model.isNotBlank() -> "Своя модель — цену смотри у провайдера"
        suggestions.isNotEmpty() -> "Пусто. Подобранные варианты — по кнопке справа"
        else -> "Впиши модель вручную: для этого адреса API подсказок нет"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = model,
                onValueChange = onChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                OutlinedButton(
                    onClick = {
                        // Подсказок нет и живого списка тоже — сначала тянем его с провайдера.
                        if (suggestions.isEmpty() && models.isEmpty() && onLoadModels != null) {
                            awaitingList = true
                            onLoadModels()
                        } else {
                            expanded = true
                        }
                    },
                    enabled = !loading && (suggestions.isNotEmpty() || onLoadModels != null),
                ) { Text(if (loading) "…" else "Список") }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    suggestions.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(entry.id, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${tierTitle(entry.tier)} · ${entry.price}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onChange(entry.id)
                                expanded = false
                            },
                        )
                    }

                    if (onLoadModels != null) {
                        HorizontalDivider()
                        val known = suggestions.map { it.id }.toSet()
                        models.filterNot { it in known }.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(id, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    onChange(id)
                                    expanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(if (models.isEmpty()) "Все модели провайдера" else "Обновить список")
                            },
                            onClick = {
                                expanded = false
                                onLoadModels()
                            },
                        )
                    }
                }
            }
        }

        Text(
            text = listOfNotNull(hint, footnote).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AsrTab(draft: AdminConfig, viewModel: AdminViewModel) {
    Text("Распознавание речи на сервере", style = MaterialTheme.typography.titleMedium)
    Text(
        "Whisper-совместимый эндпоинт /audio/transcriptions. Anthropic такого не умеет — " +
            "здесь нужен OpenAI-совместимый провайдер.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Провайдер (чей ключ и base_url)", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = draft.providers
            .filter { it.kind == ProviderKind.OPENAI }
            .map { it.key to it.label.ifBlank { it.key } },
        selected = draft.asrProvider,
        onSelect = viewModel::setAsrProvider,
    )

    val chosen = draft.provider(draft.asrProvider)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Модель распознавания", style = MaterialTheme.typography.labelLarge)
            Text(
                chosen?.asrModel?.ifBlank { null } ?: "не задана",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Задаётся в карточке провайдера на вкладке «Провайдеры»: у каждого она " +
                    "своя. whisper-1 у Mistral не существует, voxtral у OpenAI — тоже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveTab(state: AdminUiState, draft: AdminConfig, viewModel: AdminViewModel) {
    val live = draft.live
    // Голоса тянутся при первом показе вкладки, а не вместе с настройками: за ними
    // бэкенд ходит к провайдеру, и на остальных вкладках этот запрос не нужен.
    LaunchedEffect(live.ttsProvider) { viewModel.loadVoices() }
    // Потоковое распознавание и синтез идут по OpenAI-совместимым эндпоинтам,
    // которых у Anthropic нет.
    val openAiProviders = draft.providers
        .filter { it.kind == ProviderKind.OPENAI }
        .map { it.key to it.label.ifBlank { it.key } }
    val allProviders = draft.providers.map { it.key to it.label.ifBlank { it.key } }

    Text("Живой перевод", style = MaterialTheme.typography.titleMedium)
    Text(
        "Экран живого перевода: микрофон слушает говорящего, язык определяется сам. " +
            "Одной модели на всё нет, поэтому здесь три — распознавание, перевод и озвучка.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("Потоковое распознавание", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = openAiProviders,
        selected = live.sttProvider,
        onSelect = { viewModel.setLive(live.copy(sttProvider = it)) },
    )
    OutlinedTextField(
        value = live.sttModel,
        onValueChange = { viewModel.setLive(live.copy(sttModel = it)) },
        label = { Text("Модель распознавания") },
        supportingText = {
            Text("Нужен WebSocket /audio/transcriptions/realtime — на 06.08.2026 только у Mistral")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider()

    Text("Перевод", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = allProviders,
        selected = live.translateProvider,
        onSelect = { viewModel.setLive(live.copy(translateProvider = it)) },
    )
    OutlinedTextField(
        value = live.translateModel,
        onValueChange = { viewModel.setLive(live.copy(translateModel = it)) },
        label = { Text("Модель перевода") },
        supportingText = {
            Text("Вызывается на каждую фразу — здесь нужна дешёвая и быстрая, а не флагман")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider()

    Text("Озвучка", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = openAiProviders,
        selected = live.ttsProvider,
        onSelect = viewModel::setTtsProvider,
    )
    OutlinedTextField(
        value = live.ttsModel,
        onValueChange = { viewModel.setLive(live.copy(ttsModel = it)) },
        label = { Text("Модель синтеза речи") },
        supportingText = { Text("Пусто — перевод только текстом, без голоса в наушнике") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    VoicePicker(state, live, viewModel)

    HorizontalDivider()

    OutlinedTextField(
        value = live.sessionLimitMinutes.toString(),
        onValueChange = { raw ->
            val minutes = raw.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
            viewModel.setLive(live.copy(sessionLimitMinutes = minutes))
        },
        label = { Text("Предел одной сессии, минут") },
        supportingText = {
            Text("Забытый включённым микрофон тратит деньги молча — сессия закроется сама")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Выбор голоса: пол → имя → настроение.
 *
 * Ряд пола показывается только там, где провайдер публикует пол голосов (Mistral);
 * OpenAI этого не делает, и гадать по именам мы не станем. Настроение у Mistral —
 * часть самого голоса («Paul — Happy» и «Paul — Sad» это разные голоса), у OpenAI —
 * отдельное поле запроса, поэтому его ряд появляется только для таких провайдеров.
 *
 * Ручной ввод оставлен: у Mistral можно клонировать собственный голос, и его id
 * появится в списке не сразу.
 */
@Composable
private fun VoicePicker(state: AdminUiState, live: LiveConfig, viewModel: AdminViewModel) {
    val voices = state.voices
    val catalog = voices.catalog
    val chosen = catalog.find(live.ttsVoice)

    Text("Голос", style = MaterialTheme.typography.labelLarge)

    when {
        voices.loading -> Text(
            "Загружаю голоса…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        voices.error != null -> Text(
            "Не удалось получить голоса: ${voices.error}. Впиши id вручную ниже.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        catalog.voices.isEmpty() -> Text(
            "У этого провайдера список голосов недоступен — впиши id вручную.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {
            val gender = voices.gender ?: chosen?.gender.orEmpty()
            if (catalog.genders.isNotEmpty()) {
                val titles = mapOf("male" to "Мужской", "female" to "Женский")
                ChipRow(
                    options = catalog.genders.map { it to (titles[it] ?: it) },
                    selected = gender,
                    onSelect = viewModel::pickVoiceGender,
                )
            }

            val name = voices.name ?: chosen?.name.orEmpty()
            ChipRow(
                options = catalog.names(gender).map { it to it },
                selected = name,
                onSelect = viewModel::pickVoiceName,
            )

            val variants = catalog.variants(gender, name)
            if (variants.size > 1) {
                ChipRow(
                    options = variants.map { it.id to it.mood.ifBlank { it.id } },
                    selected = live.ttsVoice,
                    onSelect = viewModel::pickVoice,
                )
            }

            if (chosen != null) {
                Text(
                    listOfNotNull(
                        chosen.id,
                        chosen.mood.takeIf { it.isNotBlank() },
                        chosen.languages.joinToString(", ").takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (catalog.supportsInstructions && catalog.moods.isNotEmpty()) {
                Text("Настроение", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Уходит в запрос полем instructions — одним голосом можно говорить " +
                        "и бодро, и сухо.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = catalog.moods.map { it.id to it.title },
                    selected = live.ttsMood,
                    onSelect = viewModel::pickMood,
                )
            }
        }
    }

    OutlinedTextField(
        value = live.ttsVoice,
        onValueChange = { viewModel.setLive(live.copy(ttsVoice = it.trim())) },
        label = { Text("id голоса") },
        supportingText = { Text("Заполняется кнопками; можно вписать свой клонированный голос") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PromptTab(draft: AdminConfig, viewModel: AdminViewModel) {
    Text("Базовый системный промпт", style = MaterialTheme.typography.titleMedium)
    Text(
        "Главное правило — модель должна вернуть только готовый текст, без пояснений.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft.basePrompt,
        onValueChange = viewModel::setBasePrompt,
        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
    )
}

@Composable
private fun StylesTab(draft: AdminConfig, viewModel: AdminViewModel) {
    draft.styles.forEachIndexed { index, style ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = style.id,
                        onValueChange = { viewModel.updateStyle(index, style.copy(id = it)) },
                        label = { Text("ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = style.name,
                        onValueChange = { viewModel.updateStyle(index, style.copy(name = it)) },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                    IconButton(onClick = { viewModel.removeStyle(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить стиль")
                    }
                }
                OutlinedTextField(
                    value = style.prompt,
                    onValueChange = { viewModel.updateStyle(index, style.copy(prompt = it)) },
                    label = { Text("Промпт стиля") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                )
            }
        }
    }
    OutlinedButton(onClick = viewModel::addStyle, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text("  Добавить стиль")
    }
    Text(
        "Стили без ID при сохранении отбрасываются.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LanguagesTab(draft: AdminConfig, viewModel: AdminViewModel) {
    draft.languages.forEachIndexed { index, language ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = language.code,
                    onValueChange = { viewModel.updateLanguage(index, language.copy(code = it)) },
                    label = { Text("Код") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = language.name,
                    onValueChange = { viewModel.updateLanguage(index, language.copy(name = it)) },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f),
                )
                IconButton(onClick = { viewModel.removeLanguage(index) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить язык")
                }
            }
        }
    }
    OutlinedButton(onClick = viewModel::addLanguage, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text("  Добавить язык")
    }
    Text(
        "Код off означает «без перевода» — его лучше оставить первым.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TestTab(state: AdminUiState, draft: AdminConfig, viewModel: AdminViewModel) {
    val test = state.test

    Text("Прогон текста", style = MaterialTheme.typography.titleMedium)
    Text(
        "Идёт через настройки, сохранённые на сервере. Провайдер и модель можно " +
            "переопределить, ничего не сохраняя.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("Провайдер", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = listOf("" to "Активный") + draft.providers.map { it.key to it.label.ifBlank { it.key } },
        selected = test.providerKey,
        onSelect = { key -> viewModel.editTest { it.copy(providerKey = key) } },
    )

    val testProviderKey = test.providerKey.ifBlank { draft.activeProvider }
    ModelField(
        label = "Модель (пусто — из настроек)",
        model = test.model,
        suggestions = state.catalog.llmFor(draft.provider(testProviderKey)?.baseUrl.orEmpty()),
        tierTitle = state.catalog::tierTitle,
        models = state.models[testProviderKey].orEmpty(),
        loading = state.loadingModelsFor != null,
        onChange = { value -> viewModel.editTest { it.copy(model = value) } },
        onLoadModels = { viewModel.loadModels(testProviderKey) },
    )

    Text("Стиль", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = draft.styles.map { it.id to it.name.ifBlank { it.id } },
        selected = test.styleId,
        onSelect = { id -> viewModel.editTest { it.copy(styleId = id) } },
    )

    Text("Перевод", style = MaterialTheme.typography.labelLarge)
    ChipRow(
        options = draft.languages.map { it.code to it.name.ifBlank { it.code } },
        selected = test.targetLang,
        onSelect = { code -> viewModel.editTest { it.copy(targetLang = code) } },
    )

    OutlinedTextField(
        value = test.input,
        onValueChange = { value -> viewModel.editTest { it.copy(input = value) } },
        label = { Text("Исходный текст") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
    )

    Button(
        onClick = viewModel::runTest,
        enabled = !test.running && test.input.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (test.running) "Обрабатываю…" else "Прогнать") }

    if (test.output.isNotBlank()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Результат", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(test.output, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun AccessTab(draft: AdminConfig, viewModel: AdminViewModel, onBack: () -> Unit) {
    Text("Токены", style = MaterialTheme.typography.titleMedium)

    SecretField(
        value = draft.apiToken,
        onValueChange = viewModel::setApiToken,
        label = "API-токен приложения",
        supporting = "Обычный доступ к диктовке и обработке. После сохранения этот телефон " +
            "подхватит новый токен сам, остальные устройства отвалятся.",
    )
    OutlinedButton(onClick = { viewModel.setApiToken(viewModel.regenerate()) }) {
        Text("Сгенерировать новый")
    }

    SecretField(
        value = draft.adminToken,
        onValueChange = viewModel::setAdminToken,
        label = "Админ-токен",
        supporting = "Открывает эту панель. После сохранения этот телефон подхватит новый " +
            "токен сам; в веб-панели его видно на вкладке «Приложение».",
    )
    OutlinedButton(onClick = { viewModel.setAdminToken(viewModel.regenerate()) }) {
        Text("Сгенерировать новый")
    }

    OutlinedButton(
        onClick = { viewModel.signOut(onBack) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Выйти из панели на этом телефоне") }
    Text(
        "Токен сотрётся с устройства, раздел «Админ» исчезнет. Настройки на сервере не изменятся.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Мелочи ----------------------------------------------------------------

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * Чипы выбора: провайдеров и стилей может быть много, поэтому переносим по строкам.
 *
 * internal, а не private: тем же чипам выбирается период на вкладке «Расход»
 * (она живёт в отдельном файле, чтобы этот не разрастался).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supporting) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Скрыть" else "Показать")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

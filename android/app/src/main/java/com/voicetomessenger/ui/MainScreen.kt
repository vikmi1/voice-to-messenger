package com.voicetomessenger.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicetomessenger.capture.CaptureState
import com.voicetomessenger.data.Language
import com.voicetomessenger.data.ProcessingMode
import com.voicetomessenger.data.Style

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val capture by viewModel.capture.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val options by viewModel.options.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) viewModel.onMicClick()
    }

    val onMicClick = {
        if (micGranted) viewModel.onMicClick() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice-to-Messenger") },
                actions = {
                    IconButton(onClick = onOpenNotes) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Заметки")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!settings.isServerConfigured && settings.processingMode == ProcessingMode.SERVER) {
                SetupHint(onOpenSettings)
            }

            MicSection(capture = capture, onClick = onMicClick)

            StyleRow(
                styles = options.styles,
                selectedId = settings.styleId,
                enabled = settings.processingMode == ProcessingMode.SERVER,
                onSelect = viewModel::selectStyle,
            )

            LanguageRow(
                languages = options.languages,
                selectedCode = settings.targetLang,
                enabled = settings.processingMode == ProcessingMode.SERVER,
                onSelect = viewModel::selectTargetLang,
            )

            when (val state = capture) {
                is CaptureState.Processing -> ResultBlock(
                    text = state.text,
                    raw = state.raw,
                    busy = true,
                    viewModel = viewModel,
                )

                is CaptureState.Done -> ResultBlock(
                    text = state.text,
                    raw = state.raw,
                    busy = false,
                    viewModel = viewModel,
                )

                is CaptureState.Failed -> ErrorCard(state.message, onRetry = viewModel::reset)

                else -> Unit
            }
        }
    }
}

@Composable
private fun SetupHint(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сервер не настроен", style = MaterialTheme.typography.titleSmall)
            Text(
                "Укажи адрес бэкенда и API-токен из админ-панели — или переключись " +
                    "на локальную обработку в настройках.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onOpenSettings) { Text("Открыть настройки") }
        }
    }
}

@Composable
private fun MicSection(capture: CaptureState, onClick: () -> Unit) {
    val listening = capture is CaptureState.Listening
    val level = (capture as? CaptureState.Listening)?.level ?: 0f
    val scale by animateFloatAsState(targetValue = if (listening) 1f + level * 0.2f else 1f, label = "mic")

    val background = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(132.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (listening) "Остановить запись" else "Начать запись",
            tint = Color.White,
            modifier = Modifier.size(56.dp),
        )
    }

    Text(
        text = statusText(capture),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )

    val partial = (capture as? CaptureState.Listening)?.partial.orEmpty()
    if (partial.isNotBlank()) {
        Text(
            text = partial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun statusText(capture: CaptureState): String = when (capture) {
    CaptureState.Idle -> "Нажми и наговори сообщение"
    is CaptureState.Listening -> "Слушаю… нажми, чтобы закончить"
    CaptureState.Transcribing -> "Расшифровываю запись…"
    is CaptureState.Processing -> "Готовлю сообщение…"
    is CaptureState.Done -> "Готово — проверь и отправь"
    is CaptureState.Failed -> "Не получилось"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleRow(
    styles: List<Style>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (styles.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Стиль", style = MaterialTheme.typography.labelLarge)
        // FlowRow, а не горизонтальная прокрутка: иначе последние чипы уезжают за край незаметно.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            styles.forEach { style ->
                FilterChip(
                    selected = style.id == selectedId,
                    onClick = { onSelect(style.id) },
                    enabled = enabled,
                    label = { Text(style.name) },
                )
            }
        }
        if (!enabled) {
            Text(
                "Локальная обработка стили не применяет",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageRow(
    languages: List<Language>,
    selectedCode: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (languages.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Перевод", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            languages.forEach { language ->
                FilterChip(
                    selected = language.code == selectedCode,
                    onClick = { onSelect(language.code) },
                    enabled = enabled,
                    label = { Text(language.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ResultBlock(
    text: String,
    raw: String,
    busy: Boolean,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    var showRaw by remember { mutableStateOf(false) }

    // Клавиатура перекрывала поле: держим его в видимой части экрана, пока в нём правят текст.
    val bringIntoView = remember { BringIntoViewRequester() }
    var editing by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(editing, imeVisible) {
        // Ждём, пока клавиатура доедет: до этого видимая высота ещё старая.
        if (editing) bringIntoView.bringIntoView()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = viewModel::editResult,
            readOnly = busy,
            label = { Text("Готовое сообщение") },
            minLines = 3,
            // Длинное сообщение прокручивается внутри поля, а не растит его под клавиатуру.
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .onFocusChanged { editing = it.isFocused },
        )

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Текст ещё дописывается…", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { context.copyToClipboard(text) },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Копировать")
            }
            OutlinedButton(
                onClick = { context.shareText(text) },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Поделиться")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.saveNote(text) },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("В заметки") }
            OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) {
                Text("Новое сообщение")
            }
        }

        TextButton(onClick = { showRaw = !showRaw }) {
            Text(if (showRaw) "Скрыть исходный текст" else "Показать исходный текст")
        }
        if (showRaw) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    text = raw,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) { Text("Попробовать снова") }
        }
    }
}

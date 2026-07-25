package com.voicetomessenger.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voicetomessenger.bubble.BubbleService
import com.voicetomessenger.data.AsrMode
import com.voicetomessenger.data.ON_DEVICE_LANGUAGES
import com.voicetomessenger.data.ProcessingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings.apiToken) { mutableStateOf(settings.apiToken) }

    val canDrawOverlay = rememberOverlayPermission(context)

    // Сервис пузыря объявлен как foregroundServiceType="microphone": без выданного
    // RECORD_AUDIO система откажется его запускать, поэтому спрашиваем заранее.
    val bubblePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] != false) {
            viewModel.setBubbleEnabled(true)
            BubbleService.start(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Сервер") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Адрес бэкенда") },
                    placeholder = { Text("https://voice.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API-токен") },
                    supportingText = { Text("Вкладка «Приложение» в админ-панели") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.setServer(url, token) }) { Text("Сохранить") }
                    OutlinedButton(onClick = viewModel::checkConnection) { Text("Проверить связь") }
                }
            }

            HorizontalDivider()

            Section("Распознавание речи") {
                ChoiceRow(
                    options = listOf(
                        AsrMode.ON_DEVICE to "Системное",
                        AsrMode.SERVER to "Whisper на сервере",
                    ),
                    selected = settings.asrMode,
                    onSelect = viewModel::setAsrMode,
                )
                Text(
                    text = when (settings.asrMode) {
                        AsrMode.ON_DEVICE ->
                            "Быстро и без загрузки аудио на наш сервер. Язык выбирается вручную; " +
                                "распознаёт системный сервис (у Google он может обращаться в облако)."
                        AsrMode.SERVER ->
                            "Аудио уходит на бэкенд и удаляется сразу после расшифровки. " +
                                "Качественнее и определяет язык сам."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (settings.asrMode == AsrMode.ON_DEVICE) {
                    Text("Язык диктовки", style = MaterialTheme.typography.labelLarge)
                    ChoiceRow(
                        options = ON_DEVICE_LANGUAGES.map { it.code to it.name },
                        selected = settings.asrLanguage,
                        onSelect = viewModel::setAsrLanguage,
                    )
                }
            }

            HorizontalDivider()

            Section("Обработка текста") {
                ChoiceRow(
                    options = listOf(
                        ProcessingMode.SERVER to "Сервер (LLM)",
                        ProcessingMode.LOCAL to "Локально",
                    ),
                    selected = settings.processingMode,
                    onSelect = viewModel::setProcessingMode,
                )
                Text(
                    text = when (settings.processingMode) {
                        ProcessingMode.SERVER ->
                            "Стили, перевод и полноценный рерайт — текст уходит на бэкенд."
                        ProcessingMode.LOCAL ->
                            "Без сети: чистка слов-паразитов и пунктуации. Стили и перевод не работают."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Section("Кнопка поверх приложений") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Показывать пузырь", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings.bubbleEnabled && canDrawOverlay,
                        onCheckedChange = { enabled ->
                            when {
                                !enabled -> {
                                    viewModel.setBubbleEnabled(false)
                                    BubbleService.stop(context)
                                }
                                // Без разрешения включать нечего — ведём в системные настройки.
                                !canDrawOverlay -> context.requestOverlayPermission()
                                else -> bubblePermissions.launch(bubblePermissionList())
                            }
                        },
                    )
                }
                Text(
                    "Кнопка микрофона висит поверх мессенджеров: нажал — наговорил — " +
                        "готовый текст лёг в буфер обмена, осталось вставить в поле ввода.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canDrawOverlay) {
                    OutlinedButton(onClick = { context.requestOverlayPermission() }) {
                        Text("Разрешить показ поверх приложений")
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

/** Право рисовать поверх других приложений выдаётся в системных настройках — перепроверяем на возврате. */
@Composable
private fun rememberOverlayPermission(context: Context): Boolean {
    var granted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = AndroidSettings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

private fun bubblePermissionList(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

private fun Context.requestOverlayPermission() {
    val intent = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    )
    startActivity(intent)
}

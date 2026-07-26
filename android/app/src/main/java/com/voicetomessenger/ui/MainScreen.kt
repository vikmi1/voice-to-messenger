package com.voicetomessenger.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicetomessenger.R
import com.voicetomessenger.capture.CaptureState
import com.voicetomessenger.data.Language
import com.voicetomessenger.data.ProcessingMode
import com.voicetomessenger.data.Style
import kotlin.math.PI
import kotlin.math.sin

private const val WAVE_BARS = 5
private val WAVE_MIN_HEIGHT = 10.dp
private val WAVE_MAX_HEIGHT = 56.dp

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

    val serverMode = settings.processingMode == ProcessingMode.SERVER

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenNotes) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.cd_notes))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // safeDrawing = системные панели + клавиатура. Композер живёт в bottomBar и
        // сам поднимается над клавиатурой; Scaffold ужимает контент ровно на его высоту.
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            // Поле правки «прилипает» к низу и едет вверх вместе с клавиатурой (как в WhatsApp).
            when (val state = capture) {
                is CaptureState.Processing -> ResultComposer(state.text, busy = true, viewModel = viewModel)
                is CaptureState.Done -> ResultComposer(state.text, busy = false, viewModel = viewModel)
                else -> Unit
            }
        },
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
            if (!settings.isServerConfigured && serverMode) {
                SetupHint(onOpenSettings)
            }

            MicSection(capture = capture, onClick = onMicClick)

            TypeRow(asList = settings.asList, enabled = serverMode, onSelect = viewModel::setListMode)

            StyleRow(
                styles = options.styles,
                selectedId = settings.styleId,
                enabled = serverMode,
                onSelect = viewModel::selectStyle,
            )

            EmojiRow(checked = settings.emoji, enabled = serverMode, onToggle = viewModel::setEmoji)

            LanguageRow(
                languages = options.languages,
                selectedCode = settings.targetLang,
                enabled = serverMode,
                onSelect = viewModel::selectTargetLang,
            )

            (capture as? CaptureState.Listening)?.let { listening ->
                RecordingControls(
                    state = listening,
                    onPause = viewModel::pauseCapture,
                    onResume = viewModel::resumeCapture,
                    onStop = viewModel::stopCapture,
                )
            }

            when (val state = capture) {
                is CaptureState.Processing -> RawSection(state.raw)
                is CaptureState.Done -> RawSection(state.raw)
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
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.setup_open)) }
        }
    }
}

@Composable
private fun MicSection(capture: CaptureState, onClick: () -> Unit) {
    val listening = capture as? CaptureState.Listening
    val level = listening?.level ?: 0f
    val paused = listening?.paused == true
    val pulsing = listening != null && !paused
    val scale by animateFloatAsState(targetValue = if (pulsing) 1f + level * 0.2f else 1f, label = "mic")

    val background =
        if (listening != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(132.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Во время записи кнопка не превращается в «стоп»: она краснеет и показывает
        // звуковые волны, а остановка — отдельной кнопкой ниже.
        if (listening != null) {
            SoundWaves(level = level, paused = paused)
        } else {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = stringResource(R.string.mic_start_cd),
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
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

/** Столбики-эквалайзер внутри кнопки: видно, что запись реально идёт. */
@Composable
private fun SoundWaves(level: Float, paused: Boolean) {
    val phase by rememberInfiniteTransition(label = "waves").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "phase",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(WAVE_MAX_HEIGHT),
    ) {
        repeat(WAVE_BARS) { index ->
            // На паузе столбики замирают в минимальной высоте — это читается как «стоит».
            val wave = if (paused) 0f else (sin(phase + index * 0.9f) + 1f) / 2f
            val amplitude = (0.25f + 0.75f * level) * wave
            val height = WAVE_MIN_HEIGHT + (WAVE_MAX_HEIGHT - WAVE_MIN_HEIGHT) * amplitude

            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(height)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun RecordingControls(
    state: CaptureState.Listening,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.canPause) {
                OutlinedButton(
                    onClick = if (state.paused) onResume else onPause,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(if (state.paused) R.string.rec_resume else R.string.rec_pause))
                }
            }
            Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.rec_stop))
            }
        }
        if (!state.canPause) {
            Text(
                stringResource(R.string.rec_pause_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusText(capture: CaptureState): String = when (capture) {
    CaptureState.Idle -> stringResource(R.string.status_idle)
    is CaptureState.Listening ->
        if (capture.paused) stringResource(R.string.status_paused) else stringResource(R.string.status_listening)
    CaptureState.Transcribing -> stringResource(R.string.status_transcribing)
    is CaptureState.Processing -> stringResource(R.string.status_processing)
    is CaptureState.Done -> stringResource(R.string.status_done)
    is CaptureState.Failed -> stringResource(R.string.status_failed)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeRow(asList: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.type_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = !asList,
                onClick = { onSelect(false) },
                enabled = enabled,
                label = { Text(stringResource(R.string.type_message)) },
            )
            FilterChip(
                selected = asList,
                onClick = { onSelect(true) },
                enabled = enabled,
                label = { Text(stringResource(R.string.type_list)) },
            )
        }
    }
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
        Text(stringResource(R.string.style_label), style = MaterialTheme.typography.labelLarge)
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
                stringResource(R.string.style_local_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Переключатель «ИИ сам расставит эмодзи». Работает только в серверном режиме. */
@Composable
private fun EmojiRow(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FilterChip(
            selected = checked,
            onClick = { onToggle(!checked) },
            enabled = enabled,
            label = { Text(stringResource(if (checked) R.string.emoji_on else R.string.emoji_off)) },
        )
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
        Text(stringResource(R.string.translate_label), style = MaterialTheme.typography.labelLarge)
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

/** Раскрываемый блок с исходной расшифровкой — под чипами, в прокручиваемой части. */
@Composable
private fun RawSection(raw: String) {
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { showRaw = !showRaw }) {
            Text(stringResource(if (showRaw) R.string.raw_hide else R.string.raw_show))
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

/**
 * Док внизу экрана: поле готового сообщения и действия. Через imePadding() поднимается
 * над клавиатурой и «прилипает» к ней, как поле ввода в мессенджере.
 */
@Composable
private fun ResultComposer(text: String, busy: Boolean, viewModel: MainViewModel) {
    val context = LocalContext.current
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::editResult,
                readOnly = busy,
                label = { Text(stringResource(R.string.result_label)) },
                minLines = 1,
                // Длинное сообщение прокручивается внутри поля, а не растит его под клавиатуру.
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.result_streaming), style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { context.copyToClipboard(text) },
                    enabled = !busy && text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_copy))
                }
                OutlinedButton(
                    onClick = { context.shareText(text) },
                    enabled = !busy && text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_share))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.saveNote(text) },
                    enabled = !busy && text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_save_note)) }
                OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_new_message))
                }
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
            TextButton(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
        }
    }
}

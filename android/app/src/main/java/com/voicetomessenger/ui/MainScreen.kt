package com.voicetomessenger.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicetomessenger.R
import com.voicetomessenger.capture.CaptureState
import com.voicetomessenger.data.Language
import com.voicetomessenger.data.ProcessingMode
import com.voicetomessenger.data.Style
import com.voicetomessenger.ui.theme.LocalBrand
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

private const val WAVE_BARS = 5
private val WAVE_MIN_HEIGHT = 8.dp
private val WAVE_MAX_HEIGHT = 44.dp

/** Кнопка микрофона и её «сцена». Высота сцены постоянна — кнопка не сдвигается никогда. */
private val MIC_SIZE = 96.dp
private val MIC_STAGE_HEIGHT = 132.dp
private val SIDE_ACTION_SIZE = 60.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGames: () -> Unit,
) {
    val context = LocalContext.current
    val capture by viewModel.capture.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val options by viewModel.options.collectAsState()
    val spentCredits by viewModel.spentCredits.collectAsState()
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
    // Пока идёт запись / расшифровка / генерация — опции не трогаем: менять их на ходу
    // непонятно. Настраиваются до записи либо после появления результата (там reprocess).
    val idleForOptions = capture is CaptureState.Idle ||
        capture is CaptureState.Done || capture is CaptureState.Failed
    val optionsEnabled = serverMode && idleForOptions

    // Тип — единственная опция, которая меняет содержание, а не подачу: из речи,
    // наговорённой сплошным текстом, список не «появится» — модель его сочинит.
    // Поэтому после старта записи тип заперт до «Нового сообщения», в отличие от
    // стиля и перевода, которые лишь по-разному подают одно и то же.
    val typeLocked = capture !is CaptureState.Idle && capture !is CaptureState.Failed
    val typeEnabled = serverMode && !typeLocked

    val micAtBottom = settings.micAtBottom
    val listening = capture as? CaptureState.Listening

    Box(Modifier.fillMaxSize().auroraBackground()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        IconButton(onClick = onOpenGames) {
                            Icon(Icons.Rounded.Casino, contentDescription = stringResource(R.string.cd_games))
                        }
                        IconButton(onClick = onOpenNotes) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.cd_notes),
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.cd_settings))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            // safeDrawing = системные панели + клавиатура. Композер живёт в bottomBar и
            // сам поднимается над клавиатурой; Scaffold ужимает контент ровно на его высоту.
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                // Поле правки «прилипает» к низу и едет вверх вместе с клавиатурой (как в мессенджере).
                when (val state = capture) {
                    is CaptureState.Processing -> ResultComposer(state.text, busy = true, viewModel = viewModel)
                    is CaptureState.Done -> ResultComposer(state.text, busy = false, viewModel = viewModel, spentCredits = spentCredits)
                    else -> Unit
                }
            },
        ) { innerPadding ->
            // Внешняя колонка нужна ради режима «микрофон внизу»: прокручиваемая часть
            // занимает всё оставшееся место, а док записи стоит под ней намертво.
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!settings.isServerConfigured && serverMode) {
                        SetupHint(onOpenSettings)
                    }

                    if (!micAtBottom) {
                        MicDock(
                            capture = capture,
                            attached = false,
                            onMic = onMicClick,
                            onPause = viewModel::pauseCapture,
                            onResume = viewModel::resumeCapture,
                            onDone = viewModel::stopCapture,
                            onDelete = viewModel::reset,
                        )
                    }

                    OptionsPanel(
                        asList = settings.asList,
                        typeEnabled = typeEnabled,
                        onSelectType = viewModel::setListMode,
                        styles = options.styles,
                        styleId = settings.styleId,
                        // Список — это заметка/перечень, стиль к нему не применяется.
                        stylesEnabled = optionsEnabled && !settings.asList,
                        listMode = settings.asList,
                        localMode = !serverMode,
                        onSelectStyle = viewModel::selectStyle,
                        languages = options.languages,
                        langCode = settings.targetLang,
                        languagesEnabled = optionsEnabled,
                        onSelectLang = viewModel::selectTargetLang,
                    )

                    // Гипотеза распознавания растёт по ходу диктовки, поэтому живёт в
                    // прокручиваемой части: док внизу от неё не съезжает.
                    val partial = listening?.partial.orEmpty()
                    if (partial.isNotBlank()) {
                        LiveTranscript(partial)
                    }
                    if (listening != null && !listening.canPause) {
                        HintText(stringResource(R.string.rec_pause_hint))
                    }

                    when (val state = capture) {
                        is CaptureState.Processing -> RawSection(state.raw)
                        is CaptureState.Done -> RawSection(state.raw)
                        is CaptureState.Failed -> ErrorCard(state.message, onRetry = viewModel::reset)
                        else -> Unit
                    }
                }

                if (micAtBottom) {
                    // Док приподнят тенью и скруглён сверху — граница между настройками
                    // и записью видна сразу, а кнопка стоит там, куда дотягивается большой палец.
                    MicDock(
                        capture = capture,
                        attached = true,
                        onMic = onMicClick,
                        onPause = viewModel::pauseCapture,
                        onResume = viewModel::resumeCapture,
                        onDone = viewModel::stopCapture,
                        onDelete = viewModel::reset,
                    )
                }
            }
        }
    }
}

// --- Док записи -------------------------------------------------------------

/**
 * Микрофон и управление записью.
 *
 * Геометрия фиксированная: строка кнопок всегда [MIC_STAGE_HEIGHT] высотой, микрофон всегда
 * по центру. Кнопки «удалить» и «готово» появляются по краям — они не двигают микрофон,
 * потому что живут в том же Box, а не в общей колонке.
 *
 * Роли ровно три, как в привычных мессенджерах: слева — отменить и стереть, по центру —
 * пауза/продолжение (там же видно, что запись идёт), справа — закончить.
 */
@Composable
private fun MicDock(
    capture: CaptureState,
    attached: Boolean,
    onMic: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    val listening = capture as? CaptureState.Listening
    val recording = listening != null
    val paused = listening?.paused == true
    val canPause = listening?.canPause == true
    val busy = capture is CaptureState.Transcribing || capture is CaptureState.Processing

    // Секундомер живёт в UI: конвейеру он не нужен, а глазу — да.
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) { if (recording) elapsedMs = 0L }
    LaunchedEffect(recording, paused) {
        while (recording && !paused) {
            delay(200)
            elapsedMs += 200
        }
    }

    val onCenter: () -> Unit = when {
        listening == null -> onMic
        // Паузу умеет только серверное распознавание; иначе центр остаётся «закончить».
        canPause && paused -> onResume
        canPause -> onPause
        else -> onDone
    }
    val centerCd = when {
        listening == null -> stringResource(R.string.mic_start_cd)
        canPause && paused -> stringResource(R.string.cd_rec_resume)
        canPause -> stringResource(R.string.cd_rec_pause)
        else -> stringResource(R.string.cd_rec_done)
    }

    if (attached) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 18.dp,
        ) {
            MicDockBody(
                capture = capture,
                recording = recording,
                paused = paused,
                busy = busy,
                level = listening?.level ?: 0f,
                elapsedMs = elapsedMs,
                centerCd = centerCd,
                onCenter = onCenter,
                onDone = onDone,
                onDelete = onDelete,
            )
        }
    } else {
        MicDockBody(
            capture = capture,
            recording = recording,
            paused = paused,
            busy = busy,
            level = listening?.level ?: 0f,
            elapsedMs = elapsedMs,
            centerCd = centerCd,
            onCenter = onCenter,
            onDone = onDone,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun MicDockBody(
    capture: CaptureState,
    recording: Boolean,
    paused: Boolean,
    busy: Boolean,
    level: Float,
    elapsedMs: Long,
    centerCd: String,
    onCenter: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusLine(capture = capture, recording = recording, elapsedMs = elapsedMs)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MIC_STAGE_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            SideActionSlot(
                visible = recording,
                modifier = Modifier.align(Alignment.CenterStart),
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.cd_rec_delete),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )

            MicButton(
                recording = recording,
                paused = paused,
                busy = busy,
                level = level,
                contentDescription = centerCd,
                onClick = onCenter,
            )

            SideActionSlot(
                visible = recording,
                modifier = Modifier.align(Alignment.CenterEnd),
                icon = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.cd_rec_done),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onDone,
            )
        }
    }
}

/** Одна строка над кнопкой: точка «в эфире», секундомер и что происходит. Высота постоянна. */
@Composable
private fun StatusLine(capture: CaptureState, recording: Boolean, elapsedMs: Long) {
    val brand = LocalBrand.current
    Row(
        modifier = Modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recording) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(brand.live),
            )
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = brand.live,
            )
        }
        Text(
            text = statusText(capture),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Круг микрофона: градиент, мягкая тень, пульсирующее кольцо во время записи. */
@Composable
private fun MicButton(
    recording: Boolean,
    paused: Boolean,
    busy: Boolean,
    level: Float,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val brand = LocalBrand.current
    val live = recording && !paused
    val scale by animateFloatAsState(targetValue = if (live) 1f + level * 0.08f else 1f, label = "mic")

    Box(
        modifier = Modifier.size(MIC_STAGE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        if (live) {
            // Кольцо расходится от кнопки и гаснет — видно, что запись идёт, даже боковым зрением.
            val phase by rememberInfiniteTransition(label = "ring").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
                label = "ringPhase",
            )
            Canvas(Modifier.size(MIC_STAGE_HEIGHT)) {
                val from = size.minDimension * 0.37f
                val to = size.minDimension * 0.5f
                drawCircle(
                    color = brand.live.copy(alpha = 0.28f * (1f - phase)),
                    radius = from + (to - from) * phase,
                )
            }
        }

        val glow = if (recording) brand.live else brand.micIdle.last()
        Box(
            modifier = Modifier
                .size(MIC_SIZE)
                .scale(scale)
                .shadow(
                    elevation = if (recording) 20.dp else 14.dp,
                    shape = CircleShape,
                    ambientColor = glow,
                    spotColor = glow,
                )
                .clip(CircleShape)
                .background(Brush.linearGradient(if (recording) brand.micLive else brand.micIdle))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
                recording && paused -> Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(46.dp),
                )
                recording -> SoundWaves(level = level)
                else -> Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}

/**
 * Боковая кнопка появляется и исчезает вместе с записью. Анимация вынесена в отдельную
 * функцию: внутри Box, вложенного в Column, `AnimatedVisibility` иначе цепляется
 * за ColumnScope-перегрузку и не компилируется.
 */
@Composable
private fun SideActionSlot(
    visible: Boolean,
    modifier: Modifier,
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.7f),
        exit = fadeOut() + scaleOut(targetScale = 0.7f),
    ) {
        SideAction(
            icon = icon,
            contentDescription = contentDescription,
            container = container,
            content = content,
            onClick = onClick,
        )
    }
}

/** Круглая боковая кнопка записи: только иконка, 60 dp — мимо не промахнёшься. */
@Composable
private fun SideAction(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(SIDE_ACTION_SIZE)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(26.dp))
    }
}

/** Столбики-эквалайзер внутри кнопки: видно, что голос доходит. */
@Composable
private fun SoundWaves(level: Float) {
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
            val wave = (sin(phase + index * 0.9f) + 1f) / 2f
            val amplitude = (0.25f + 0.75f * level) * wave
            val height = WAVE_MIN_HEIGHT + (WAVE_MAX_HEIGHT - WAVE_MIN_HEIGHT) * amplitude

            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(height)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
    }
}

// --- Настройки сообщения ----------------------------------------------------

/** Панель опций: один лист, три группы. Разделяет их воздух, а не линии. */
@Composable
private fun OptionsPanel(
    asList: Boolean,
    typeEnabled: Boolean,
    onSelectType: (Boolean) -> Unit,
    styles: List<Style>,
    styleId: String,
    stylesEnabled: Boolean,
    listMode: Boolean,
    localMode: Boolean,
    onSelectStyle: (String) -> Unit,
    languages: List<Language>,
    langCode: String,
    languagesEnabled: Boolean,
    onSelectLang: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.extraLarge,
            ),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Group(stringResource(R.string.type_label)) {
                TypeSegmented(asList = asList, enabled = typeEnabled, onSelect = onSelectType)
            }

            if (styles.isNotEmpty()) {
                Group(stringResource(R.string.style_label)) {
                    ChipFlow {
                        styles.forEach { style ->
                            BrandChip(
                                text = styleLabel(style),
                                selected = style.id == styleId,
                                enabled = stylesEnabled,
                                onClick = { onSelectStyle(style.id) },
                            )
                        }
                    }
                    // Поясняем, почему стили недоступны: список без стиля или локальная обработка.
                    val hint = when {
                        listMode -> stringResource(R.string.style_list_hint)
                        localMode -> stringResource(R.string.style_local_hint)
                        else -> null
                    }
                    if (hint != null) {
                        Spacer(Modifier.height(8.dp))
                        HintText(hint)
                    }
                }
            }

            if (languages.isNotEmpty()) {
                Group(stringResource(R.string.translate_label)) {
                    ChipFlow {
                        languages.forEach { language ->
                            BrandChip(
                                text = languageLabel(language),
                                selected = language.code == langCode,
                                enabled = languagesEnabled,
                                onClick = { onSelectLang(language.code) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(label)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    // FlowRow, а не горизонтальная прокрутка: иначе последние чипы уезжают за край незаметно.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSegmented(asList: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = brandSegmentedColors()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !asList,
            onClick = { onSelect(false) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            colors = colors,
            icon = {},
            label = { Text(stringResource(R.string.type_message)) },
        )
        SegmentedButton(
            selected = asList,
            onClick = { onSelect(true) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            colors = colors,
            icon = {},
            label = { Text(stringResource(R.string.type_list)) },
        )
    }
}

// Локализуем ТОЛЬКО нетронутые дефолтные имена (зеркалят config_store._default_config на
// бэкенде). Если админ переименовал стиль/язык в панели — показываем его имя как есть (WYSIWYG),
// иначе правки в админке молча перебивались бы локализацией.

/** Название стиля: нетронутый дефолт — локализованно, переименованное в админке — как задано. */
@Composable
private fun styleLabel(style: Style): String = when (style.id to style.name) {
    "mine" to "Мой стиль" -> stringResource(R.string.style_mine)
    "friendly" to "Дружелюбно" -> stringResource(R.string.style_friendly)
    "business" to "Деловой" -> stringResource(R.string.style_business)
    else -> style.name
}

/** Название языка перевода: нетронутый дефолт — локализованно, изменённое в админке — как задано. */
@Composable
private fun languageLabel(language: Language): String = when (language.code to language.name) {
    "off" to "Без перевода" -> stringResource(R.string.lang_off)
    "ru" to "Русский" -> stringResource(R.string.lang_ru)
    "de" to "Немецкий" -> stringResource(R.string.lang_de)
    "en" to "Английский" -> stringResource(R.string.lang_en)
    else -> language.name
}

// --- Прочие блоки -----------------------------------------------------------

@Composable
private fun SetupHint(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    stringResource(R.string.setup_open),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Живая гипотеза распознавания — то, что микрофон слышит прямо сейчас. */
@Composable
private fun LiveTranscript(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            SectionLabel(stringResource(R.string.live_label))
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Мелкая поясняющая строка. */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/** Миллисекунды → «0:07». */
private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/** Раскрываемый блок с исходной расшифровкой — под панелью опций, в прокручиваемой части. */
@Composable
private fun RawSection(raw: String) {
    var showRaw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { showRaw = !showRaw },
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Text(stringResource(if (showRaw) R.string.raw_hide else R.string.raw_show))
        }
        if (showRaw) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = raw,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ResultComposer(
    text: String,
    busy: Boolean,
    viewModel: MainViewModel,
    /** Кредиты за это сообщение. 0 — обработка ничего не стоила (локальный режим). */
    spentCredits: Int = 0,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::editResult,
                readOnly = busy,
                label = { Text(stringResource(R.string.result_label)) },
                shape = MaterialTheme.shapes.medium,
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
            } else if (spentCredits > 0) {
                // Только по готовому сообщению: во время генерации сумма ещё догоняет
                // и прыгала бы на глазах. Ноль не показываем — строки про «ничего не
                // потрачено» не нужно, она бы только мозолила глаза в локальном режиме.
                Text(
                    pluralStringResource(R.plurals.result_spent, spentCredits, spentCredits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { context.copyToClipboard(text) },
                    enabled = !busy && text.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_copy))
                }
                FilledTonalButton(
                    onClick = { context.shareText(text) },
                    enabled = !busy && text.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_share))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { viewModel.saveNote(text) },
                    enabled = !busy && text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_save_note))
                }
                TextButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_new_message))
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    stringResource(R.string.error_retry),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

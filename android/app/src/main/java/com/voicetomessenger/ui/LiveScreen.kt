package com.voicetomessenger.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicetomessenger.R
import com.voicetomessenger.data.LiveLanguage
import com.voicetomessenger.ui.theme.LocalBrand

private val MIC_SIZE = 88.dp
private val MIC_STAGE = 120.dp

/**
 * Живой перевод: микрофон слушает говорящего, перевод идёт лентой и (если включено)
 * голосом в наушник.
 *
 * Экран намеренно скупой. Смотреть в него будут краем глаза, посреди разговора:
 * крупный перевод, тихий оригинал под ним и одна кнопка. Язык говорящего не
 * спрашиваем вовсе — его определяет модель.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(viewModel: LiveViewModel, onBack: () -> Unit, navBar: @Composable () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    // Пока идёт перевод, экран не гасим: с погасшим экраном система забирает микрофон,
    // и сессия молча превратилась бы в оплачиваемую тишину.
    val window = (context as? Activity)?.window
    DisposableEffect(state.active, window) {
        if (state.active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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

    Box(Modifier.fillMaxSize().auroraBackground()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.live_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = navBar,
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            ) {
                LanguageBar(
                    targetLang = state.targetLang,
                    sourceLang = state.sourceLang,
                    speak = state.speak,
                    // Языки на ходу менять можно — сервер подхватит со следующей фразы.
                    onSelectTarget = viewModel::setTargetLang,
                    onSelectSource = viewModel::setSourceLang,
                    onToggleSpeak = viewModel::setSpeak,
                )

                if (state.speak && !state.headset) {
                    HeadsetHint()
                }

                Transcript(
                    state = state,
                    modifier = Modifier.weight(1f),
                )

                MicDock(
                    state = state,
                    onMicClick = {
                        if (micGranted) {
                            viewModel.onMicClick()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        }
    }
}

// --- Верхняя полоса ---------------------------------------------------------

@Composable
private fun LanguageBar(
    targetLang: String,
    sourceLang: String,
    speak: Boolean,
    onSelectTarget: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onToggleSpeak: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // «С» слева, «на» справа — в порядке чтения. Обе колонки по weight(1f):
        // названия языков разной длины, и без этого полоса прыгала бы при выборе.
        Column(Modifier.weight(1f)) {
            SectionLabel(stringResource(R.string.live_translate_from))
            Spacer(Modifier.height(6.dp))
            LanguageDropdown(
                selected = sourceLang,
                codes = LiveLanguage.SOURCE_CODES,
                onSelect = onSelectSource,
            )
        }

        Column(Modifier.weight(1f)) {
            SectionLabel(stringResource(R.string.live_translate_to))
            Spacer(Modifier.height(6.dp))
            LanguageDropdown(selected = targetLang, onSelect = onSelectTarget)
        }

        // Озвучка — переключатель-иконка, а не Switch с подписью: место наверху дорогое,
        // а состояние читается по перечёркнутому динамику мгновенно.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (speak) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                )
                .clickable { onToggleSpeak(!speak) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (speak) {
                    Icons.AutoMirrored.Rounded.VolumeUp
                } else {
                    Icons.AutoMirrored.Rounded.VolumeOff
                },
                contentDescription = stringResource(
                    if (speak) R.string.live_speak_on else R.string.live_speak_off,
                ),
                tint = if (speak) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun LanguageDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    codes: List<String> = LiveLanguage.CODES,
) {
    var expanded by remember { mutableStateOf(false) }
    val names = liveLanguageNames()

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = names[selected] ?: selected,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // Двадцать языков — среди них «Нидерландский»; в две колонки такое
                // не влезает, а перенос строки распирал бы полосу по высоте.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Список длинный — ограничиваем высоту, иначе меню накрывает пол-экрана
        // и до нижних языков не долистать.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            codes.forEach { code ->
                DropdownMenuItem(
                    text = { Text(names[code] ?: code) },
                    onClick = {
                        expanded = false
                        onSelect(code)
                    },
                )
            }
        }
    }
}

/** Названия языков из ресурсов: список должен говорить на языке интерфейса. */
@Composable
private fun liveLanguageNames(): Map<String, String> = mapOf(
    LiveLanguage.AUTO to stringResource(R.string.lang_auto),
    "ru" to stringResource(R.string.lang_ru),
    "de" to stringResource(R.string.lang_de),
    "en" to stringResource(R.string.lang_en),
    "uk" to stringResource(R.string.lang_uk),
    "pl" to stringResource(R.string.lang_pl),
    "tr" to stringResource(R.string.lang_tr),
    "it" to stringResource(R.string.lang_it),
    "es" to stringResource(R.string.lang_es),
    "fr" to stringResource(R.string.lang_fr),
    "pt" to stringResource(R.string.lang_pt),
    "nl" to stringResource(R.string.lang_nl),
    "cs" to stringResource(R.string.lang_cs),
    "ro" to stringResource(R.string.lang_ro),
    "sv" to stringResource(R.string.lang_sv),
    "el" to stringResource(R.string.lang_el),
    "ar" to stringResource(R.string.lang_ar),
    "zh" to stringResource(R.string.lang_zh),
    "ja" to stringResource(R.string.lang_ja),
    "ko" to stringResource(R.string.lang_ko),
    "hi" to stringResource(R.string.lang_hi),
)

// --- Лента ------------------------------------------------------------------

@Composable
private fun Transcript(state: LiveUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Держим последнюю реплику на виду: смотреть в этот экран будут не отрываясь
    // от разговора, прокручивать его руками некогда.
    LaunchedEffect(state.lines.size, state.partial) {
        val total = state.lines.size + if (state.partial.isNotBlank()) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    if (state.lines.isEmpty() && state.partial.isBlank()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(
                    if (state.active) R.string.live_waiting else R.string.live_empty,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.lines, key = { it.id }) { line -> Line(line) }
        if (state.partial.isNotBlank()) {
            item(key = "partial") { PartialLine(state.partial) }
        }
    }
}

@Composable
private fun Line(line: LiveLine) {
    Column {
        when {
            line.translation == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.live_translating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Говорят на том же языке, на который переводим: показывать одно и то же
            // дважды незачем — переводом тут была бы копия оригинала.
            line.sameLanguage -> Text(
                text = line.original,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            else -> Text(
                text = line.translation,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Оригинала может не быть вовсе: модель, которая переводит сама, отдаёт только
        // перевод — подписывать его пустой строкой незачем.
        if (!line.sameLanguage && line.original.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = line.original,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Фраза, которую ещё договаривают: видно, что микрофон слышит, до всякого перевода. */
@Composable
private fun PartialLine(text: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Предупреждение об отсутствии наушников.
 *
 * Без них озвучка перевода попадает обратно в микрофон и переводится сама — поэтому
 * на время реплики вход глушится, и часть сказанного собеседником теряется.
 */
@Composable
private fun HeadsetHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Headset,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.live_no_headset),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Нижний док -------------------------------------------------------------

@Composable
private fun MicDock(state: LiveUiState, onMicClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusRow(state)
        LiveMicButton(
            active = state.active,
            busy = state.status == LiveStatus.CONNECTING || state.status == LiveStatus.FINISHING,
            hearing = state.hearing,
            level = state.level,
            contentDescription = stringResource(
                if (state.active) R.string.live_stop else R.string.live_start,
            ),
            onClick = onMicClick,
        )
    }
}

@Composable
private fun StatusRow(state: LiveUiState) {
    val text = when {
        state.status == LiveStatus.CONNECTING -> stringResource(R.string.live_connecting)
        state.status == LiveStatus.FINISHING -> stringResource(R.string.live_finishing)
        !state.active -> stringResource(R.string.live_idle)
        state.speaking -> stringResource(R.string.live_speaking)
        state.hearing -> stringResource(R.string.live_hearing)
        else -> stringResource(R.string.live_silence)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = state.active) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.detectedLanguage?.let { code ->
                    Text(
                        text = stringResource(R.string.live_detected, code.uppercase()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatRemaining(state.remainingSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.remainingSeconds <= WARNING_SECONDS) {
                        LocalBrand.current.live
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (state.remainingSeconds <= WARNING_SECONDS) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                )
                if (state.credits > 0) {
                    Text(
                        text = stringResource(R.string.live_credits, state.credits),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Кнопка запуска. Та же «сцена», что и на главном экране, но кольцо пульсирует
 * только когда речь действительно уходит в модель, — по нему видно, тратятся ли
 * сейчас деньги.
 */
@Composable
private fun LiveMicButton(
    active: Boolean,
    busy: Boolean,
    hearing: Boolean,
    level: Float,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val brand = LocalBrand.current
    val scale by animateFloatAsState(
        targetValue = if (hearing) 1f + level * 0.1f else 1f,
        label = "liveMic",
    )

    Box(modifier = Modifier.size(MIC_STAGE), contentAlignment = Alignment.Center) {
        if (hearing) {
            val phase by rememberInfiniteTransition(label = "liveRing").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
                label = "liveRingPhase",
            )
            Canvas(Modifier.size(MIC_STAGE)) {
                val from = size.minDimension * 0.37f
                val to = size.minDimension * 0.5f
                drawCircle(
                    color = brand.live.copy(alpha = 0.28f * (1f - phase)),
                    radius = from + (to - from) * phase,
                )
            }
        }

        val glow = if (active) brand.live else brand.micIdle.last()
        Box(
            modifier = Modifier
                .size(MIC_SIZE)
                .scale(scale)
                .shadow(
                    elevation = if (active) 20.dp else 14.dp,
                    shape = CircleShape,
                    ambientColor = glow,
                    spotColor = glow,
                )
                .clip(CircleShape)
                .background(Brush.linearGradient(if (active) brand.micLive else brand.micIdle))
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )

                active -> Icon(
                    Icons.Rounded.Stop,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp),
                )

                else -> Icon(
                    Icons.Rounded.Mic,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
    }
}

/** За сколько до автостопа таймер становится тревожным. */
private const val WARNING_SECONDS = 60

private fun formatRemaining(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

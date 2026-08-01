package com.voicetomessenger.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetomessenger.R
import com.voicetomessenger.game.STROOP_ROUND_MS
import com.voicetomessenger.game.StroopColor
import com.voicetomessenger.game.StroopPrompt
import com.voicetomessenger.game.computeStroopScore
import com.voicetomessenger.game.nextStroopPrompt
import com.voicetomessenger.game.stroopAccuracyPercent
import com.voicetomessenger.game.stroopIntervalMs
import com.voicetomessenger.game.stroopLevel
import com.voicetomessenger.sound.GameSound
import kotlinx.coroutines.delay

private enum class StroopPhase { IDLE, PLAYING, FINISHED }

/**
 * Струп-тест: слово-название цвета написано чернилами другого цвета в 70% заданий, и нужно
 * называть цвет чернил, подавляя рефлекс прочитать слово. Раунд — 60 секунд без пауз между
 * заданиями; каждые 20 верных ответов время на задание сокращается по расписанию из
 * `game/Stroop.kt`, до пола в 700 мс — если не успел, задание сгорает само и это тоже ошибка.
 *
 * Логика заданий, уровня и счёта — чистые функции в `game/Stroop.kt` под юнит-тестами; этот
 * экран отвечает только за сам раунд: таймеры и реакцию на нажатия, по образцу SchulteScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StroopScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val best by viewModel.stroopBest.collectAsState()

    var phase by rememberSaveable { mutableStateOf(StroopPhase.IDLE) }
    var attempt by rememberSaveable { mutableStateOf(0) }
    // Рекорд на момент старта именно этой попытки: если сравнивать с живым `best`, то в момент
    // показа результата он мог уже обновиться асинхронной записью в DataStore этого же раунда,
    // и «новый рекорд» перестал бы срабатывать. Захват на старте исключает эту гонку.
    val previousBest = remember(attempt) { best }

    var promptSeq by remember(attempt) { mutableIntStateOf(0) }
    var prompt by remember(attempt) { mutableStateOf(nextStroopPrompt()) }
    // «Сейчас» на момент, когда Compose впервые увидел эту попытку — не 0L с последующей
    // правкой из start(): та правка целится в состояние ПРЕЖНЕЙ попытки (remember(attempt)
    // пересоздаёт объект при смене ключа и отбрасывает более ранние записи в старый), из-за
    // чего roundStartedAt оставался нулём, а тик сравнивался с временем с включения телефона —
    // разница мгновенно превышала STROOP_ROUND_MS, и раунд завершался, не начавшись.
    var promptStartedAt by remember(attempt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var roundStartedAt by remember(attempt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    var correct by remember(attempt) { mutableIntStateOf(0) }
    var errors by remember(attempt) { mutableIntStateOf(0) }
    var streak by remember(attempt) { mutableIntStateOf(0) }
    var maxStreak by remember(attempt) { mutableIntStateOf(0) }
    var reactionSamples by remember(attempt) { mutableIntStateOf(0) }
    var totalReactionMs by remember(attempt) { mutableLongStateOf(0L) }

    // true — вспышка верного ответа, false — неверного/тайм-аута, null — ничего не показывать.
    var flash by remember(attempt) { mutableStateOf<Boolean?>(null) }

    val haptics = LocalHapticFeedback.current

    fun advance(result: Boolean?, reactionMs: Long) {
        when (result) {
            true -> {
                correct += 1
                streak += 1
                maxStreak = maxOf(maxStreak, streak)
                totalReactionMs += reactionMs
                reactionSamples += 1
                flash = true
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.playSound(GameSound.CORRECT)
            }
            false -> {
                errors += 1
                streak = 0
                totalReactionMs += reactionMs
                reactionSamples += 1
                flash = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.playSound(GameSound.WRONG)
            }
            null -> {
                // Тайм-аут: задание сгорело само. Серия сбита, но реакции не было — не считаем её.
                errors += 1
                streak = 0
                flash = false
            }
        }
        promptSeq += 1
        prompt = nextStroopPrompt()
        promptStartedAt = SystemClock.elapsedRealtime()
    }

    // Общий отсчёт раунда. 100 мс тика достаточно — глазу разницы с более частым не видно.
    var tick by remember(attempt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(phase, attempt) {
        if (phase == StroopPhase.PLAYING) {
            while (true) {
                tick = SystemClock.elapsedRealtime()
                if (tick - roundStartedAt >= STROOP_ROUND_MS) {
                    phase = StroopPhase.FINISHED
                    val avg = if (reactionSamples > 0) totalReactionMs / reactionSamples else 0L
                    viewModel.saveStroopBest(computeStroopScore(correct, maxStreak, avg))
                    viewModel.playSound(GameSound.WIN)
                    break
                }
                delay(100)
            }
        }
    }
    val remainingMs = (STROOP_ROUND_MS - (tick - roundStartedAt)).coerceIn(0, STROOP_ROUND_MS)

    // Тайм-аут одного задания. Ключ promptSeq пересоздаёт эффект на каждое новое задание —
    // ответ игрока меняет promptSeq через advance() и тем самым сам отменяет старый отсчёт.
    LaunchedEffect(phase, attempt, promptSeq) {
        if (phase == StroopPhase.PLAYING) {
            delay(stroopIntervalMs(correct))
            advance(null, 0)
        }
    }

    // Вспышка гаснет сама — короче для верного, длиннее для ошибки, как в Таблицах Шульте.
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(if (flash == true) 180 else 320)
            flash = null
        }
    }

    fun onAnswer(color: StroopColor) {
        if (phase != StroopPhase.PLAYING) return
        val reaction = SystemClock.elapsedRealtime() - promptStartedAt
        advance(color == prompt.ink, reaction)
    }

    fun start() {
        // Только ключ и фаза. Время старта берёт remember(attempt) выше — своим начальным
        // значением, в момент первой композиции именно этой попытки (см. комментарий там).
        attempt += 1
        phase = StroopPhase.PLAYING
    }

    Box(Modifier.fillMaxSize().auroraBackground()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.stroop_title)) },
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
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (phase) {
                    StroopPhase.IDLE -> StroopIntro(best = best, onStart = ::start)

                    StroopPhase.PLAYING -> StroopRound(
                        prompt = prompt,
                        remainingMs = remainingMs,
                        level = stroopLevel(correct),
                        streak = streak,
                        flash = flash,
                        onAnswer = ::onAnswer,
                    )

                    StroopPhase.FINISHED -> {
                        val avg = if (reactionSamples > 0) totalReactionMs / reactionSamples else 0L
                        val score = computeStroopScore(correct, maxStreak, avg)
                        StroopResult(
                            correct = correct,
                            errors = errors,
                            accuracy = stroopAccuracyPercent(correct, errors),
                            avgReactionMs = avg,
                            maxStreak = maxStreak,
                            score = score,
                            isNewRecord = score > previousBest,
                            onRestart = ::start,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StroopIntro(best: Int, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.stroop_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (best > 0) stringResource(R.string.game_stat_record, best) else stringResource(R.string.game_stat_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onStart, shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.stroop_start), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ColumnScope.StroopRound(
    prompt: StroopPrompt,
    remainingMs: Long,
    level: Int,
    streak: Int,
    flash: Boolean?,
    onAnswer: (StroopColor) -> Unit,
) {
    val remainingSeconds = ((remainingMs + 999) / 1000).toInt()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat(stringResource(R.string.stroop_time_left), remainingSeconds.toString(), Modifier.weight(1f))
            StatDivider()
            Stat(stringResource(R.string.stroop_level), level.toString(), Modifier.weight(1f))
            StatDivider()
            Stat(
                label = stringResource(R.string.stroop_streak),
                value = streak.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (streak >= 5) GamePalette.Correct else null,
            )
        }
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        WordCard(prompt = prompt, flash = flash)
    }

    ColorGrid(onAnswer = onAnswer)
}

/** Слово-название цвета [StroopPrompt.word], написанное чернилами [StroopPrompt.ink]. */
@Composable
private fun WordCard(prompt: StroopPrompt, flash: Boolean?) {
    val scale by animateFloatAsState(
        targetValue = if (flash == true) 0.9f else 1f,
        animationSpec = tween(150),
        label = "stroopWordScale",
    )
    // Лёгкое покачивание на ошибке: игрок должен физически почувствовать «мимо», не только
    // увидеть цвет вспышки боковым зрением.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash == false) {
            shake.snapTo(0f)
            listOf(1f, -1f, 0.6f, 0f).forEach { shake.animateTo(it, tween(55)) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1.6f),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(prompt.word.labelRes()),
                color = prompt.ink.inkColor(),
                fontWeight = FontWeight.Black,
                fontSize = 42.sp,
                modifier = Modifier
                    .scale(scale)
                    .offset(x = (shake.value * 10).dp),
            )
        }
    }
}

/** Шесть больших цветных кнопок, порядок фиксирован — игрок ищет цвет глазами, а не память. */
@Composable
private fun ColorGrid(onAnswer: (StroopColor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StroopColor.entries.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { color ->
                    ColorButton(color = color, onClick = { onAnswer(color) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ColorButton(color: StroopColor, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .aspectRatio(1.5f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.medium,
        color = color.inkColor(),
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(4.dp)) {
            Text(
                text = stringResource(color.labelRes()),
                color = color.textColor(),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ColumnScope.StroopResult(
    correct: Int,
    errors: Int,
    accuracy: Int,
    avgReactionMs: Long,
    maxStreak: Int,
    score: Int,
    isNewRecord: Boolean,
    onRestart: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.stroop_result_title), style = MaterialTheme.typography.titleMedium)
            Text(score.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            if (isNewRecord) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.stroop_new_record),
                    style = MaterialTheme.typography.labelLarge,
                    color = GamePalette.Best,
                )
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f),
    ) {
        item { ResultStat(stringResource(R.string.stroop_stat_correct), correct.toString()) }
        item { ResultStat(stringResource(R.string.schulte_errors), errors.toString()) }
        item { ResultStat(stringResource(R.string.stroop_stat_accuracy), "$accuracy%") }
        item { ResultStat(stringResource(R.string.stroop_stat_reaction), stringResource(R.string.stroop_ms_value, avgReactionMs)) }
        item { ResultStat(stringResource(R.string.stroop_stat_streak), maxStreak.toString()) }
    }

    Button(onClick = onRestart, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Text(stringResource(R.string.stroop_play_again))
    }
}

@Composable
private fun ResultStat(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(2.dp))
            SectionLabel(label)
        }
    }
}

private fun StroopColor.labelRes(): Int = when (this) {
    StroopColor.RED -> R.string.stroop_color_red
    StroopColor.BLUE -> R.string.stroop_color_blue
    StroopColor.YELLOW -> R.string.stroop_color_yellow
    StroopColor.GREEN -> R.string.stroop_color_green
    StroopColor.ORANGE -> R.string.stroop_color_orange
    StroopColor.PURPLE -> R.string.stroop_color_purple
}

/** Конкретные оттенки шести цветов — насыщенные и однозначно различимые между собой. */
private fun StroopColor.inkColor(): Color = when (this) {
    StroopColor.RED -> Color(0xFFE53935)
    StroopColor.BLUE -> Color(0xFF1E88E5)
    StroopColor.YELLOW -> Color(0xFFFDD835)
    StroopColor.GREEN -> Color(0xFF43A047)
    StroopColor.ORANGE -> Color(0xFFFB8C00)
    StroopColor.PURPLE -> Color(0xFF8E24AA)
}

/** Текст на цветной кнопке: тёмный на жёлтой (иначе белый на ней не читается), иначе белый. */
private fun StroopColor.textColor(): Color =
    if (this == StroopColor.YELLOW) GamePalette.TextDark else GamePalette.TextLight

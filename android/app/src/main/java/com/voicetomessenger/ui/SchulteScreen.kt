package com.voicetomessenger.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetomessenger.R
import com.voicetomessenger.game.SCHULTE_DEFAULT_SIZE
import com.voicetomessenger.game.SCHULTE_SIZES
import com.voicetomessenger.game.shuffledSchulte
import com.voicetomessenger.sound.GameSound
import kotlinx.coroutines.delay

/**
 * Таблицы Шульте. Игрок жмёт числа по порядку 1, 2, 3…; каждое нажатие отзывается вибрацией,
 * верное даёт зелёную вспышку, неверное — красную и +1 к счётчику ошибок. Ячейки не остаются
 * закрашенными: иначе по «дыркам» видно, какие числа ещё не найдены, и таблица теряет смысл.
 * После последнего числа таймер останавливается, результат сохраняется по размеру таблицы.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchulteScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val results by viewModel.schulteResults.collectAsState()

    var size by rememberSaveable { mutableStateOf(SCHULTE_DEFAULT_SIZE) }
    // Смена размера или новая попытка меняет ключ — все состояния партии сбрасываются сами.
    var attempt by rememberSaveable { mutableStateOf(0) }
    val numbers = remember(size, attempt) { shuffledSchulte(size) }

    var next by remember(size, attempt) { mutableIntStateOf(1) }
    var startedAt by remember(size, attempt) { mutableLongStateOf(0L) }
    var resultMs by remember(size, attempt) { mutableStateOf<Long?>(null) }
    var wrongIndex by remember(size, attempt) { mutableStateOf<Int?>(null) }
    var correctIndex by remember(size, attempt) { mutableStateOf<Int?>(null) }
    var errors by remember(size, attempt) { mutableIntStateOf(0) }
    var showProgress by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current

    // Живой таймер: тикает, пока попытка началась и не завершена.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt, resultMs) {
        if (startedAt != 0L && resultMs == null) {
            while (true) {
                tick = SystemClock.elapsedRealtime()
                delay(80)
            }
        }
    }
    val elapsedMs = resultMs ?: if (startedAt != 0L) (tick - startedAt).coerceAtLeast(0) else 0L

    // Обе вспышки гаснут сами: зелёная короче красной — ошибку стоит заметить.
    LaunchedEffect(wrongIndex) {
        if (wrongIndex != null) {
            delay(320)
            wrongIndex = null
        }
    }
    LaunchedEffect(correctIndex) {
        if (correctIndex != null) {
            delay(180)
            correctIndex = null
        }
    }

    val onCell: (Int) -> Unit = onCell@{ index ->
        if (resultMs != null) return@onCell
        if (numbers[index] == next) {
            // Лёгкий тик на верном, ощутимый удар на ошибке — палец понимает исход
            // раньше, чем глаз успевает вернуться к вспышке.
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            correctIndex = index
            if (next == 1) startedAt = SystemClock.elapsedRealtime()
            next += 1
            if (next > size * size) {
                val result = SystemClock.elapsedRealtime() - startedAt
                resultMs = result
                viewModel.saveSchulteResult(size, result)
                viewModel.playSound(GameSound.WIN)
            } else {
                viewModel.playSound(GameSound.CORRECT)
            }
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            wrongIndex = index
            errors += 1
            viewModel.playSound(GameSound.WRONG)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schulte_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SizeSelector(selected = size, onSelect = { size = it })

            StatusRow(
                elapsedMs = elapsedMs,
                next = next,
                errors = errors,
                finished = resultMs != null,
            )

            // Доска квадратная и максимально большая в пределах свободного места — без прокрутки.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                Board(
                    numbers = numbers,
                    size = size,
                    correctIndex = correctIndex,
                    wrongIndex = wrongIndex,
                    onCell = onCell,
                    modifier = Modifier.size(side),
                )
            }

            Text(
                text = if (resultMs != null) {
                    stringResource(R.string.schulte_result, formatSeconds(elapsedMs))
                } else {
                    stringResource(R.string.schulte_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (resultMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (resultMs != null) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { attempt += 1 }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.schulte_new_attempt))
                }
                OutlinedButton(onClick = { showProgress = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.schulte_progress))
                }
            }
        }
    }

    if (showProgress) {
        ProgressDialog(
            size = size,
            times = results[size].orEmpty(),
            onDismiss = { showProgress = false },
        )
    }
}

@Composable
private fun SizeSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SCHULTE_SIZES.forEach { s ->
            FilterChip(
                selected = s == selected,
                onClick = { onSelect(s) },
                label = { Text("${s}×$s") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusRow(elapsedMs: Long, next: Int, errors: Int, finished: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(
            label = stringResource(R.string.schulte_timer),
            value = formatSeconds(elapsedMs),
        )
        Badge(
            label = stringResource(R.string.schulte_errors),
            value = errors.toString(),
            // Ноль ошибок не кричит красным: подсвечиваем счётчик, только когда есть что считать.
            valueColor = if (errors > 0) GamePalette.Wrong else null,
        )
        Badge(
            label = stringResource(R.string.schulte_next),
            value = if (finished) "✓" else next.toString(),
            emphasize = true,
        )
    }
}

@Composable
private fun Badge(
    label: String,
    value: String,
    emphasize: Boolean = false,
    valueColor: Color? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor
                ?: if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Board(
    numbers: List<Int>,
    size: Int,
    correctIndex: Int?,
    wrongIndex: Int?,
    onCell: (Int) -> Unit,
    modifier: Modifier,
) {
    val gap = if (size >= 6) 4.dp else 6.dp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GamePalette.Board)
            .padding(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(size) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                repeat(size) { column ->
                    val index = row * size + column
                    Cell(
                        value = numbers[index],
                        correct = index == correctIndex,
                        wrong = index == wrongIndex,
                        fontSize = cellFontSize(size),
                        onClick = { onCell(index) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(
    value: Int,
    correct: Boolean,
    wrong: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val flashing = correct || wrong
    val background = when {
        wrong -> GamePalette.Wrong
        correct -> GamePalette.Correct
        else -> GamePalette.LightCell
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            // Ripple убран: он держится дольше вспышки и подсвечивал бы уже нажатые ячейки.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = value.toString(),
            color = if (flashing) GamePalette.TextLight else GamePalette.TextDark,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
        )
    }
}

@Composable
private fun ProgressDialog(size: Int, times: List<Long>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(stringResource(R.string.schulte_progress_title, "${size}×$size")) },
        text = {
            if (times.isEmpty()) {
                Text(stringResource(R.string.schulte_progress_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.schulte_best, formatSeconds(times.min())))
                        Text(stringResource(R.string.schulte_last, formatSeconds(times.last())))
                    }
                    ProgressChart(times = times.takeLast(12))
                    Text(
                        stringResource(R.string.schulte_progress_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** Простой столбчатый график последних результатов: короче столбик — быстрее прошёл. */
@Composable
private fun ProgressChart(times: List<Long>) {
    val best = times.min()
    val max = times.max().toFloat()
    val accent = GamePalette.Accent
    val gold = GamePalette.Best
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val n = times.size
        val slot = this.size.width / n
        val barWidth = slot * 0.6f
        times.forEachIndexed { i, t ->
            val h = (t / max) * this.size.height
            val x = i * slot + (slot - barWidth) / 2f
            drawRect(
                color = if (t == best) gold else accent,
                topLeft = Offset(x, this.size.height - h),
                size = Size(barWidth, h),
            )
        }
    }
}

private fun cellFontSize(size: Int) = when (size) {
    3 -> 34.sp
    4 -> 28.sp
    5 -> 24.sp
    6 -> 20.sp
    else -> 17.sp
}

/** Миллисекунды → «12.3» (секунды с десятыми). */
private fun formatSeconds(ms: Long): String {
    val tenths = (ms + 50) / 100 // округление до десятой доли секунды
    return "${tenths / 10}.${tenths % 10}"
}

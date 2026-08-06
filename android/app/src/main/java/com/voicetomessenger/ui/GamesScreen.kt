package com.voicetomessenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetomessenger.R

/** Пустые слоты под будущие игры: намёк на планы, а не половина экрана серых заглушек. */
private const val PLACEHOLDER_COUNT = 3

private val TILE_SHAPE = RoundedCornerShape(24.dp)

/**
 * Экран выбора игры: крупные плитки с мини-превью, названием и личным результатом.
 * Ниже — тихий раздел «Скоро» из контурных слотов.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpen2048: () -> Unit,
    onOpenSchulte: () -> Unit,
    onOpenStroop: () -> Unit,
    navBar: @Composable () -> Unit,
) {
    val best by viewModel.gameBest.collectAsState()
    val schulte by viewModel.schulteResults.collectAsState()
    val stroopBest by viewModel.stroopBest.collectAsState()

    // Лучшее время по всем размерам таблицы: на витрине важен сам факт результата.
    val schulteBest = schulte.values.flatten().minOrNull()

    Box(Modifier.fillMaxSize().auroraBackground()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.games_title)) },
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
            bottomBar = navBar,
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { innerPadding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    GameTile(
                        name = stringResource(R.string.game_2048_name),
                        stat = if (best > 0) {
                            stringResource(R.string.game_stat_record, best)
                        } else {
                            stringResource(R.string.game_stat_none)
                        },
                        backdrop = listOf(Color(0xFFF6E2C8), Color(0xFFE0B489)),
                        onClick = onOpen2048,
                    ) { Game2048Preview() }
                }
                item {
                    GameTile(
                        name = stringResource(R.string.schulte_name),
                        stat = if (schulteBest != null) {
                            stringResource(R.string.game_stat_time, formatSchulteSeconds(schulteBest))
                        } else {
                            stringResource(R.string.game_stat_none)
                        },
                        backdrop = listOf(Color(0xFFEDE7DC), Color(0xFFCFC2B1)),
                        onClick = onOpenSchulte,
                    ) { SchultePreview() }
                }
                item {
                    GameTile(
                        name = stringResource(R.string.stroop_name),
                        stat = if (stroopBest > 0) {
                            stringResource(R.string.game_stat_record, stroopBest)
                        } else {
                            stringResource(R.string.game_stat_none)
                        },
                        backdrop = listOf(Color(0xFFE3D9F2), Color(0xFFB9A6DE)),
                        onClick = onOpenStroop,
                    ) { StroopPreview() }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel(stringResource(R.string.games_soon_section))
                        Spacer(Modifier.size(12.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        )
                    }
                }

                items(PLACEHOLDER_COUNT) { PlaceholderTile() }
            }
        }
    }
}

/** Плитка игры: превью на фирменной подложке, название и личный рекорд. */
@Composable
private fun GameTile(
    name: String,
    stat: String,
    backdrop: List<Color>,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TILE_SHAPE)
            .clickable(onClick = onClick),
        shape = TILE_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 6.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f)
                    .background(Brush.linearGradient(backdrop)),
                contentAlignment = Alignment.Center,
            ) {
                // Лёгкий наклон превью: доска выглядит предметом, который взяли в руки,
                // а не скриншотом.
                Box(Modifier.graphicsLayer { rotationZ = -7f }) { preview() }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stat,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Слот под будущую игру: пунктирный контур вместо серого прямоугольника с замком. */
@Composable
private fun PlaceholderTile() {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
                    ),
                )
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.game_soon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Мини-доска 2×2 из плиток 2048 — узнаётся по тёплым тонам и цифрам. */
@Composable
private fun Game2048Preview() {
    val tiles = listOf(
        2 to Color(0xFFEEE4DA),
        8 to Color(0xFFF2B179),
        4 to Color(0xFFEDE0C8),
        16 to Color(0xFFF59563),
    )
    MiniBoard(cells = 2) { row, column ->
        val (value, color) = tiles[row * 2 + column]
        MiniCell(
            text = value.toString(),
            background = color,
            textColor = if (value <= 4) GamePalette.TextDark else GamePalette.TextLight,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Мини-таблица Шульте 3×3 с числами вразнобой и вспышкой на верно нажатой единице. */
@Composable
private fun SchultePreview() {
    val numbers = listOf(3, 7, 1, 9, 5, 2, 6, 4, 8)
    MiniBoard(cells = 3) { row, column ->
        val value = numbers[row * 3 + column]
        val flash = value == 1
        MiniCell(
            text = value.toString(),
            background = if (flash) GamePalette.Correct else GamePalette.LightCell,
            textColor = if (flash) GamePalette.TextLight else GamePalette.TextDark,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Классическая иллюстрация струп-эффекта: слово «синий», написанное красными чернилами. */
@Composable
private fun StroopPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.68f)
            .aspectRatio(1.3f)
            .clip(RoundedCornerShape(10.dp))
            .background(GamePalette.LightCell),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.stroop_color_blue),
            // Тот же красный, что StroopColor.RED в StroopScreen.kt — палитра игры локальна
            // каждому экрану (см. Game2048Preview/SchultePreview выше), здесь достаточно одного тона.
            color = Color(0xFFE53935),
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun MiniBoard(cells: Int, cell: @Composable RowScope.(row: Int, column: Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(GamePalette.Board)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(cells) { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(cells) { column -> cell(row, column) }
            }
        }
    }
}

@Composable
private fun MiniCell(
    text: String,
    background: Color,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(5.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
        )
    }
}

/** Миллисекунды → «12.3». Экран прогресса форматирует так же. */
private fun formatSchulteSeconds(ms: Long): String {
    val tenths = (ms + 50) / 100
    return "${tenths / 10}.${tenths % 10}"
}

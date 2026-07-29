package com.voicetomessenger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetomessenger.R
import com.voicetomessenger.game.BOARD_SIZE
import com.voicetomessenger.game.Direction
import com.voicetomessenger.game.GameState
import com.voicetomessenger.game.move
import com.voicetomessenger.game.newGame
import com.voicetomessenger.sound.GameSound
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Game2048Screen(viewModel: MainViewModel, onBack: () -> Unit) {
    val best by viewModel.gameBest.collectAsState()

    // Партию подхватываем из хранилища: игра продолжается ровно с того места, где её
    // бросили, даже после закрытия приложения. Пока грузим — null, экран пуст мгновение.
    var state by remember { mutableStateOf<GameState?>(null) }
    LaunchedEffect(Unit) { state = viewModel.loadSavedGame2048() ?: newGame() }

    val current = state ?: return

    // Рекорд пишем по ходу партии, а не в конце: игру часто бросают, не доиграв.
    LaunchedEffect(current.score) { viewModel.saveGameBest(current.score) }

    // Партию берём из state прямо в момент свайпа, а не из захваченной копии: обработчик
    // живёт внутри pointerInput(Unit) и создаётся один раз, поэтому копия протухла бы
    // после первого хода — доска замирала бы на нём.
    // Каждый ход сохраняем сразу — «продолжить» работает без отдельной кнопки.
    val onMove: (Direction) -> Unit = onMove@{ direction ->
        val now = state ?: return@onMove
        val next = now.move(direction)
        if (next == now) return@onMove
        when {
            next.won && !now.won -> viewModel.playSound(GameSound.WIN)
            next.score > now.score -> viewModel.playSound(GameSound.MERGE)
            else -> viewModel.playSound(GameSound.TAP)
        }
        state = next
        viewModel.persistGame2048(next)
    }

    val onNewGame = {
        val fresh = newGame()
        state = fresh
        viewModel.persistGame2048(fresh)
    }

    Box(Modifier.fillMaxSize().auroraBackground()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.game_title)) },
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScoreBox(
                        label = stringResource(R.string.game_score),
                        value = current.score,
                        modifier = Modifier.weight(1f),
                    )
                    ScoreBox(
                        label = stringResource(R.string.game_best),
                        value = maxOf(best, current.score),
                        accent = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Box(Modifier.fillMaxWidth()) {
                    Board(state = current, onSwipe = onMove)
                    if (current.over) {
                        GameOverOverlay(onNewGame = onNewGame)
                    }
                }

                Text(
                    text = if (current.won) {
                        stringResource(R.string.game_won)
                    } else {
                        stringResource(R.string.game_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current.won) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (current.won) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )

                FilledTonalButton(onClick = onNewGame, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.game_new))
                }
            }
        }
    }
}

/** Счёт и рекорд: два одинаковых по весу блока, рекорд подсвечен янтарём. */
@Composable
private fun ScoreBox(label: String, value: Int, modifier: Modifier = Modifier, accent: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel(label)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = if (accent) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun Board(state: GameState, onSwipe: (Direction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .shadow(10.dp, BOARD_SHAPE, spotColor = GamePalette.TextDark)
            .clip(BOARD_SHAPE)
            .background(BOARD_COLOR)
            .padding(GAP)
            // Направление определяем по всему жесту, а не по первому кадру: короткий
            // резкий свайп иначе то и дело срабатывает по «дрожащей» первой оси.
            .pointerInput(Unit) {
                var travel = Offset.Zero
                val threshold = 24.dp.toPx()
                detectDragGestures(
                    onDragStart = { travel = Offset.Zero },
                    onDrag = { change, delta ->
                        change.consume()
                        travel += delta
                    },
                    onDragEnd = {
                        val (dx, dy) = travel
                        if (maxOf(abs(dx), abs(dy)) >= threshold) {
                            onSwipe(
                                if (abs(dx) > abs(dy)) {
                                    if (dx > 0) Direction.RIGHT else Direction.LEFT
                                } else {
                                    if (dy > 0) Direction.DOWN else Direction.UP
                                },
                            )
                        }
                    },
                )
            },
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        repeat(BOARD_SIZE) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(GAP),
            ) {
                repeat(BOARD_SIZE) { column ->
                    Tile(
                        value = state.cells[row * BOARD_SIZE + column],
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Конец партии: доска гаснет под шторкой, выход один — начать заново. */
@Composable
private fun BoxScope.GameOverOverlay(onNewGame: () -> Unit) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(BOARD_SHAPE)
            .background(GamePalette.Board.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.game_over),
                style = MaterialTheme.typography.headlineSmall,
                color = GamePalette.TextLight,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNewGame, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.game_new))
            }
        }
    }
}

/** Плитка. Новая или слитая — коротко «выпрыгивает»: ход становится заметен без анимации сдвига. */
@Composable
private fun Tile(value: Int, modifier: Modifier) {
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableIntStateOf(value) }
    LaunchedEffect(value) {
        if (value != 0 && value != previous) {
            pop.snapTo(0.8f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        previous = value
    }

    Box(
        modifier = modifier
            .scale(pop.value)
            .clip(TILE_SHAPE)
            .background(tileBackground(value)),
        contentAlignment = Alignment.Center,
    ) {
        if (value == 0) return@Box
        val digits = value.toString().length
        Text(
            text = value.toString(),
            color = if (value <= 4) TILE_TEXT_DARK else TILE_TEXT_LIGHT,
            fontWeight = FontWeight.Bold,
            // Четырёхзначные плитки не влезают в клетку кеглем двузначных.
            fontSize = when {
                digits <= 2 -> 30.sp
                digits == 3 -> 25.sp
                digits == 4 -> 20.sp
                else -> 16.sp
            },
        )
    }
}

// Палитра — общая для игр (см. GamePalette): тёплые тона оригинального 2048.
private val BOARD_COLOR = GamePalette.Board
private val EMPTY_CELL = GamePalette.EmptyCell
private val TILE_TEXT_DARK = GamePalette.TextDark
private val TILE_TEXT_LIGHT = GamePalette.TextLight
private val GAP = 7.dp
private val BOARD_SHAPE = RoundedCornerShape(20.dp)
private val TILE_SHAPE = RoundedCornerShape(10.dp)

private fun tileBackground(value: Int): Color = when (value) {
    0 -> EMPTY_CELL
    2 -> Color(0xFFEEE4DA)
    4 -> Color(0xFFEDE0C8)
    8 -> Color(0xFFF2B179)
    16 -> Color(0xFFF59563)
    32 -> Color(0xFFF67C5F)
    64 -> Color(0xFFF65E3B)
    128 -> Color(0xFFEDCF72)
    256 -> Color(0xFFEDCC61)
    512 -> Color(0xFFEDC850)
    1024 -> Color(0xFFEDC53F)
    2048 -> Color(0xFFEDC22E)
    else -> Color(0xFF3C3A32)
}

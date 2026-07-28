package com.voicetomessenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetomessenger.R

/** Сколько пустых плиток «Скоро» показываем под будущие игры (всего с реальными — 12). */
private const val PLACEHOLDER_COUNT = 10

/**
 * Экран выбора игры: плитки с мини-превью и названием. Реальные игры кликабельны,
 * плитки «Скоро» — задел под будущие. Меню может прокручиваться (в отличие от самих игр).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(onBack: () -> Unit, onOpen2048: () -> Unit, onOpenSchulte: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.games_title)) },
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GameTile(name = stringResource(R.string.game_2048_name), onClick = onOpen2048) {
                    Game2048Preview()
                }
            }
            item {
                GameTile(name = stringResource(R.string.schulte_name), onClick = onOpenSchulte) {
                    SchultePreview()
                }
            }
            items(PLACEHOLDER_COUNT) { PlaceholderTile() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameTile(name: String, onClick: () -> Unit, preview: @Composable () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
                    .background(GamePalette.Board),
                contentAlignment = Alignment.Center,
            ) { preview() }
            Text(
                text = name,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PlaceholderTile() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
            Text(
                text = stringResource(R.string.game_soon),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Мини-доска 2×2 из плиток 2048 — узнаётся по тёплым тонам и цифрам. */
@Composable
private fun Game2048Preview() {
    val tiles = listOf(2 to Color(0xFFEEE4DA), 8 to Color(0xFFF2B179), 4 to Color(0xFFEDE0C8), 16 to Color(0xFFF59563))
    Column(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0..1) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (column in 0..1) {
                    val (value, color) = tiles[row * 2 + column]
                    MiniCell(value.toString(), color, if (value <= 4) GamePalette.TextDark else GamePalette.TextLight, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Мини-таблица Шульте 3×3 с числами вразнобой и вспышкой на верно нажатой единице. */
@Composable
private fun SchultePreview() {
    val numbers = listOf(3, 7, 1, 9, 5, 2, 6, 4, 8)
    Column(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (row in 0..2) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (column in 0..2) {
                    val value = numbers[row * 3 + column]
                    val flash = value == 1
                    MiniCell(
                        text = value.toString(),
                        background = if (flash) GamePalette.Correct else GamePalette.LightCell,
                        textColor = if (flash) GamePalette.TextLight else GamePalette.TextDark,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniCell(text: String, background: Color, textColor: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

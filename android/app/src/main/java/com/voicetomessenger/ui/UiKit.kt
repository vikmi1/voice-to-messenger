package com.voicetomessenger.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voicetomessenger.ui.theme.LocalBrand

/**
 * Общие элементы оформления: мягкий фирменный фон и «таблетки» выбора.
 * Держим их в одном месте, чтобы главный экран и экран игр выглядели одной семьёй.
 */

/**
 * Фон приложения: базовый цвет плюс две очень слабые радиальные подсветки —
 * холодная из-под шапки и тёплая справа внизу. Заметны ровно настолько, чтобы экран
 * перестал быть плоским листом, и не мешают контрасту текста.
 */
@Composable
fun Modifier.auroraBackground(): Modifier {
    val brand = LocalBrand.current
    val base = MaterialTheme.colorScheme.background
    return drawBehind {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                colors = listOf(brand.auroraCool, Color.Transparent),
                center = Offset(size.width * 0.08f, size.height * 0.02f),
                radius = size.minDimension * 1.25f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(brand.auroraWarm, Color.Transparent),
                center = Offset(size.width * 1.05f, size.height * 0.62f),
                radius = size.minDimension * 1.0f,
            ),
        )
    }
}

/**
 * Цвета сегментированного переключателя. Отдельно заданы состояния «выключено»:
 * по умолчанию Material гасит выбранный сегмент сильнее невыбранного, и заблокированный
 * переключатель читается наоборот — будто выбран не тот пункт.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun brandSegmentedColors(): SegmentedButtonColors {
    val scheme = MaterialTheme.colorScheme
    return SegmentedButtonDefaults.colors(
        activeContainerColor = scheme.primary,
        activeContentColor = scheme.onPrimary,
        activeBorderColor = scheme.primary,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = scheme.onSurfaceVariant,
        inactiveBorderColor = scheme.outlineVariant,
        disabledActiveContainerColor = scheme.primary.copy(alpha = 0.45f),
        disabledActiveContentColor = scheme.onPrimary,
        disabledActiveBorderColor = Color.Transparent,
        disabledInactiveContainerColor = Color.Transparent,
        disabledInactiveContentColor = scheme.onSurfaceVariant.copy(alpha = 0.38f),
        disabledInactiveBorderColor = scheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/** Подпись раздела: мелкая, спокойная, с небольшим разрядом. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Таблетка выбора вместо материаловского [androidx.compose.material3.FilterChip]:
 * выбранная заливается основным цветом, остальные — тихим контейнером, рамок нет.
 * Высота 46 dp — палец попадает без прицеливания.
 */
@Composable
fun BrandChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when {
            selected && enabled -> scheme.primary
            selected -> scheme.primary.copy(alpha = 0.38f)
            enabled -> scheme.surfaceContainerHigh
            else -> scheme.surfaceContainerHigh.copy(alpha = 0.5f)
        },
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = when {
            selected -> scheme.onPrimary
            enabled -> scheme.onSurfaceVariant
            else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        label = "chipContent",
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 46.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
        )
    }
}

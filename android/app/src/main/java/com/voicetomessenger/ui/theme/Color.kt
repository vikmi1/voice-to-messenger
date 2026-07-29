package com.voicetomessenger.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Фирменная палитра «ирис и янтарь».
 *
 * Раньше приложение брало цвета из обоев (Material You), поэтому чипы были сиреневые,
 * а микрофон — синий: единого узнаваемого тона не существовало. Схема ниже задана вручную
 * по ролям Material 3, поэтому светлая и тёмная темы согласованы, а бренд не зависит от телефона.
 *
 * Ирис (сине-фиолетовый) — основной: спокойный, «рабочий», не спорит с тёплой палитрой игр.
 * Янтарь — третичный акцент: им подсвечены подсказки, рекорды и всё, что связано с играми.
 */

// --- Светлая схема ---------------------------------------------------------

internal val PrimaryLight = Color(0xFF4B4ED2)
internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val PrimaryContainerLight = Color(0xFFE2E0FF)
internal val OnPrimaryContainerLight = Color(0xFF0D0064)

internal val SecondaryLight = Color(0xFF5B5D72)
internal val OnSecondaryLight = Color(0xFFFFFFFF)
internal val SecondaryContainerLight = Color(0xFFE0E0F9)
internal val OnSecondaryContainerLight = Color(0xFF181A2C)

internal val TertiaryLight = Color(0xFF9A4A00)
internal val OnTertiaryLight = Color(0xFFFFFFFF)
internal val TertiaryContainerLight = Color(0xFFFFDCC2)
internal val OnTertiaryContainerLight = Color(0xFF331200)

internal val ErrorLight = Color(0xFFB3261E)
internal val OnErrorLight = Color(0xFFFFFFFF)
internal val ErrorContainerLight = Color(0xFFF9DEDC)
internal val OnErrorContainerLight = Color(0xFF410E0B)

internal val BackgroundLight = Color(0xFFFBF8FF)
internal val OnBackgroundLight = Color(0xFF1B1B21)
internal val SurfaceVariantLight = Color(0xFFE4E1EC)
internal val OnSurfaceVariantLight = Color(0xFF46464F)
internal val OutlineLight = Color(0xFF77767F)
internal val OutlineVariantLight = Color(0xFFC7C5D0)
internal val InverseSurfaceLight = Color(0xFF303036)
internal val InverseOnSurfaceLight = Color(0xFFF3EFF7)
internal val InversePrimaryLight = Color(0xFFC1C1FF)

internal val SurfaceDimLight = Color(0xFFDBD9E0)
internal val SurfaceBrightLight = Color(0xFFFBF8FF)
internal val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
internal val SurfaceContainerLowLight = Color(0xFFF5F2FA)
internal val SurfaceContainerLight = Color(0xFFEFEDF5)
internal val SurfaceContainerHighLight = Color(0xFFE9E7EF)
internal val SurfaceContainerHighestLight = Color(0xFFE4E1E9)

// --- Тёмная схема ----------------------------------------------------------

internal val PrimaryDark = Color(0xFFC1C1FF)
internal val OnPrimaryDark = Color(0xFF262593)
internal val PrimaryContainerDark = Color(0xFF3A3AB9)
internal val OnPrimaryContainerDark = Color(0xFFE2E0FF)

internal val SecondaryDark = Color(0xFFC4C4DD)
internal val OnSecondaryDark = Color(0xFF2D2F42)
internal val SecondaryContainerDark = Color(0xFF444559)
internal val OnSecondaryContainerDark = Color(0xFFE0E0F9)

internal val TertiaryDark = Color(0xFFFFB77C)
internal val OnTertiaryDark = Color(0xFF532300)
internal val TertiaryContainerDark = Color(0xFF762F00)
internal val OnTertiaryContainerDark = Color(0xFFFFDCC2)

internal val ErrorDark = Color(0xFFF2B8B5)
internal val OnErrorDark = Color(0xFF601410)
internal val ErrorContainerDark = Color(0xFF8C1D18)
internal val OnErrorContainerDark = Color(0xFFF9DEDC)

internal val BackgroundDark = Color(0xFF121218)
internal val OnBackgroundDark = Color(0xFFE4E1E9)
internal val SurfaceVariantDark = Color(0xFF46464F)
internal val OnSurfaceVariantDark = Color(0xFFC7C5D0)
internal val OutlineDark = Color(0xFF918F9A)
internal val OutlineVariantDark = Color(0xFF46464F)
internal val InverseSurfaceDark = Color(0xFFE4E1E9)
internal val InverseOnSurfaceDark = Color(0xFF303036)
internal val InversePrimaryDark = Color(0xFF4B4ED2)

internal val SurfaceDimDark = Color(0xFF121218)
internal val SurfaceBrightDark = Color(0xFF38373E)
internal val SurfaceContainerLowestDark = Color(0xFF0D0D12)
internal val SurfaceContainerLowDark = Color(0xFF1B1B21)
internal val SurfaceContainerDark = Color(0xFF1F1F25)
internal val SurfaceContainerHighDark = Color(0xFF29292F)
internal val SurfaceContainerHighestDark = Color(0xFF34343A)

/**
 * Цвета, которым нет роли в Material: градиент микрофона, «в эфире» и мягкая заливка фона.
 * Берутся через [LocalBrand], поэтому светлый и тёмный варианты переключаются сами.
 */
@Immutable
data class BrandColors(
    /** Заливка кнопки микрофона в покое — сверху светлее, снизу глубже. */
    val micIdle: List<Color>,
    /** Заливка кнопки во время записи. */
    val micLive: List<Color>,
    /** Цвет пульсирующего кольца и точки «идёт запись». */
    val live: Color,
    /** Две мягкие радиальные подсветки фона: холодная сверху, тёплая справа. */
    val auroraCool: Color,
    val auroraWarm: Color,
)

private val BrandLight = BrandColors(
    micIdle = listOf(Color(0xFF6E6BFF), Color(0xFF3B34C0)),
    micLive = listOf(Color(0xFFFF6A4D), Color(0xFFDE2E14)),
    live = Color(0xFFE23D28),
    auroraCool = Color(0x1A4B4ED2),
    auroraWarm = Color(0x14FF9A3E),
)

private val BrandDark = BrandColors(
    micIdle = listOf(Color(0xFF6F6DF0), Color(0xFF3F3ABE)),
    micLive = listOf(Color(0xFFFF7A5E), Color(0xFFC7301A)),
    live = Color(0xFFFF6B57),
    auroraCool = Color(0x336E6BFF),
    auroraWarm = Color(0x1FFF9A3E),
)

internal fun brandColors(dark: Boolean): BrandColors = if (dark) BrandDark else BrandLight

val LocalBrand = staticCompositionLocalOf { BrandLight }

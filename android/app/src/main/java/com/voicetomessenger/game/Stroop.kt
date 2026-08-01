package com.voicetomessenger.game

import kotlin.random.Random

/** Шесть цветов струп-теста. Конкретный оттенок и локализованное название рисует UI. */
enum class StroopColor { RED, BLUE, YELLOW, GREEN, ORANGE, PURPLE }

/** Одно задание: слово [word] написано чернилами цвета [ink]. Игрок называет [ink], не [word]. */
data class StroopPrompt(val word: StroopColor, val ink: StroopColor)

/** Доля заданий, где слово и цвет чернил не совпадают, — собственно струп-эффект. */
const val STROOP_CONFLICT_PERCENT = 70

/** Длительность раунда. */
const val STROOP_ROUND_MS = 60_000L

/** Сколько подряд верных ответов держит один уровень скорости. */
const val STROOP_ANSWERS_PER_LEVEL = 20

/** Ниже этого интервал между заданиями не падает — иначе игра станет нечестной, а не сложной. */
const val STROOP_MIN_INTERVAL_MS = 700L

/** Время реакции, с которого начинает начисляться бонус за скорость в [computeStroopScore]. */
private const val STROOP_BASE_REACTION_MS = 2000L

/** Время на задание по уровню: 2000→1700→1500→1300→1100 мс, дальше падает тем же шагом до пола. */
private val STROOP_LEVEL_INTERVALS_MS = longArrayOf(2000, 1700, 1500, 1300, 1100)
private const val STROOP_LEVEL_STEP_MS = 150L

/**
 * Следующее задание. [conflictPercent]% случаев цвет чернил гарантированно отличается от
 * слова (конфликт — суть струп-эффекта), остальные — совпадает. Раздельные ветки, а не общий
 * random-pick с проверкой post factum: так доля конфликтов точная, а не «примерно».
 */
fun nextStroopPrompt(random: Random = Random, conflictPercent: Int = STROOP_CONFLICT_PERCENT): StroopPrompt {
    val word = StroopColor.entries.random(random)
    val conflict = random.nextInt(100) < conflictPercent
    val ink = if (conflict) StroopColor.entries.filter { it != word }.random(random) else word
    return StroopPrompt(word, ink)
}

/** Номер уровня (с единицы) по числу верных ответов — для бейджа в интерфейсе. */
fun stroopLevel(correct: Int): Int = correct / STROOP_ANSWERS_PER_LEVEL + 1

/** Сколько миллисекунд у игрока на текущее задание при таком числе верных ответов подряд. */
fun stroopIntervalMs(correct: Int): Long {
    val step = correct / STROOP_ANSWERS_PER_LEVEL
    val ms = if (step < STROOP_LEVEL_INTERVALS_MS.size) {
        STROOP_LEVEL_INTERVALS_MS[step]
    } else {
        val beyond = step - STROOP_LEVEL_INTERVALS_MS.size + 1
        STROOP_LEVEL_INTERVALS_MS.last() - beyond * STROOP_LEVEL_STEP_MS
    }
    return ms.coerceAtLeast(STROOP_MIN_INTERVAL_MS)
}

/** Точность в процентах, округление до целого. Ноль заданий — ноль, а не деление на ноль. */
fun stroopAccuracyPercent(correct: Int, errors: Int): Int {
    val total = correct + errors
    if (total <= 0) return 0
    return (correct * 100 + total / 2) / total
}

/**
 * Итоговый счёт: очки за верные ответы + бонус за лучшую серию + бонус за скорость реакции.
 * Без единого верного ответа счёт нулевой — иначе раунд из одних тайм-аутов давал бы очки
 * просто потому, что бонус за реакцию считается от средней в ноль миллисекунд.
 */
fun computeStroopScore(correct: Int, maxStreak: Int, avgReactionMs: Long): Int {
    if (correct <= 0) return 0
    val reactionBonus = (STROOP_BASE_REACTION_MS - avgReactionMs).coerceAtLeast(0)
    return correct * 100 + maxStreak * 50 + reactionBonus.toInt()
}

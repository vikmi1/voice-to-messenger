package com.voicetomessenger.game

import kotlin.random.Random

/** Доступные размеры таблицы Шульте — от 3×3 до 7×7. */
val SCHULTE_SIZES = listOf(3, 4, 5, 6, 7)

const val SCHULTE_DEFAULT_SIZE = 5

/**
 * Числа 1..size² в случайном порядке — плоский список для сетки size×size.
 *
 * Логика вынесена из Compose и не зависит от Android: перемешивание можно проверить
 * в JVM-тесте. Каждая новая попытка вызывает это заново — расстановка всегда другая.
 */
fun shuffledSchulte(size: Int, random: Random = Random): List<Int> =
    (1..size * size).shuffled(random)

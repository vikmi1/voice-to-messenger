package com.voicetomessenger.game

import kotlin.random.Random

const val BOARD_SIZE = 4
const val WINNING_TILE = 2048

/** Куда игрок свайпнул. */
enum class Direction { LEFT, RIGHT, UP, DOWN }

/**
 * Поле 2048. [cells] — плоский список из [BOARD_SIZE] × [BOARD_SIZE] значений,
 * ноль означает пустую клетку.
 *
 * Логика вынесена из Compose и не зависит от Android: её можно гонять в JVM-тестах.
 */
data class GameState(
    val cells: List<Int>,
    val score: Int = 0,
    /** Плитка 2048 уже собиралась. Игру не заканчиваем — можно набирать дальше. */
    val won: Boolean = false,
    val over: Boolean = false,
)

fun newGame(random: Random = Random): GameState =
    GameState(cells = List(BOARD_SIZE * BOARD_SIZE) { 0 }).spawn(random).spawn(random)

fun GameState.move(direction: Direction, random: Random = Random): GameState {
    val next = cells.toMutableList()
    var gained = 0
    lineIndices(direction).forEach { line ->
        val (collapsed, points) = collapse(line.map { cells[it] })
        gained += points
        line.forEachIndexed { position, cell -> next[cell] = collapsed[position] }
    }

    // Ход, который ничего не сдвинул, не считается: новая плитка не появляется,
    // иначе поле забивалось бы от свайпов в стену.
    if (next == cells) return this

    val moved = copy(
        cells = next,
        score = score + gained,
        won = won || next.any { it >= WINNING_TILE },
    ).spawn(random)
    return moved.copy(over = !moved.hasMoves())
}

/** Есть ли ещё ходы: свободная клетка или пара одинаковых соседей. */
fun GameState.hasMoves(): Boolean {
    if (cells.any { it == 0 }) return true
    for (row in 0 until BOARD_SIZE) {
        for (column in 0 until BOARD_SIZE) {
            val value = cells[row * BOARD_SIZE + column]
            if (column + 1 < BOARD_SIZE && cells[row * BOARD_SIZE + column + 1] == value) return true
            if (row + 1 < BOARD_SIZE && cells[(row + 1) * BOARD_SIZE + column] == value) return true
        }
    }
    return false
}

private fun GameState.spawn(random: Random): GameState {
    val empty = cells.indices.filter { cells[it] == 0 }
    if (empty.isEmpty()) return this
    val at = empty[random.nextInt(empty.size)]
    // Четвёрка примерно в одном появлении из десяти — классические правила игры.
    val value = if (random.nextInt(10) == 0) 4 else 2
    return copy(cells = cells.toMutableList().also { it[at] = value })
}

/**
 * Сдвигает значения линии к её началу и складывает соседние одинаковые.
 * Возвращает новую линию и набранные за слияния очки.
 */
internal fun collapse(line: List<Int>): Pair<List<Int>, Int> {
    val values = line.filter { it != 0 }
    val result = mutableListOf<Int>()
    var points = 0
    var index = 0
    while (index < values.size) {
        // Плитка участвует в слиянии один раз за ход: 2-2-4 даёт 4-4, а не 8.
        if (index + 1 < values.size && values[index] == values[index + 1]) {
            val merged = values[index] * 2
            result += merged
            points += merged
            index += 2
        } else {
            result += values[index]
            index++
        }
    }
    while (result.size < line.size) result += 0
    return result to points
}

/**
 * Партия в компактной строке: «score|won|over|c0,c1,…,c15». Чистый Kotlin без org.json,
 * чтобы сериализацию можно было гонять в JVM-тестах вместе с остальной логикой игры.
 */
fun GameState.serialize(): String =
    "$score|${if (won) 1 else 0}|${if (over) 1 else 0}|${cells.joinToString(",")}"

/** Разбирает строку из [serialize]; при любой порче формата возвращает null. */
fun parseGameState(text: String): GameState? = runCatching {
    val parts = text.split("|")
    if (parts.size != 4) return null
    val cells = parts[3].split(",").map { it.toInt() }
    if (cells.size != BOARD_SIZE * BOARD_SIZE) return null
    GameState(
        cells = cells,
        score = parts[0].toInt(),
        won = parts[1] == "1",
        over = parts[2] == "1",
    )
}.getOrNull()

/**
 * Индексы клеток по линиям в порядке «от края, к которому свайпнули».
 * Так [collapse] всегда работает одинаково — как сдвиг влево.
 */
private fun lineIndices(direction: Direction): List<List<Int>> = when (direction) {
    Direction.LEFT ->
        (0 until BOARD_SIZE).map { row -> (0 until BOARD_SIZE).map { row * BOARD_SIZE + it } }
    Direction.UP ->
        (0 until BOARD_SIZE).map { column -> (0 until BOARD_SIZE).map { it * BOARD_SIZE + column } }
    Direction.RIGHT -> lineIndices(Direction.LEFT).map { it.reversed() }
    Direction.DOWN -> lineIndices(Direction.UP).map { it.reversed() }
}

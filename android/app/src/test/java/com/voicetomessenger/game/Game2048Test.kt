package com.voicetomessenger.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Game2048Test {

    @Test
    fun `сдвигает значения к началу линии`() {
        val (line, points) = collapse(listOf(0, 2, 0, 4))
        assertEquals(listOf(2, 4, 0, 0), line)
        assertEquals(0, points)
    }

    @Test
    fun `складывает одинаковые соседние и даёт очки`() {
        val (line, points) = collapse(listOf(2, 2, 4, 0))
        assertEquals(listOf(4, 4, 0, 0), line)
        assertEquals(4, points)
    }

    @Test
    fun `плитка сливается только один раз за ход`() {
        val (line, points) = collapse(listOf(2, 2, 2, 2))
        assertEquals(listOf(4, 4, 0, 0), line)
        assertEquals(8, points)
    }

    @Test
    fun `новая четвёрка не сливается повторно в том же ходе`() {
        val (line, points) = collapse(listOf(2, 2, 4, 4))
        assertEquals(listOf(4, 8, 0, 0), line)
        assertEquals(12, points)
    }

    @Test
    fun `ход в стену ничего не меняет и не рождает плитку`() {
        val state = GameState(
            cells = listOf(
                2, 4, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        )
        assertEquals(state, state.move(Direction.LEFT, Random(1)))
    }

    @Test
    fun `удачный ход добавляет ровно одну плитку`() {
        val state = GameState(
            cells = listOf(
                0, 0, 2, 2,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        )
        val next = state.move(Direction.LEFT, Random(1))

        assertEquals(4, next.score)
        assertEquals(4, next.cells.first())
        // Была одна четвёрка от слияния плюс одна подброшенная плитка.
        assertEquals(2, next.cells.count { it != 0 })
    }

    @Test
    fun `две тысячи сорок восемь помечают победу но игра продолжается`() {
        val state = GameState(
            cells = listOf(
                1024, 1024, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        )
        val next = state.move(Direction.LEFT, Random(1))

        assertTrue(next.won)
        assertFalse(next.over)
    }

    @Test
    fun `полное поле без пар считается проигрышем`() {
        val state = GameState(
            cells = listOf(
                2, 4, 2, 4,
                4, 2, 4, 2,
                2, 4, 2, 4,
                4, 2, 4, 2,
            ),
        )
        assertFalse(state.hasMoves())
    }

    @Test
    fun `новая игра начинается с двух плиток`() {
        val state = newGame(Random(42))
        assertEquals(2, state.cells.count { it != 0 })
        assertEquals(0, state.score)
        assertTrue(state.cells.all { it == 0 || it == 2 || it == 4 })
    }

    @Test
    fun `сериализация партии переживает круговой рейс`() {
        val state = GameState(
            cells = listOf(
                2, 4, 8, 16,
                0, 32, 0, 64,
                128, 0, 256, 0,
                0, 0, 0, 2,
            ),
            score = 1234,
            won = true,
            over = false,
        )
        assertEquals(state, parseGameState(state.serialize()))
    }

    @Test
    fun `порченая строка партии даёт null`() {
        assertEquals(null, parseGameState("мусор"))
        assertEquals(null, parseGameState("1|0|0|1,2,3"))
    }
}

package com.voicetomessenger.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SchulteTest {

    @Test
    fun `таблица содержит все числа от одного до квадрата размера`() {
        SCHULTE_SIZES.forEach { size ->
            val cells = shuffledSchulte(size, Random(1))
            assertEquals(size * size, cells.size)
            assertEquals((1..size * size).toList(), cells.sorted())
        }
    }

    @Test
    fun `новая попытка перемешивает числа заново`() {
        val first = shuffledSchulte(5, Random(1))
        val second = shuffledSchulte(5, Random(2))
        // Разные сиды дают разную расстановку — каждая попытка выглядит иначе.
        assertNotEquals(first, second)
    }
}

package com.voicetomessenger.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StroopTest {

    @Test
    fun `при стопроцентном конфликте слово и цвет всегда разные`() {
        val random = Random(1)
        repeat(500) {
            val prompt = nextStroopPrompt(random, conflictPercent = 100)
            assertTrue("${prompt.word} vs ${prompt.ink}", prompt.word != prompt.ink)
        }
    }

    @Test
    fun `при нулевом конфликте слово и цвет всегда совпадают`() {
        val random = Random(1)
        repeat(500) {
            val prompt = nextStroopPrompt(random, conflictPercent = 0)
            assertEquals(prompt.word, prompt.ink)
        }
    }

    @Test
    fun `доля конфликтных заданий близка к заданному проценту`() {
        val random = Random(42)
        val conflicts = (1..5000).count {
            nextStroopPrompt(random, STROOP_CONFLICT_PERCENT).let { it.word != it.ink }
        }
        val share = conflicts / 5000.0
        assertTrue("доля конфликтов $share далека от 0.70", share in 0.66..0.74)
    }

    @Test
    fun `уровень растёт каждые 20 верных ответов`() {
        assertEquals(1, stroopLevel(0))
        assertEquals(1, stroopLevel(19))
        assertEquals(2, stroopLevel(20))
        assertEquals(3, stroopLevel(40))
    }

    @Test
    fun `интервал между заданиями идёт по расписанию и не проваливается ниже пола`() {
        assertEquals(2000L, stroopIntervalMs(0))
        assertEquals(1700L, stroopIntervalMs(20))
        assertEquals(1500L, stroopIntervalMs(40))
        assertEquals(1300L, stroopIntervalMs(60))
        assertEquals(1100L, stroopIntervalMs(80))
        assertEquals(STROOP_MIN_INTERVAL_MS, stroopIntervalMs(100_000))
        assertTrue(stroopIntervalMs(1000) >= STROOP_MIN_INTERVAL_MS)
    }

    @Test
    fun `точность считается корректно и не делит на ноль`() {
        assertEquals(0, stroopAccuracyPercent(0, 0))
        assertEquals(100, stroopAccuracyPercent(10, 0))
        assertEquals(0, stroopAccuracyPercent(0, 10))
        assertEquals(67, stroopAccuracyPercent(2, 1))
    }

    @Test
    fun `без единого верного ответа счёт нулевой`() {
        assertEquals(0, computeStroopScore(0, 0, 0))
        assertEquals(0, computeStroopScore(0, 5, 100))
    }

    @Test
    fun `счёт складывается из верных ответов, серии и бонуса за реакцию`() {
        // 10 верных по 100, серия 5 по 50, реакция 500 мс даёт бонус 2000-500=1500.
        assertEquals(10 * 100 + 5 * 50 + 1500, computeStroopScore(10, 5, 500))
        // Реакция медленнее базовой бонуса не даёт, но и не уходит в минус.
        assertEquals(10 * 100 + 5 * 50, computeStroopScore(10, 5, 5000))
    }
}

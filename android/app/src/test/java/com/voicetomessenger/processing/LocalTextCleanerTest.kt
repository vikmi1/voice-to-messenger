package com.voicetomessenger.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTextCleanerTest {

    @Test
    fun `убирает слова-паразиты и ставит точку`() {
        val result = LocalTextCleaner.clean("эм надо завтра встретиться часа в три")
        assertEquals("Надо завтра встретиться часа в три.", result)
    }

    @Test
    fun `убирает многословные паразиты`() {
        val result = LocalTextCleaner.clean("это самое я как бы уже отправил файл")
        assertEquals("Я уже отправил файл.", result)
    }

    @Test
    fun `не трогает слова, внутри которых есть паразит`() {
        // «мм» — паразит, но внутри «суммы» его трогать нельзя.
        val result = LocalTextCleaner.clean("суммы уже сошлись")
        assertEquals("Суммы уже сошлись.", result)
    }

    @Test
    fun `делает заглавной букву после точки и убирает пробел перед запятой`() {
        val result = LocalTextCleaner.clean("привет . как дела , всё хорошо?")
        assertEquals("Привет. Как дела, всё хорошо?", result)
    }

    @Test
    fun `сохраняет уже корректный текст`() {
        val result = LocalTextCleaner.clean("Встреча в 15:00 у входа.")
        assertEquals("Встреча в 15:00 у входа.", result)
    }

    @Test
    fun `пустой ввод даёт пустую строку`() {
        assertEquals("", LocalTextCleaner.clean("   "))
    }
}

package com.voicetomessenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сводка расхода в том виде, в каком её отдаёт `/admin/api/usage`.
 *
 * Форма снята с ответа боевого сервера. Главное, что здесь проверяется, — null в
 * стоимости: модель не из каталога цены не имеет, и «неизвестно» не должно
 * превратиться в ноль, иначе панель покажет платный запрос бесплатным.
 */
private const val SERVER_JSON = """
{
  "days": 30,
  "credit_usd": 0.001,
  "total": {
    "requests": 4, "input_tokens": 2149, "output_tokens": 221, "cached_tokens": 800,
    "audio_seconds": 84.0, "usd": 0.0062, "credits": 7, "errors": 1, "unpriced": 1
  },
  "by_model": [
    {"key": "openai/gpt-4o", "op": "process", "requests": 2, "unpriced": 0,
     "input_tokens": 1949, "output_tokens": 171, "audio_seconds": 0.0, "usd": 0.005},
    {"key": "mistral/voxtral-mini-latest", "op": "transcribe", "requests": 1, "unpriced": 0,
     "input_tokens": 0, "output_tokens": 0, "audio_seconds": 84.0, "usd": 0.0042},
    {"key": "work/своя-модель", "op": "process", "requests": 1, "unpriced": 1,
     "input_tokens": 200, "output_tokens": 50, "audio_seconds": 0.0, "usd": 0.0}
  ],
  "by_day": [
    {"day": "2026-07-31", "requests": 3, "unpriced": 0, "usd": 0.0062},
    {"day": "2026-08-01", "requests": 1, "unpriced": 1, "usd": 0.0}
  ],
  "recent": [
    {"id": "a1", "ts": "2026-08-01T09:15:00+00:00", "op": "process", "model": "своя-модель",
     "input_tokens": 200, "output_tokens": 50, "audio_seconds": null, "ok": 1,
     "usd": null, "credits": null},
    {"id": "a2", "ts": "2026-07-31T22:05:03+00:00", "op": "transcribe",
     "model": "voxtral-mini-latest", "input_tokens": 0, "output_tokens": 0,
     "audio_seconds": 84.0, "ok": 1, "usd": 0.0042, "credits": 5},
    {"id": "a3", "ts": "2026-07-31T21:00:00+00:00", "op": "process", "model": "gpt-4o",
     "input_tokens": 0, "output_tokens": 0, "audio_seconds": null, "ok": 0,
     "usd": 0.0, "credits": 0}
  ]
}
"""

class UsageReportTest {

    private val report = UsageReport.parse(SERVER_JSON)

    @Test
    fun `итоги читаются целиком`() {
        assertEquals(30, report.days)
        assertEquals(0.001, report.creditUsd, 1e-9)
        assertEquals(4, report.total.requests)
        assertEquals(2149, report.total.inputTokens)
        assertEquals(221, report.total.outputTokens)
        assertEquals(800, report.total.cachedTokens)
        assertEquals(84.0, report.total.audioSeconds, 1e-9)
        assertEquals(7, report.total.credits)
        assertEquals(1, report.total.errors)
        assertEquals(1, report.total.unpriced)
    }

    @Test
    fun `запрос без цены не выглядит бесплатным`() {
        val unpriced = report.recent.first { it.model == "своя-модель" }
        assertNull("цена неизвестна, а не ноль", unpriced.credits)

        val group = report.byModel.first { it.key == "work/своя-модель" }
        assertTrue("вся группа без цены — сумму показывать нельзя", group.allUnpriced)
    }

    @Test
    fun `настоящий ноль остаётся нулём`() {
        // Упавший запрос ничего не потратил: это именно ноль, а не «неизвестно».
        val failed = report.recent.first { !it.ok }
        assertEquals(0, failed.credits)
    }

    @Test
    fun `группа с частью запросов без цены помечена как заниженная`() {
        val day = report.byDay.first { it.day == "2026-08-01" }
        assertTrue(day.allUnpriced)

        val full = report.byDay.first { it.day == "2026-07-31" }
        assertFalse(full.allUnpriced)
        assertEquals(0, full.unpriced)
    }

    @Test
    fun `строки запросов читаются с типом операции и длительностью`() {
        val transcribe = report.recent.first { it.op == "transcribe" }
        assertEquals("voxtral-mini-latest", transcribe.model)
        assertEquals(84.0, transcribe.audioSeconds!!, 1e-9)
        assertEquals(5, transcribe.credits)

        val process = report.recent.first { it.model == "своя-модель" }
        assertNull("у обработки текста аудио нет", process.audioSeconds)
    }

    @Test
    fun `пустой ответ не роняет разбор`() {
        val empty = UsageReport.parse("""{"days": 7, "credit_usd": 0.001, "total": {}}""")
        assertEquals(0, empty.total.requests)
        assertTrue(empty.byModel.isEmpty())
        assertTrue(empty.byDay.isEmpty())
        assertTrue(empty.recent.isEmpty())
    }
}

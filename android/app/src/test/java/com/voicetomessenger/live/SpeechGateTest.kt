package com.voicetomessenger.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {

    private fun SpeechGate.feed(level: Float, times: Int) = repeat(times) { update(level) }

    @Test
    fun `тишина ворота не открывает`() {
        val gate = SpeechGate()
        gate.feed(0.001f, 50)
        assertFalse(gate.isOpen)
    }

    @Test
    fun `речь открывает ворота`() {
        val gate = SpeechGate()
        gate.feed(0.002f, 20)   // фон тихой комнаты
        gate.feed(0.2f, 3)      // заговорили
        assertTrue(gate.isOpen)
    }

    @Test
    fun `короткая пауза между словами ворота не закрывает`() {
        val gate = SpeechGate()
        gate.feed(0.002f, 20)
        gate.feed(0.2f, 5)
        gate.feed(0.001f, 4)    // вдох посреди фразы — меньше releaseChunks
        assertTrue(gate.isOpen)
    }

    @Test
    fun `долгая тишина ворота закрывает`() {
        val gate = SpeechGate()
        gate.feed(0.002f, 20)
        gate.feed(0.2f, 5)
        gate.feed(0.001f, 10)
        assertFalse(gate.isOpen)
    }

    @Test
    fun `в шумном месте фон не считается речью`() {
        val gate = SpeechGate()
        // Кафе: ровный гул заметно выше абсолютного порога, но это не речь в микрофон.
        gate.feed(0.05f, 100)
        assertFalse(gate.isOpen)
        // А голос рядом с микрофоном громче гула в разы — его слышно.
        gate.feed(0.4f, 3)
        assertTrue(gate.isOpen)
    }

    @Test
    fun `одиночный щелчок ворота не открывает`() {
        val gate = SpeechGate(attackChunks = 2)
        gate.feed(0.002f, 20)
        gate.update(0.5f)       // один громкий кадр
        gate.feed(0.001f, 5)
        assertFalse(gate.isOpen)
    }

    @Test
    fun `reset возвращает в исходное состояние`() {
        val gate = SpeechGate()
        gate.feed(0.002f, 20)
        gate.feed(0.2f, 5)
        assertTrue(gate.isOpen)
        gate.reset()
        assertFalse(gate.isOpen)
    }
}

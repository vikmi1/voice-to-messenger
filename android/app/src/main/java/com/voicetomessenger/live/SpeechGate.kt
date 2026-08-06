package com.voicetomessenger.live

/**
 * Решает по громкости, говорят сейчас или молчат.
 *
 * Зачем: живой перевод тарифицируется по секундам аудио, ушедшим в модель, а в
 * разговоре молчания больше, чем речи. Тишину в модель не отправляем — это самая
 * крупная статья экономии на сессии.
 *
 * Порог не фиксированный: в тихой комнате и в кафе «тишина» звучит по-разному, а
 * жёсткое число либо резало бы речь, либо не срабатывало вовсе. Поэтому уровень
 * шума оцениваем по самому потоку — пока ворота закрыты, накопленное считается фоном.
 *
 * Гистерезис обязателен в обе стороны: без выдержки на открытии воротами дёргал бы
 * каждый стук, без выдержки на закрытии обрубались бы паузы между словами.
 */
class SpeechGate(
    /** Сколько порций подряд должно быть громко, чтобы признать это речью. */
    private val attackChunks: Int = 2,
    /** Сколько порций подряд должно быть тихо, чтобы признать паузу законченной. */
    private val releaseChunks: Int = 8,
) {

    var isOpen: Boolean = false
        private set

    /** Оценка фонового шума. -1 — ещё не измеряли. */
    private var noiseFloor: Float = -1f
    private var loudRun = 0
    private var quietRun = 0

    /**
     * Скармливает громкость очередной порции (0..1) и возвращает текущее состояние ворот.
     *
     * @return true, если порцию нужно отправить в распознавание.
     */
    fun update(rms: Float): Boolean {
        if (noiseFloor < 0f) noiseFloor = rms

        val openLevel = maxOf(noiseFloor * OPEN_FACTOR, ABSOLUTE_FLOOR)
        // Закрываемся при заметно меньшей громкости, чем открывались: иначе на границе
        // порога ворота хлопали бы туда-сюда посреди фразы.
        val closeLevel = openLevel * CLOSE_RATIO

        if (isOpen) {
            if (rms < closeLevel) {
                quietRun++
                if (quietRun >= releaseChunks) {
                    isOpen = false
                    loudRun = 0
                }
            } else {
                quietRun = 0
            }
        } else {
            // Фон обновляем только в тишине: иначе громкая речь сама поднимет порог
            // и вторая половина фразы окажется «тихой».
            noiseFloor = noiseFloor * (1 - FLOOR_ADAPT) + rms * FLOOR_ADAPT
            if (rms > openLevel) {
                loudRun++
                if (loudRun >= attackChunks) {
                    isOpen = true
                    quietRun = 0
                }
            } else {
                loudRun = 0
            }
        }
        return isOpen
    }

    fun reset() {
        isOpen = false
        noiseFloor = -1f
        loudRun = 0
        quietRun = 0
    }

    private companion object {
        /** Во сколько раз громче фона должно быть, чтобы считаться речью. */
        const val OPEN_FACTOR = 2.5f

        /** Ниже этого не открываемся никогда: в полной тишине фон близок к нулю,
         *  и любой относительный порог сработал бы от шороха. */
        const val ABSOLUTE_FLOOR = 0.012f

        const val CLOSE_RATIO = 0.6f

        /** Скорость подстройки под фон: медленная, чтобы пауза в речи не стала «шумом». */
        const val FLOOR_ADAPT = 0.05f
    }
}

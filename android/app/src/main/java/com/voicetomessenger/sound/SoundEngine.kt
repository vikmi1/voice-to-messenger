package com.voicetomessenger.sound

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Звуковые события в играх. */
enum class GameSound { TAP, CORRECT, WRONG, WIN, MERGE }

/**
 * Короткие звуковые эффекты для игр.
 *
 * Все звуки **синтезируются в коде** из синусоид с затуханием — ни одного внешнего файла,
 * поэтому нет и лицензионных ограничений при продаже приложения. PCM генерируется один раз
 * при первом обращении, грузится в [SoundPool] и дальше проигрывается с низкой задержкой.
 *
 * Фоновую музыку сюда же можно добавить позже: положить трек в `res/raw`, поднять
 * `MediaPlayer` в этом классе и включать/выключать тем же флагом [enabled].
 */
class SoundEngine {

    @Volatile
    var enabled: Boolean = true

    // SoundPool и загрузку PCM откладываем до первого звука: если игрок в игры не заходит,
    // аудиоресурсы вообще не создаются.
    private val pool: SoundPool by lazy { buildPool() }
    private val ids: Map<GameSound, Int> by lazy { loadSounds() }

    fun play(sound: GameSound) {
        if (!enabled) return
        val id = ids[sound] ?: return
        pool.play(id, 0.7f, 0.7f, 1, 0, 1f)
    }

    fun release() {
        // Трогаем ленивые поля только если пул уже создавался.
        if (poolInitialized) pool.release()
    }

    private var poolInitialized = false

    private fun buildPool(): SoundPool {
        poolInitialized = true
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
    }

    private fun loadSounds(): Map<GameSound, Int> = mapOf(
        // Мягкий клик по ячейке.
        GameSound.TAP to pool.loadTone(tone(660.0, 60)),
        // «Верно» — короткая восходящая пара нот.
        GameSound.CORRECT to pool.loadTone(tone(660.0, 70) + tone(990.0, 110)),
        // «Неверно» — низкий короткий зуммер.
        GameSound.WRONG to pool.loadTone(tone(180.0, 180, harmonics = true)),
        // Победа — маленькое арпеджио.
        GameSound.WIN to pool.loadTone(
            tone(523.0, 110) + tone(659.0, 110) + tone(784.0, 110) + tone(1047.0, 220),
        ),
        // Слияние плиток в 2048 — короткий средний «блип».
        GameSound.MERGE to pool.loadTone(tone(520.0, 70)),
    )
}

private const val SAMPLE_RATE = 44_100

/**
 * PCM-семплы одной ноты [freq] Гц длиной [ms] мс с экспоненциальным затуханием.
 * [harmonics] подмешивает грубую гармонику — так «неверно» звучит резче, как зуммер.
 */
private fun tone(freq: Double, ms: Int, harmonics: Boolean = false): ShortArray {
    val count = SAMPLE_RATE * ms / 1000
    return ShortArray(count) { i ->
        val t = i.toDouble() / SAMPLE_RATE
        val envelope = exp(-3.0 * i / count) // плавный спад к концу ноты
        var value = sin(2 * PI * freq * t)
        if (harmonics) value += 0.5 * sin(2 * PI * freq * 2 * t)
        (value * envelope * 0.35 * Short.MAX_VALUE).toInt().toShort()
    }
}

/** Заворачивает PCM в минимальный WAV во временный файл и загружает во [SoundPool]. */
private fun SoundPool.loadTone(samples: ShortArray): Int {
    val file = File.createTempFile("snd", ".wav").apply {
        writeBytes(wavBytes(samples))
        deleteOnExit()
    }
    return load(file.absolutePath, 1)
}

/** 16-бит моно WAV-контейнер вокруг PCM-семплов. */
private fun wavBytes(samples: ShortArray): ByteArray {
    val dataSize = samples.size * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(36 + dataSize)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)               // размер подчанка fmt
    buffer.putShort(1)              // PCM
    buffer.putShort(1)              // моно
    buffer.putInt(SAMPLE_RATE)
    buffer.putInt(SAMPLE_RATE * 2)  // байт в секунду
    buffer.putShort(2)              // выравнивание блока
    buffer.putShort(16)             // бит на семпл
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataSize)
    samples.forEach { buffer.putShort(it) }
    return buffer.array()
}

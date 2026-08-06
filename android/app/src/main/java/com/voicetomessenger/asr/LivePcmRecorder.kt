package com.voicetomessenger.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** Порция сырого звука и её громкость: [bytes] — PCM_S16LE, [rms] — 0..1. */
data class PcmChunk(val bytes: ByteArray, val rms: Float) {
    // data class с ByteArray сравнивается по ссылке — переопределяем, чтобы не удивлять.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PcmChunk && bytes.contentEquals(other.bytes) && rms == other.rms)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + rms.hashCode()
}

/**
 * Микрофон → поток PCM для живого перевода.
 *
 * Здесь [AudioRecord], а не [AudioRecorder]: MediaRecorder умеет только писать файл,
 * а живому переводу нужны сырые кадры прямо во время речи. Формат — тот, что ждёт
 * потоковая модель: PCM 16 бит, 16 кГц, моно.
 */
class LivePcmRecorder {

    /**
     * Порции по [CHUNK_MS] миллисекунд, пока поток собирают.
     *
     * Отмена подписки останавливает и освобождает микрофон. Разрешение RECORD_AUDIO
     * проверяет вызывающий — экран не начинает сессию без него.
     */
    @SuppressLint("MissingPermission")
    fun chunks(): Flow<PcmChunk> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) throw IllegalStateException("Микрофон недоступен")

        val record = AudioRecord(
            // VOICE_RECOGNITION: без автоусиления и шумодава, которые сбивают распознавание.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Микрофон недоступен")
        }

        try {
            record.startRecording()
            val buffer = ByteArray(CHUNK_BYTES)
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < CHUNK_BYTES) {
                    val read = record.read(buffer, filled, CHUNK_BYTES - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled <= 0) break
                emit(PcmChunk(buffer.copyOf(filled), rms(buffer, filled)))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Порция звука. 100 мс — компромисс: реже трафик, но заметнее задержка ворот тишины. */
        const val CHUNK_MS = 100
        const val CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS / 1000

        /** Среднеквадратичная громкость порции, 0..1. */
        private fun rms(buffer: ByteArray, length: Int): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                // little-endian: младший байт первый, старший — знаковый.
                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                val value = sample.toShort().toDouble()
                sum += value * value
                i += 2
            }
            val samples = length / 2
            if (samples == 0) return 0f
            return (sqrt(sum / samples) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        }
    }
}

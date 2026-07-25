package com.voicetomessenger.asr

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Запись голоса в m4a для серверного Whisper.
 *
 * Файл живёт в кэше и удаляется сразу после расшифровки — аудио не храним
 * (см. приватность в дизайн-документе).
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var paused = false

    val isRecording: Boolean
        get() = recorder != null

    val isPaused: Boolean
        get() = paused

    /** @return true, если запись пошла. */
    fun start(): Boolean {
        cancel()
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return runCatching {
            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = instance
            output = file
            paused = false
            true
        }.getOrElse {
            runCatching { instance.release() }
            file.delete()
            false
        }
    }

    /**
     * Пауза записи. Пишем в один файл, поэтому склейка кусков не нужна.
     *
     * @return true, если запись действительно встала на паузу.
     */
    fun pause(): Boolean {
        val instance = recorder ?: return false
        if (paused) return true
        return runCatching {
            instance.pause()
            paused = true
            true
        }.getOrDefault(false)
    }

    /** @return true, если запись продолжилась. */
    fun resume(): Boolean {
        val instance = recorder ?: return false
        if (!paused) return true
        return runCatching {
            instance.resume()
            paused = false
            true
        }.getOrDefault(false)
    }

    /** @return записанный файл или null, если запись не удалась (например, слишком короткая). */
    fun stop(): File? {
        val instance = recorder ?: return null
        recorder = null
        paused = false
        val file = output
        output = null

        val stopped = runCatching { instance.stop() }.isSuccess
        runCatching { instance.release() }
        if (!stopped || file == null || !file.exists() || file.length() < MIN_BYTES) {
            file?.delete()
            return null
        }
        return file
    }

    fun cancel() {
        val instance = recorder ?: return
        recorder = null
        paused = false
        runCatching { instance.stop() }
        runCatching { instance.release() }
        output?.delete()
        output = null
    }

    /** Текущая громкость 0..1 для индикатора записи. */
    fun level(): Float {
        val instance = recorder ?: return 0f
        if (paused) return 0f
        val amplitude = runCatching { instance.maxAmplitude }.getOrDefault(0)
        return (amplitude / 12_000f).coerceIn(0f, 1f)
    }

    private companion object {
        /** Меньше — это заведомо пустой контейнер без звука. */
        const val MIN_BYTES = 1_024
    }
}

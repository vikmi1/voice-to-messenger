package com.voicetomessenger.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Проговаривает переводы в наушник, по одному и по очереди.
 *
 * Очередь обязательна: пока звучит предыдущая фраза, приходит следующая, а две
 * реплики разом на слух неразбираемы. Файлы кладём в кэш и стираем сразу после
 * воспроизведения — аудио в приложении не хранится (см. приватность в дизайн-документе).
 */
class TranslationPlayer(private val context: Context) {

    private val queue = ArrayDeque<File>()
    private var player: MediaPlayer? = null

    /** Непрерывная озвучка: куски PCM и поток, который их проигрывает. */
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var streamThread: Thread? = null

    @Volatile
    private var streaming = false

    private val _playing = MutableStateFlow(false)

    /** Звучит ли перевод прямо сейчас — по этому флагу экран глушит микрофон без наушников. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Synchronized
    fun enqueue(id: Int, mp3: ByteArray) {
        val file = File(context.cacheDir, "live_tts_$id.mp3")
        runCatching { file.writeBytes(mp3) }.onFailure { return }
        queue.addLast(file)
        if (player == null) playNext()
    }

    @Synchronized
    private fun playNext() {
        player?.release()
        player = null
        val file = queue.removeFirstOrNull()
        if (file == null) {
            _playing.value = false
            return
        }
        val instance = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    // SPEECH + MEDIA: система сама уводит звук в подключённые наушники и
                    // приглушает чужую музыку на время реплики.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnCompletionListener {
                file.delete()
                playNext()
            }
            setOnErrorListener { _, _, _ ->
                file.delete()
                playNext()
                true
            }
        }
        val started = runCatching {
            instance.setDataSource(file.absolutePath)
            instance.prepare()
            instance.start()
        }.isSuccess
        if (!started) {
            instance.release()
            file.delete()
            playNext()
            return
        }
        player = instance
        _playing.value = true
    }

    /**
     * Кусок непрерывной озвучки (режим одной модели).
     *
     * Здесь [AudioTrack], а не [MediaPlayer]: модель говорит потоком, готового файла на
     * фразу у неё нет, а собирать его по кускам значило бы ждать конца фразы — ровно ту
     * задержку, ради которой эта модель и берётся.
     *
     * Пишем в отдельном потоке: [AudioTrack.write] блокируется, пока буфер полон, и на
     * главном потоке это был бы ANR.
     */
    @Synchronized
    fun stream(pcm: ByteArray, sampleRate: Int) {
        if (streamThread == null) startStream(sampleRate)
        chunks.offer(pcm)
    }

    private fun startStream(sampleRate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_OUT, ENCODING)
        if (minBuffer <= 0) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_OUT)
                    .build(),
            )
            // Полсекунды запаса: сеть отдаёт куски неровно, а на пустом буфере слышен щелчок.
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        streaming = true
        track.play()
        streamThread = Thread({
            try {
                while (streaming) {
                    val chunk = chunks.poll(IDLE_MS, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        _playing.value = false
                        continue
                    }
                    _playing.value = true
                    var offset = 0
                    while (offset < chunk.size && streaming) {
                        val written = track.write(chunk, offset, chunk.size - offset)
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (_: InterruptedException) {
                // Сессию остановили — выходим молча.
            } finally {
                runCatching { track.pause(); track.flush(); track.stop() }
                track.release()
                _playing.value = false
            }
        }, "live-audio-out").apply { isDaemon = true; start() }
    }

    /** Обрывает озвучку и чистит очередь — например, когда сессию остановили. */
    @Synchronized
    fun clear() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        queue.forEach { it.delete() }
        queue.clear()

        streaming = false
        chunks.clear()
        streamThread?.interrupt()
        streamThread = null

        _playing.value = false
    }

    /**
     * Подключены ли наушники.
     *
     * Без них озвучка попадает обратно в микрофон, и перевод начинает переводить сам
     * себя — экран в этом случае глушит микрофон на время реплики.
     */
    fun headsetConnected(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in HEADSET_TYPES
        }
    }

    private companion object {
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Сколько ждать следующий кусок, прежде чем считать, что озвучка смолкла. */
        const val IDLE_MS = 300L

        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
    }
}

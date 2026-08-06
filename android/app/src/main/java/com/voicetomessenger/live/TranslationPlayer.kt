package com.voicetomessenger.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File
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

    /** Обрывает озвучку и чистит очередь — например, когда сессию остановили. */
    @Synchronized
    fun clear() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        queue.forEach { it.delete() }
        queue.clear()
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
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
    }
}

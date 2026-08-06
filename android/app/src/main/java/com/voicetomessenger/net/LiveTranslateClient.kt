package com.voicetomessenger.net

import android.content.Context
import android.util.Base64
import com.voicetomessenger.R
import com.voicetomessenger.data.SettingsRepository
import com.voicetomessenger.localizedString
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** Что приходит с сервера во время живого перевода. */
sealed interface LiveEvent {
    /** Сессия распознавания поднялась — можно слать звук. */
    data class Ready(val limitSeconds: Int) : LiveEvent

    /** Язык говорящего, определённый моделью. */
    data class Language(val code: String) : LiveEvent

    /** Ещё не законченная фраза: приходит целиком, склеивать не надо. */
    data class Partial(val text: String) : LiveEvent

    /** Фраза закрыта и ушла на перевод. */
    data class Unit(val id: Int, val original: String) : LiveEvent

    data class Translation(val id: Int, val text: String, val sameLanguage: Boolean) : LiveEvent

    /** Озвучка фразы, mp3 целиком. */
    data class Audio(val id: Int, val mp3: ByteArray) : LiveEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Audio && id == other.id && mp3.contentEquals(other.mp3))

        override fun hashCode(): Int = 31 * id + mp3.contentHashCode()
    }

    /**
     * Кусок непрерывной озвучки (режим одной модели): сырой PCM, а не готовый файл.
     *
     * Такая модель говорит потоком, вперемешку с распознаванием, и «фразы целиком»
     * у неё просто нет — играть это надо по мере поступления.
     */
    data class AudioStream(val pcm: ByteArray, val sampleRate: Int) : LiveEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is AudioStream && sampleRate == other.sampleRate && pcm.contentEquals(other.pcm))

        override fun hashCode(): Int = 31 * sampleRate + pcm.contentHashCode()
    }

    data class Credits(val spent: Int) : LiveEvent

    /** Вышло время сессии — сервер закрывает её сам. */
    data object LimitReached : LiveEvent

    data class Failed(val message: String) : LiveEvent

    /** Сессия закончилась: сервер дописал расход и попрощался. */
    data object Done : LiveEvent
}

/**
 * Соединение живого перевода.
 *
 * Отдельный OkHttpClient: у общего стоит readTimeout 180 с под стриминг ответа,
 * а здесь связь держится всю сессию и живость проверяется пингами.
 */
class LiveTranslateClient(
    private val settings: SettingsRepository,
    private val context: Context,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Открывает сессию. Звук можно слать после [LiveEvent.Ready]. */
    suspend fun open(targetLang: String, speak: Boolean): LiveConnection {
        val current = settings.current()
        if (!current.isServerConfigured) {
            throw BackendException(context.localizedString(R.string.err_server_not_configured))
        }
        val base = current.serverUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$base/api/live")
            .header("Authorization", "Bearer ${current.apiToken}")
            .build()
        return LiveConnection(http, request, targetLang, speak, context)
    }
}

class LiveConnection internal constructor(
    http: OkHttpClient,
    request: Request,
    targetLang: String,
    speak: Boolean,
    private val context: Context,
) {

    // replay: сокет открывается прямо в конструкторе, а подписчик появляется через
    // несколько строк — без буфера повтора быстрый отказ (нет сети, сервер не отвечает)
    // прилетел бы раньше подписки и потерялся, а экран завис бы на «Подключаюсь…».
    // extraBufferCapacity: события идут пачками (текст + озвучка), терять их
    // из-за медленного подписчика тоже нельзя.
    private val _events = MutableSharedFlow<LiveEvent>(replay = 8, extraBufferCapacity = 128)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    @Volatile
    private var closed = false

    private val socket: WebSocket = http.newWebSocket(
        request,
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject()
                        .put("type", "start")
                        .put("target_lang", targetLang)
                        .put("speak", speak)
                        .toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { _events.tryEmit(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Сервер шлёт только текст; двоичные кадры — не наш протокол.
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                val reason = when (response?.code) {
                    401 -> context.localizedString(R.string.err_server_token)
                    404 -> context.localizedString(R.string.err_server_endpoint)
                    else -> t.message ?: context.localizedString(R.string.err_live_connection)
                }
                _events.tryEmit(LiveEvent.Failed(reason))
                _events.tryEmit(LiveEvent.Done)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _events.tryEmit(LiveEvent.Done)
            }
        },
    )

    /** Порция звука в распознавание. */
    fun sendAudio(pcm: ByteArray) {
        if (!closed) socket.send(pcm.toByteString())
    }

    /** Смена языка или озвучки на ходу — переподключаться не нужно. */
    fun reconfigure(targetLang: String, speak: Boolean) {
        if (closed) return
        socket.send(
            JSONObject()
                .put("type", "config")
                .put("target_lang", targetLang)
                .put("speak", speak)
                .toString(),
        )
    }

    /**
     * Штатное завершение: сервер дочитает остаток фразы, доозвучит её и допишет расход.
     * Сокет закрываем не мы — дожидаемся [LiveEvent.Done].
     */
    fun stop() {
        if (!closed) socket.send(JSONObject().put("type", "stop").toString())
    }

    /** Аварийное закрытие: экран ушёл, ждать нечего. */
    fun close() {
        closed = true
        socket.close(1000, null)
    }

    private fun parse(raw: String): LiveEvent? = runCatching {
        val json = JSONObject(raw)
        when (json.optString("type")) {
            "ready" -> LiveEvent.Ready(json.optInt("limit_seconds", 600))
            "language" -> LiveEvent.Language(json.optString("code"))
            "partial" -> LiveEvent.Partial(json.optString("text"))
            "unit" -> LiveEvent.Unit(json.optInt("id"), json.optString("original"))
            "translation" -> LiveEvent.Translation(
                json.optInt("id"),
                json.optString("text"),
                json.optBoolean("same_language"),
            )
            "audio" -> LiveEvent.Audio(
                json.optInt("id"),
                Base64.decode(json.optString("data"), Base64.DEFAULT),
            )
            "audio_stream" -> LiveEvent.AudioStream(
                Base64.decode(json.optString("data"), Base64.DEFAULT),
                json.optInt("sample_rate", 24_000),
            )
            "credits" -> LiveEvent.Credits(json.optInt("spent"))
            "limit" -> LiveEvent.LimitReached
            // Отказ по токену сервер помечает машинным словом — человеческий текст
            // (и на нужном языке) у приложения уже есть.
            "error" -> LiveEvent.Failed(
                json.optString("message").takeIf { it != "unauthorized" }
                    ?: context.localizedString(R.string.err_server_token),
            )
            "done" -> LiveEvent.Done
            else -> null
        }
    }.getOrNull()
}

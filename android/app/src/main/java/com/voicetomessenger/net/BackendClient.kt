package com.voicetomessenger.net

import com.voicetomessenger.data.Language
import com.voicetomessenger.data.Options
import com.voicetomessenger.data.SettingsRepository
import com.voicetomessenger.data.Style
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Ошибка обращения к бэкенду с текстом, который можно показать пользователю. */
class BackendException(message: String) : IOException(message)

/**
 * Клиент бэкенда voice-to-messenger.
 *
 * Адрес и токен читаются из настроек на каждый запрос, поэтому смена сервера
 * в настройках действует сразу, без пересоздания клиента.
 */
class BackendClient(private val settings: SettingsRepository) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Ответ /api/process приходит потоком — читаем его долго и небольшими порциями.
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Текст → готовое сообщение. Эмитит куски ответа по мере их прихода. */
    fun processStream(text: String, styleId: String, targetLang: String): Flow<String> = flow {
        val (base, token) = credentials()
        val payload = JSONObject()
            .put("text", text)
            .put("style", styleId)
            .put("target_lang", targetLang)
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url("$base/api/process")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        http.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            // charStream() сам склеивает многобайтовые символы, разорванные между чтениями.
            val reader = response.body?.charStream() ?: throw BackendException("Пустой ответ сервера")
            val buffer = CharArray(512)
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                if (read > 0) emit(String(buffer, 0, read))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Аудиофайл → сырой текст (серверный Whisper). */
    suspend fun transcribe(file: File, mimeType: String = "audio/mp4"): String =
        withContext(Dispatchers.IO) {
            val (base, token) = credentials()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                .build()

            val request = Request.Builder()
                .url("$base/api/transcribe")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                ensureSuccessful(response)
                val raw = response.body?.string().orEmpty()
                JSONObject(raw).optString("text").trim()
            }
        }

    /** Актуальные стили и языки из админ-панели. */
    suspend fun options(): Options = withContext(Dispatchers.IO) {
        val (base, token) = credentials()
        val request = Request.Builder()
            .url("$base/api/options")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            parseOptions(JSONObject(response.body?.string().orEmpty()))
        }
    }

    private suspend fun credentials(): Pair<String, String> {
        val current = settings.current()
        if (!current.isServerConfigured) {
            throw BackendException("Сервер не настроен — укажи адрес и токен в настройках")
        }
        return current.serverUrl.trimEnd('/') to current.apiToken
    }

    private fun ensureSuccessful(response: Response) {
        if (response.isSuccessful) return
        // Бэкенд объясняет причину в поле error — оно понятнее, чем голый код ответа.
        val explained = runCatching {
            JSONObject(response.body?.string().orEmpty()).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        throw BackendException(
            explained ?: when (response.code) {
                401 -> "Сервер отклонил токен — проверь API-токен в настройках"
                404 -> "Эндпоинт не найден — проверь адрес сервера"
                in 500..599 -> "Ошибка на сервере (${response.code})"
                else -> "Сервер ответил ${response.code}"
            },
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun parseOptions(root: JSONObject): Options {
            fun <T> JSONArray.mapItems(transform: (JSONObject) -> T): List<T> =
                (0 until length()).map { transform(getJSONObject(it)) }

            val styles = root.optJSONArray("styles")
                ?.mapItems { Style(it.getString("id"), it.getString("name")) }
                .orEmpty()
            val languages = root.optJSONArray("languages")
                ?.mapItems { Language(it.getString("code"), it.getString("name")) }
                .orEmpty()
            return Options(
                styles = styles.ifEmpty { Options.DEFAULT.styles },
                languages = languages.ifEmpty { Options.DEFAULT.languages },
            )
        }
    }
}

package com.voicetomessenger.net

import android.content.Context
import com.voicetomessenger.R
import com.voicetomessenger.data.Language
import com.voicetomessenger.data.Options
import com.voicetomessenger.data.SettingsRepository
import com.voicetomessenger.data.Style
import com.voicetomessenger.localizedString
import com.voicetomessenger.processing.ProcessRequest
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
class BackendClient(
    private val settings: SettingsRepository,
    // Тексты ошибок доходят до пользователя (снекбар, карточка на экране, панель
    // пузыря), поэтому берём их из ресурсов на выбранном языке, а не из литералов.
    private val context: Context,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Ответ /api/process приходит потоком — читаем его долго и небольшими порциями.
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Текст → готовое сообщение. Эмитит куски ответа по мере их прихода.
     *
     * [onUsageId] вызывается, как только пришли заголовки, — то есть до первого куска
     * текста. По этому id потом спрашивается стоимость запроса ([usageCredits]).
     * Старый бэкенд заголовка не отдаёт, тогда колбэк просто не вызывается.
     */
    fun processStream(request: ProcessRequest, onUsageId: (String) -> Unit = {}): Flow<String> = flow {
        val (base, token) = credentials()
        val payload = JSONObject()
            .put("text", request.raw)
            .put("style", request.styleId)
            .put("target_lang", request.targetLang)
            .put("as_list", request.asList)
            .put("emoji", request.emoji)
            .put("examples", JSONArray(request.examples))
            .toString()
            .toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$base/api/process")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        http.newCall(httpRequest).execute().use { response ->
            ensureSuccessful(response)
            response.header(USAGE_ID_HEADER)?.takeIf { it.isNotBlank() }?.let(onUsageId)
            // charStream() сам склеивает многобайтовые символы, разорванные между чтениями.
            val reader = response.body?.charStream()
                ?: throw BackendException(context.localizedString(R.string.err_server_empty_body))
            val buffer = CharArray(512)
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                if (read > 0) emit(String(buffer, 0, read))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Что вернуло серверное распознавание: текст и id строки расхода (её ещё нет у старого бэкенда). */
    data class Transcription(val text: String, val usageId: String?)

    /** Аудиофайл → сырой текст (серверный Whisper). */
    suspend fun transcribe(file: File, mimeType: String = "audio/mp4"): Transcription =
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
                val json = JSONObject(response.body?.string().orEmpty())
                Transcription(
                    text = json.optString("text").trim(),
                    usageId = json.optString("usage_id").takeIf { it.isNotBlank() },
                )
            }
        }

    /**
     * Сколько кредитов стоил запрос с таким id. null — если сервер не ответил.
     *
     * Ошибки глотаем намеренно: строка «потрачено N кредитов» — справочная, и ронять
     * из-за неё показ готового сообщения (или сыпать снекбарами) нельзя.
     */
    suspend fun usageCredits(usageId: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val (base, token) = credentials()
            val request = Request.Builder()
                .url("$base/api/usage/$usageId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.isNull("credits")) null else json.optInt("credits")
            }
        }.getOrNull()
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
            throw BackendException(context.localizedString(R.string.err_server_not_configured))
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
                401 -> context.localizedString(R.string.err_server_token)
                404 -> context.localizedString(R.string.err_server_endpoint)
                in 500..599 -> context.localizedString(R.string.err_server_5xx, response.code)
                else -> context.localizedString(R.string.err_server_code, response.code)
            },
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** Id строки расхода: приходит в заголовке, потому что тело /api/process — поток текста. */
        const val USAGE_ID_HEADER = "X-Usage-Id"

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

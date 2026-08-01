package com.voicetomessenger.net

import com.voicetomessenger.data.AdminConfig
import com.voicetomessenger.data.ModelCatalog
import com.voicetomessenger.data.SettingsRepository
import com.voicetomessenger.data.UsageReport
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Клиент админского API бэкенда (`/admin/api/...`).
 *
 * Авторизуется отдельным админ-токеном (заголовок `X-Admin-Token`), а не обычным
 * `api_token`: тот даёт только обработку текста, этот — правку всех настроек.
 */
class AdminClient(private val settings: SettingsRepository) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Тестовый прогон ждёт ответ модели целиком, без стриминга.
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Текущая конфигурация бэкенда. Заодно служит проверкой админ-токена. */
    suspend fun loadConfig(): AdminConfig = withContext(Dispatchers.IO) {
        AdminConfig.parse(get("/admin/api/config"))
    }

    /** Проверяет токен, не сохраняя его: используется на экране входа в панель. */
    suspend fun checkToken(token: String): AdminConfig = withContext(Dispatchers.IO) {
        AdminConfig.parse(get("/admin/api/config", tokenOverride = token))
    }

    suspend fun saveConfig(config: AdminConfig) = withContext(Dispatchers.IO) {
        val (base, token) = credentials()
        val request = Request.Builder()
            .url("$base/admin/api/config")
            .header("X-Admin-Token", token)
            .post(config.toJson().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use(::ensureAdminSuccessful)
    }

    /** Подобранные модели с ценами для выпадашек. Старый бэкенд отдаст 404 — см. вызывающего. */
    suspend fun catalog(): ModelCatalog = withContext(Dispatchers.IO) {
        ModelCatalog.parse(get("/admin/api/catalog"))
    }

    /** Сводка расхода за последние [days] дней. Старый бэкенд отдаст 404 — см. вызывающего. */
    suspend fun usage(days: Int): UsageReport = withContext(Dispatchers.IO) {
        val (base, token) = credentials()
        // Сдвиг пояса телефона от UTC в минутах: по нему сервер нарезает сутки, чтобы
        // «сегодня» и разбивка по дням шли по местным часам, а не по UTC.
        val tzOffset = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60
        val url = "$base/admin/api/usage".toHttpUrl().newBuilder()
            .addQueryParameter("days", days.toString())
            .addQueryParameter("tz_offset", tzOffset.toString())
            .build()
        val request = Request.Builder().url(url).header("X-Admin-Token", token).get().build()

        http.newCall(request).execute().use { response ->
            ensureAdminSuccessful(response)
            UsageReport.parse(response.body?.string().orEmpty())
        }
    }

    /** Живой список моделей провайдера — тянется бэкендом у самого провайдера. */
    suspend fun models(providerKey: String): List<String> = withContext(Dispatchers.IO) {
        val (base, token) = credentials()
        val url = "$base/admin/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("provider", providerKey)
            .build()
        val request = Request.Builder().url(url).header("X-Admin-Token", token).get().build()

        http.newCall(request).execute().use { response ->
            ensureAdminSuccessful(response)
            val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("models")
            (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
        }
    }

    /**
     * Прогон текста через сохранённые настройки.
     *
     * [providerKey] и [model] переопределяют активные, чтобы сравнить модели,
     * ничего не сохраняя; пустые значения означают «как в настройках».
     */
    suspend fun test(
        text: String,
        providerKey: String,
        model: String,
        styleId: String,
        targetLang: String,
    ): String = withContext(Dispatchers.IO) {
        val (base, token) = credentials()
        val payload = JSONObject()
            .put("text", text)
            .put("provider", providerKey)
            .put("model", model)
            .put("style", styleId)
            .put("target_lang", targetLang)
            .toString()
            .toRequestBody(JSON)

        val request = Request.Builder()
            .url("$base/admin/api/test")
            .header("X-Admin-Token", token)
            .post(payload)
            .build()

        http.newCall(request).execute().use { response ->
            ensureAdminSuccessful(response)
            JSONObject(response.body?.string().orEmpty()).optString("text")
        }
    }

    private suspend fun get(path: String, tokenOverride: String? = null): String {
        val (base, token) = credentials(tokenOverride)
        val request = Request.Builder()
            .url("$base$path")
            .header("X-Admin-Token", token)
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            ensureAdminSuccessful(response)
            response.body?.string().orEmpty()
        }
    }

    private suspend fun credentials(tokenOverride: String? = null): Pair<String, String> {
        val current = settings.current()
        val url = current.serverUrl.trim().trimEnd('/')
        if (url.isBlank()) throw BackendException("Не указан адрес сервера в настройках")
        val token = tokenOverride ?: current.adminToken
        if (token.isBlank()) throw BackendException("Не указан админ-токен")
        return url to token
    }

    private fun ensureAdminSuccessful(response: Response) {
        if (response.isSuccessful) return
        val serverMessage = runCatching {
            JSONObject(response.body?.string().orEmpty()).optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
        throw BackendException(
            serverMessage ?: when (response.code) {
                401 -> "Админ-токен отклонён сервером"
                404 -> "Админ-API не найдено — обнови бэкенд"
                else -> "Сервер ответил ${response.code}"
            },
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

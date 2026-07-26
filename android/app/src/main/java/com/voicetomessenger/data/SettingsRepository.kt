package com.voicetomessenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Настройки приложения и закэшированные стили/языки с бэкенда. */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val STYLE_ID = stringPreferencesKey("style_id")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val AS_LIST = booleanPreferencesKey("as_list")
        val EMOJI = booleanPreferencesKey("emoji")
        val ASR_MODE = stringPreferencesKey("asr_mode")
        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val PROCESSING_MODE = stringPreferencesKey("processing_mode")
        val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
        val ADMIN_TOKEN = stringPreferencesKey("admin_token")
        val OPTIONS_JSON = stringPreferencesKey("options_json")
    }

    val settings: Flow<Settings> = store.data.map { prefs ->
        val defaults = Settings()
        Settings(
            serverUrl = prefs[Keys.SERVER_URL] ?: defaults.serverUrl,
            apiToken = prefs[Keys.API_TOKEN] ?: defaults.apiToken,
            styleId = prefs[Keys.STYLE_ID] ?: defaults.styleId,
            targetLang = prefs[Keys.TARGET_LANG] ?: defaults.targetLang,
            asList = prefs[Keys.AS_LIST] ?: defaults.asList,
            emoji = prefs[Keys.EMOJI] ?: defaults.emoji,
            asrMode = prefs[Keys.ASR_MODE]?.toEnum<AsrMode>() ?: defaults.asrMode,
            asrLanguage = prefs[Keys.ASR_LANGUAGE] ?: defaults.asrLanguage,
            processingMode = prefs[Keys.PROCESSING_MODE]?.toEnum<ProcessingMode>()
                ?: defaults.processingMode,
            bubbleEnabled = prefs[Keys.BUBBLE_ENABLED] ?: defaults.bubbleEnabled,
            adminToken = prefs[Keys.ADMIN_TOKEN] ?: defaults.adminToken,
        )
    }

    val options: Flow<Options> = store.data.map { prefs ->
        prefs[Keys.OPTIONS_JSON]?.let(::parseOptions) ?: Options.DEFAULT
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setServer(url: String, token: String) = store.edit { prefs ->
        prefs[Keys.SERVER_URL] = url.trim().trimEnd('/')
        prefs[Keys.API_TOKEN] = token.trim()
    }

    suspend fun setApiToken(token: String) = store.edit { it[Keys.API_TOKEN] = token.trim() }

    suspend fun setStyle(id: String) = store.edit { it[Keys.STYLE_ID] = id }

    suspend fun setTargetLang(code: String) = store.edit { it[Keys.TARGET_LANG] = code }

    suspend fun setAsList(asList: Boolean) = store.edit { it[Keys.AS_LIST] = asList }

    suspend fun setEmoji(emoji: Boolean) = store.edit { it[Keys.EMOJI] = emoji }

    suspend fun setAsrMode(mode: AsrMode) = store.edit { it[Keys.ASR_MODE] = mode.name }

    suspend fun setAsrLanguage(tag: String) = store.edit { it[Keys.ASR_LANGUAGE] = tag }

    suspend fun setProcessingMode(mode: ProcessingMode) =
        store.edit { it[Keys.PROCESSING_MODE] = mode.name }

    suspend fun setBubbleEnabled(enabled: Boolean) =
        store.edit { it[Keys.BUBBLE_ENABLED] = enabled }

    /** Пустая строка убирает раздел «Админ» из приложения. */
    suspend fun setAdminToken(token: String) = store.edit { it[Keys.ADMIN_TOKEN] = token.trim() }

    suspend fun cacheOptions(options: Options) = store.edit {
        it[Keys.OPTIONS_JSON] = serializeOptions(options)
    }
}

private inline fun <reified T : Enum<T>> String.toEnum(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()

private fun serializeOptions(options: Options): String {
    val styles = JSONArray()
    options.styles.forEach { styles.put(JSONObject().put("id", it.id).put("name", it.name)) }
    val languages = JSONArray()
    options.languages.forEach { languages.put(JSONObject().put("code", it.code).put("name", it.name)) }
    return JSONObject().put("styles", styles).put("languages", languages).toString()
}

private fun parseOptions(json: String): Options? = runCatching {
    val root = JSONObject(json)
    val styles = root.getJSONArray("styles").let { arr ->
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Style(o.getString("id"), o.getString("name"))
        }
    }
    val languages = root.getJSONArray("languages").let { arr ->
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Language(o.getString("code"), o.getString("name"))
        }
    }
    if (styles.isEmpty()) null else Options(styles, languages.ifEmpty { Options.DEFAULT.languages })
}.getOrNull()

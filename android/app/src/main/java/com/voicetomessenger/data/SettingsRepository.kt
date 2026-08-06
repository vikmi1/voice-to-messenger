package com.voicetomessenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val MIC_AT_BOTTOM = booleanPreferencesKey("mic_at_bottom")
        val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val ADMIN_TOKEN = stringPreferencesKey("admin_token")
        val LIVE_TARGET_LANG = stringPreferencesKey("live_target_lang")
        val LIVE_SPEAK = booleanPreferencesKey("live_speak")
        val OPTIONS_JSON = stringPreferencesKey("options_json")
        val GAME_BEST = intPreferencesKey("game_best")
        val GAME_2048_STATE = stringPreferencesKey("game_2048_state")
        val SCHULTE_RESULTS = stringPreferencesKey("schulte_results")
        val STROOP_BEST = intPreferencesKey("stroop_best")
    }

    /**
     * Рекорд в 2048. Держим отдельным потоком, а не полем [Settings]: игра к настройкам
     * приложения отношения не имеет и не должна пересобирать состояние главного экрана.
     */
    val gameBest: Flow<Int> = store.data.map { it[Keys.GAME_BEST] ?: 0 }

    /** Незаконченная партия 2048 в компактной строке (см. `GameState.serialize`). */
    val game2048State: Flow<String?> = store.data.map { it[Keys.GAME_2048_STATE] }

    /**
     * Результаты Шульте: размер таблицы → список времён в миллисекундах, старые впереди.
     * Хранятся JSON-строкой, разбираются в [parseSchulteResults].
     */
    val schulteResults: Flow<Map<Int, List<Long>>> = store.data.map { prefs ->
        prefs[Keys.SCHULTE_RESULTS]?.let(::parseSchulteResults) ?: emptyMap()
    }

    /** Лучший счёт в струп-тесте. Отдельный поток по той же причине, что и [gameBest]. */
    val stroopBest: Flow<Int> = store.data.map { it[Keys.STROOP_BEST] ?: 0 }

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
            micAtBottom = prefs[Keys.MIC_AT_BOTTOM] ?: defaults.micAtBottom,
            bubbleEnabled = prefs[Keys.BUBBLE_ENABLED] ?: defaults.bubbleEnabled,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: defaults.soundEnabled,
            adminToken = prefs[Keys.ADMIN_TOKEN] ?: defaults.adminToken,
            liveTargetLang = prefs[Keys.LIVE_TARGET_LANG] ?: defaults.liveTargetLang,
            liveSpeak = prefs[Keys.LIVE_SPEAK] ?: defaults.liveSpeak,
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

    suspend fun setMicAtBottom(enabled: Boolean) = store.edit { it[Keys.MIC_AT_BOTTOM] = enabled }

    suspend fun setBubbleEnabled(enabled: Boolean) =
        store.edit { it[Keys.BUBBLE_ENABLED] = enabled }

    suspend fun setSoundEnabled(enabled: Boolean) =
        store.edit { it[Keys.SOUND_ENABLED] = enabled }

    /** Пустая строка убирает раздел «Админ» из приложения. */
    suspend fun setAdminToken(token: String) = store.edit { it[Keys.ADMIN_TOKEN] = token.trim() }

    suspend fun setLiveTargetLang(code: String) = store.edit { it[Keys.LIVE_TARGET_LANG] = code }

    suspend fun setLiveSpeak(enabled: Boolean) = store.edit { it[Keys.LIVE_SPEAK] = enabled }

    suspend fun setGameBest(score: Int) = store.edit {
        if (score > (it[Keys.GAME_BEST] ?: 0)) it[Keys.GAME_BEST] = score
    }

    /** Пустая строка стирает сохранённую партию (например, при «Новой игре» без ходов). */
    suspend fun setGame2048State(serialized: String) = store.edit {
        if (serialized.isEmpty()) it.remove(Keys.GAME_2048_STATE) else it[Keys.GAME_2048_STATE] = serialized
    }

    /** Дописывает результат попытки; на размер храним не больше [SCHULTE_HISTORY] последних времён. */
    suspend fun addSchulteResult(size: Int, millis: Long) = store.edit { prefs ->
        val current = prefs[Keys.SCHULTE_RESULTS]?.let(::parseSchulteResults) ?: emptyMap()
        val updated = current.toMutableMap()
        val times = (updated[size].orEmpty() + millis).takeLast(SCHULTE_HISTORY)
        updated[size] = times
        prefs[Keys.SCHULTE_RESULTS] = serializeSchulteResults(updated)
    }

    suspend fun cacheOptions(options: Options) = store.edit {
        it[Keys.OPTIONS_JSON] = serializeOptions(options)
    }

    suspend fun setStroopBest(score: Int) = store.edit {
        if (score > (it[Keys.STROOP_BEST] ?: 0)) it[Keys.STROOP_BEST] = score
    }
}

/** Сколько последних результатов Шульте храним на каждый размер таблицы. */
const val SCHULTE_HISTORY = 20

private inline fun <reified T : Enum<T>> String.toEnum(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()

private fun serializeOptions(options: Options): String {
    val styles = JSONArray()
    options.styles.forEach { styles.put(JSONObject().put("id", it.id).put("name", it.name)) }
    val languages = JSONArray()
    options.languages.forEach { languages.put(JSONObject().put("code", it.code).put("name", it.name)) }
    return JSONObject().put("styles", styles).put("languages", languages).toString()
}

private fun serializeSchulteResults(results: Map<Int, List<Long>>): String {
    val root = JSONObject()
    results.forEach { (size, times) ->
        root.put(size.toString(), JSONArray().apply { times.forEach { put(it) } })
    }
    return root.toString()
}

private fun parseSchulteResults(json: String): Map<Int, List<Long>> = runCatching {
    val root = JSONObject(json)
    root.keys().asSequence().associate { key ->
        val arr = root.getJSONArray(key)
        key.toInt() to (0 until arr.length()).map { arr.getLong(it) }
    }
}.getOrDefault(emptyMap())

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

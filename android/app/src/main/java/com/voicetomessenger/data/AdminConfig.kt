package com.voicetomessenger.data

import org.json.JSONArray
import org.json.JSONObject

/** Тип API провайдера: определяет, каким классом бэкенд будет с ним говорить. */
enum class ProviderKind(val id: String, val title: String) {
    OPENAI("openai", "OpenAI-совместимый"),
    ANTHROPIC("anthropic", "Anthropic (Claude)"),
    ;

    companion object {
        fun from(id: String?): ProviderKind = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

data class ProviderConfig(
    /** Ключ в конфиге бэкенда: openai, mistral, anthropic… Неизменяем после создания. */
    val key: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    /** Модель для обработки текста (chat/completions). */
    val model: String,
    /**
     * Модель распознавания речи этого провайдера (`/audio/transcriptions`).
     *
     * Лежит здесь, а не одним полем на всю систему: у каждого провайдера она своя —
     * `whisper-1` у Mistral не существует, `voxtral` у OpenAI тоже. У Anthropic
     * распознавания нет вовсе, поэтому поле пустое и в панели не показывается.
     */
    val asrModel: String,
)

/**
 * Настройки живого перевода.
 *
 * Три модели вместо одной: потоковой модели «звук → перевод» у провайдеров нет,
 * поэтому распознавание, перевод и озвучка настраиваются по отдельности. Пустая
 * [ttsModel] означает «без озвучки» — перевод останется бегущим текстом.
 */
data class LiveConfig(
    val sttProvider: String,
    val sttModel: String,
    val translateProvider: String,
    val translateModel: String,
    val ttsProvider: String,
    val ttsModel: String,
    val ttsVoice: String,
    /** Настроение для провайдеров с полем `instructions`; у остальных внутри голоса. */
    val ttsMood: String,
    val sessionLimitMinutes: Int,
) {
    companion object {
        val EMPTY = LiveConfig("", "", "", "", "", "", "", "neutral", 10)
    }
}

data class StyleConfig(val id: String, val name: String, val prompt: String)

data class LanguageConfig(val code: String, val name: String)

/**
 * Конфигурация бэкенда — то же самое, что редактируется в веб-панели `/admin`.
 *
 * [rawJson] хранит исходный документ: при сохранении известные поля перезаписываются
 * поверх него, поэтому настройки, о которых приложение ещё не знает, не теряются.
 */
data class AdminConfig(
    val activeProvider: String,
    val providers: List<ProviderConfig>,
    val asrProvider: String,
    val live: LiveConfig,
    val basePrompt: String,
    val styles: List<StyleConfig>,
    val languages: List<LanguageConfig>,
    val apiToken: String,
    val adminToken: String,
    private val rawJson: String,
) {
    fun provider(key: String): ProviderConfig? = providers.firstOrNull { it.key == key }

    fun withProvider(updated: ProviderConfig): AdminConfig =
        copy(providers = providers.map { if (it.key == updated.key) updated else it })

    fun toJson(): String {
        val root = runCatching { JSONObject(rawJson) }.getOrElse { JSONObject() }

        val provs = JSONObject()
        providers.forEach { p ->
            // Неизвестные поля провайдера (если появятся) тоже сохраняем.
            val existing = root.optJSONObject("providers")?.optJSONObject(p.key) ?: JSONObject()
            existing.put("label", p.label)
            existing.put("kind", p.kind.id)
            existing.put("base_url", p.baseUrl)
            existing.put("api_key", p.apiKey)
            existing.put("model", p.model)
            existing.put("asr_model", p.asrModel)
            provs.put(p.key, existing)
        }

        val stylesArr = JSONArray()
        styles.filter { it.id.isNotBlank() }.forEach {
            stylesArr.put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt))
        }
        val langsArr = JSONArray()
        languages.filter { it.code.isNotBlank() }.forEach {
            langsArr.put(JSONObject().put("code", it.code).put("name", it.name))
        }

        return root
            .put("active_provider", activeProvider)
            .put("providers", provs)
            // Модель распознавания ушла в карточку провайдера — здесь только кто распознаёт.
            .put("asr", JSONObject().put("provider", asrProvider))
            .put("live", (root.optJSONObject("live") ?: JSONObject())
                .put("stt_provider", live.sttProvider)
                .put("stt_model", live.sttModel)
                .put("translate_provider", live.translateProvider)
                .put("translate_model", live.translateModel)
                .put("tts_provider", live.ttsProvider)
                .put("tts_model", live.ttsModel)
                .put("tts_voice", live.ttsVoice)
                .put("tts_mood", live.ttsMood)
                .put("session_limit_minutes", live.sessionLimitMinutes))
            .put("base_prompt", basePrompt)
            .put("styles", stylesArr)
            .put("languages", langsArr)
            .put("api_token", apiToken)
            .put("admin_token", adminToken)
            .toString()
    }

    companion object {
        fun parse(json: String): AdminConfig {
            val root = JSONObject(json)

            val provsObj = root.optJSONObject("providers") ?: JSONObject()
            val providers = provsObj.keys().asSequence().map { key ->
                val p = provsObj.getJSONObject(key)
                ProviderConfig(
                    key = key,
                    label = p.optString("label", key),
                    kind = ProviderKind.from(p.optString("kind")),
                    baseUrl = p.optString("base_url"),
                    apiKey = p.optString("api_key"),
                    model = p.optString("model"),
                    asrModel = p.optString("asr_model"),
                )
            }.sortedBy { it.key }.toList()

            val asr = root.optJSONObject("asr") ?: JSONObject()
            val liveObj = root.optJSONObject("live") ?: JSONObject()

            return AdminConfig(
                activeProvider = root.optString("active_provider"),
                providers = providers,
                asrProvider = asr.optString("provider"),
                live = LiveConfig(
                    sttProvider = liveObj.optString("stt_provider"),
                    sttModel = liveObj.optString("stt_model"),
                    translateProvider = liveObj.optString("translate_provider"),
                    translateModel = liveObj.optString("translate_model"),
                    ttsProvider = liveObj.optString("tts_provider"),
                    ttsModel = liveObj.optString("tts_model"),
                    // trim: голос нередко попадает в настройки копипастом с пробелом,
                    // а провайдер отвечает на такой id 404 «Voice not found».
                    ttsVoice = liveObj.optString("tts_voice").trim(),
                    ttsMood = liveObj.optString("tts_mood").ifBlank { "neutral" },
                    sessionLimitMinutes = liveObj.optInt("session_limit_minutes", 10),
                ),
                basePrompt = root.optString("base_prompt"),
                styles = root.optJSONArray("styles").mapObjects {
                    StyleConfig(it.optString("id"), it.optString("name"), it.optString("prompt"))
                },
                languages = root.optJSONArray("languages").mapObjects {
                    LanguageConfig(it.optString("code"), it.optString("name"))
                },
                apiToken = root.optString("api_token"),
                adminToken = root.optString("admin_token"),
                rawJson = json,
            )
        }

        private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> =
            if (this == null) emptyList() else (0 until length()).map { transform(getJSONObject(it)) }
    }
}

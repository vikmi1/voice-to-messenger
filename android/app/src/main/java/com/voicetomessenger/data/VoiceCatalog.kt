package com.voicetomessenger.data

import org.json.JSONObject

/**
 * Голос синтеза речи.
 *
 * [mood] пустое у провайдеров, где настроение задаётся отдельно от голоса (OpenAI);
 * [gender] пустой там, где провайдер его не публикует — гадать по имени голоса мы не
 * станем, и в панели такие голоса показываются одним списком без деления по полу.
 */
data class Voice(
    val id: String,
    val name: String,
    val mood: String,
    val gender: String,
    val languages: List<String>,
)

/** Готовое настроение для провайдеров, понимающих поле `instructions`. */
data class VoiceMood(val id: String, val title: String)

/**
 * Ответ `/admin/api/voices`: что можно выбрать у конкретного провайдера.
 *
 * [supportsInstructions] решает, показывать ли настроения отдельным рядом кнопок:
 * у Mistral они уже внутри голоса, у OpenAI — отдельное поле запроса.
 */
data class VoiceCatalog(
    val voices: List<Voice>,
    val moods: List<VoiceMood>,
    val supportsInstructions: Boolean,
) {
    val genders: List<String> get() = voices.map { it.gender }.filter { it.isNotBlank() }.distinct()

    fun names(gender: String): List<String> =
        voices.filter { gender.isBlank() || it.gender == gender }.map { it.name }.distinct()

    fun variants(gender: String, name: String): List<Voice> =
        voices.filter { (gender.isBlank() || it.gender == gender) && it.name == name }

    fun find(id: String): Voice? = voices.firstOrNull { it.id == id }

    companion object {
        val EMPTY = VoiceCatalog(emptyList(), emptyList(), false)

        fun parse(json: String): VoiceCatalog = runCatching {
            val root = JSONObject(json)
            val arr = root.optJSONArray("voices")
            val voices = (0 until (arr?.length() ?: 0)).map { i ->
                val o = arr!!.getJSONObject(i)
                val langs = o.optJSONArray("languages")
                Voice(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    mood = o.optString("mood"),
                    gender = o.optString("gender"),
                    languages = (0 until (langs?.length() ?: 0)).map { langs!!.getString(it) },
                )
            }
            val moodArr = root.optJSONArray("moods")
            val moods = (0 until (moodArr?.length() ?: 0)).map { i ->
                val o = moodArr!!.getJSONObject(i)
                VoiceMood(o.optString("id"), o.optString("title"))
            }
            VoiceCatalog(voices, moods, root.optBoolean("supports_instructions"))
        }.getOrDefault(EMPTY)
    }
}

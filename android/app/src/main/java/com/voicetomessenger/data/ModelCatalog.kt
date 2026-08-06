package com.voicetomessenger.data

import org.json.JSONArray
import org.json.JSONObject

/** Одна подобранная модель: что это, к какому классу цены относится и сколько стоит. */
data class CatalogModel(
    val id: String,
    /** Ключ класса цены: budget / balanced / premium. Человеческое название — в [ModelCatalog.tiers]. */
    val tier: String,
    /** Цена как её показывает провайдер: «$0.15 / $0.60 за 1M» или «$0.003 / мин». */
    val price: String,
    val note: String,
)

/**
 * Подобранные модели провайдеров — приходят с бэкенда (`/admin/api/catalog`).
 *
 * Держим на сервере, чтобы обновление списка и цен не требовало пересборки APK.
 * Провайдер узнаётся по base_url, а не по ключу: ключ в конфиге может быть любым.
 */
data class ModelCatalog(
    private val entries: List<Entry>,
    private val tiers: Map<String, String>,
) {
    data class Entry(
        val match: String,
        val llm: List<CatalogModel>,
        val asr: List<CatalogModel>,
        val tts: List<CatalogModel>,
    )

    /** Модели для обработки текста у провайдера с таким адресом API. */
    fun llmFor(baseUrl: String): List<CatalogModel> = entryFor(baseUrl)?.llm.orEmpty()

    /** Модели распознавания речи у провайдера с таким адресом API. */
    fun asrFor(baseUrl: String): List<CatalogModel> = entryFor(baseUrl)?.asr.orEmpty()

    /** Модели синтеза речи у провайдера с таким адресом API. */
    fun tts(baseUrl: String): List<CatalogModel> = entryFor(baseUrl)?.tts.orEmpty()

    /** Человеческое название класса цены: «бюджет», «оптимум», «премиум». */
    fun tierTitle(tier: String): String = tiers[tier] ?: tier

    private fun entryFor(baseUrl: String): Entry? {
        val url = baseUrl.lowercase()
        return entries.firstOrNull { it.match in url }
    }

    companion object {
        val EMPTY = ModelCatalog(emptyList(), emptyMap())

        fun parse(json: String): ModelCatalog {
            val root = JSONObject(json)

            val entries = root.optJSONArray("catalog").mapObjects { obj ->
                Entry(
                    match = obj.optString("match"),
                    llm = obj.optJSONArray("llm").mapObjects(::model),
                    asr = obj.optJSONArray("asr").mapObjects(::model),
                    tts = obj.optJSONArray("tts").mapObjects(::model),
                )
            }

            val tiersObj = root.optJSONObject("tiers") ?: JSONObject()
            val tiers = tiersObj.keys().asSequence().associateWith { tiersObj.getString(it) }

            return ModelCatalog(entries, tiers)
        }

        private fun model(obj: JSONObject) = CatalogModel(
            id = obj.optString("id"),
            tier = obj.optString("tier"),
            price = obj.optString("price"),
            note = obj.optString("note"),
        )

        private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> =
            if (this == null) emptyList() else (0 until length()).map { transform(getJSONObject(it)) }
    }
}

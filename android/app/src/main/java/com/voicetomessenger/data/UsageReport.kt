package com.voicetomessenger.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Сводка расхода с бэкенда (`/admin/api/usage`).
 *
 * Деньги считает сервер, по актуальному каталогу цен: в базе лежат только сырые токены,
 * поэтому правка прайса пересчитывает и историю. Приложение ничего не считает само —
 * иначе цены пришлось бы держать в APK и обновлять пересборкой.
 *
 * [usd] равен null там, где цена неизвестна (модели нет в каталоге). Это НЕ ноль:
 * в отчёте про деньги «неизвестно» не должно выглядеть как «бесплатно».
 */
data class UsageReport(
    val days: Int,
    /** Сколько долларов в одном кредите — чтобы подпись не расходилась с сервером. */
    val creditUsd: Double,
    val total: UsageTotal,
    val byModel: List<UsageByModel>,
    val byDay: List<UsageByDay>,
    val recent: List<UsageRow>,
) {
    companion object {
        val EMPTY = UsageReport(0, 0.0, UsageTotal(), emptyList(), emptyList(), emptyList())

        fun parse(json: String): UsageReport {
            val root = JSONObject(json)
            val t = root.optJSONObject("total") ?: JSONObject()
            return UsageReport(
                days = root.optInt("days"),
                creditUsd = root.optDouble("credit_usd", 0.0),
                total = UsageTotal(
                    requests = t.optInt("requests"),
                    inputTokens = t.optInt("input_tokens"),
                    outputTokens = t.optInt("output_tokens"),
                    cachedTokens = t.optInt("cached_tokens"),
                    audioSeconds = t.optDouble("audio_seconds", 0.0),
                    usd = t.optDouble("usd", 0.0),
                    credits = t.optIntOrNull("credits") ?: 0,
                    errors = t.optInt("errors"),
                    unpriced = t.optInt("unpriced"),
                ),
                byModel = root.optJSONArray("by_model").mapObjects {
                    UsageByModel(
                        key = it.optString("key"),
                        requests = it.optInt("requests"),
                        unpriced = it.optInt("unpriced"),
                        inputTokens = it.optInt("input_tokens"),
                        outputTokens = it.optInt("output_tokens"),
                        audioSeconds = it.optDouble("audio_seconds", 0.0),
                        usd = it.optDouble("usd", 0.0),
                    )
                },
                byDay = root.optJSONArray("by_day").mapObjects {
                    UsageByDay(
                        day = it.optString("day"),
                        requests = it.optInt("requests"),
                        unpriced = it.optInt("unpriced"),
                        usd = it.optDouble("usd", 0.0),
                    )
                },
                recent = root.optJSONArray("recent").mapObjects {
                    UsageRow(
                        ts = it.optString("ts"),
                        op = it.optString("op"),
                        model = it.optString("model"),
                        inputTokens = it.optInt("input_tokens"),
                        outputTokens = it.optInt("output_tokens"),
                        audioSeconds = it.optDoubleOrNull("audio_seconds"),
                        credits = it.optIntOrNull("credits"),
                        ok = it.optInt("ok", 1) == 1,
                    )
                },
            )
        }

        /** optInt превращает null в 0 — а нам нужно отличать «не посчитано» от «бесплатно». */
        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (isNull(key)) null else optInt(key)

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (isNull(key)) null else optDouble(key)

        private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> =
            if (this == null) emptyList() else (0 until length()).map { transform(getJSONObject(it)) }
    }
}

data class UsageTotal(
    val requests: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val audioSeconds: Double = 0.0,
    val usd: Double = 0.0,
    val credits: Int = 0,
    val errors: Int = 0,
    /** Сколько запросов посчитать не удалось — модели нет в каталоге цен. */
    val unpriced: Int = 0,
)

/** Общее для группировок: сумма занижена, если часть запросов осталась без цены. */
sealed interface UsageGroup {
    val requests: Int
    val unpriced: Int
    val usd: Double

    /** Все без цены — показывать сумму нельзя вовсе, она была бы нулём и врала. */
    val allUnpriced: Boolean get() = unpriced >= requests
}

data class UsageByModel(
    val key: String,
    override val requests: Int,
    override val unpriced: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val audioSeconds: Double,
    override val usd: Double,
) : UsageGroup

data class UsageByDay(
    val day: String,
    override val requests: Int,
    override val unpriced: Int,
    override val usd: Double,
) : UsageGroup

data class UsageRow(
    val ts: String,
    /** process | transcribe | test */
    val op: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val audioSeconds: Double?,
    val credits: Int?,
    val ok: Boolean,
)

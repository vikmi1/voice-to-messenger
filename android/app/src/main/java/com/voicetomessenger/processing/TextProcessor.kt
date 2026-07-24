package com.voicetomessenger.processing

import com.voicetomessenger.net.BackendClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Сырой текст → готовое сообщение.
 *
 * Результат отдаётся потоком: серверный режим стримит ответ LLM по кускам,
 * локальный — эмитит готовую строку разом.
 */
interface TextProcessor {
    fun process(raw: String, styleId: String, targetLang: String): Flow<String>
}

/** Обработка на бэкенде: системный промпт, стили, перевод. */
class ServerProcessor(private val client: BackendClient) : TextProcessor {
    override fun process(raw: String, styleId: String, targetLang: String): Flow<String> =
        client.processStream(raw, styleId, targetLang)
}

/**
 * Обработка на устройстве. Пока это чистка текста без LLM — заглушка под
 * будущую локальную модель; стиль и перевод игнорируются.
 */
class LocalProcessor : TextProcessor {
    override fun process(raw: String, styleId: String, targetLang: String): Flow<String> =
        flowOf(LocalTextCleaner.clean(raw))
}

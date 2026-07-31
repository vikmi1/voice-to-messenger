package com.voicetomessenger.processing

import com.voicetomessenger.net.BackendClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Всё, что нужно бэкенду для одной обработки текста.
 *
 * [examples] заполняются только для стиля «Мой стиль» — это сообщения самого
 * пользователя, по которым модель подстраивает тон.
 */
data class ProcessRequest(
    val raw: String,
    val styleId: String,
    val targetLang: String,
    val asList: Boolean = false,
    val emoji: Boolean = false,
    val examples: List<String> = emptyList(),
)

/**
 * Сырой текст → готовое сообщение.
 *
 * Результат отдаётся потоком: серверный режим стримит ответ LLM по кускам,
 * локальный — эмитит готовую строку разом.
 */
interface TextProcessor {
    /**
     * [onUsageId] сообщает id строки расхода на сервере, если обработка что-то стоила.
     * Локальная обработка бесплатна и колбэк не вызывает — по этому же признаку
     * из интерфейса видно, что за режим отработал.
     */
    fun process(request: ProcessRequest, onUsageId: (String) -> Unit = {}): Flow<String>
}

/** Обработка на бэкенде: системный промпт, стили, примеры, эмодзи, список, перевод. */
class ServerProcessor(private val client: BackendClient) : TextProcessor {
    override fun process(request: ProcessRequest, onUsageId: (String) -> Unit): Flow<String> =
        client.processStream(request, onUsageId)
}

/**
 * Обработка на устройстве. Пока это чистка текста без LLM — заглушка под
 * будущую локальную модель; стиль, перевод, эмодзи и список игнорируются.
 */
class LocalProcessor : TextProcessor {
    override fun process(request: ProcessRequest, onUsageId: (String) -> Unit): Flow<String> =
        flowOf(LocalTextCleaner.clean(request.raw))
}

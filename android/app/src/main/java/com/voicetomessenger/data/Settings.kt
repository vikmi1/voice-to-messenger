package com.voicetomessenger.data

/** Откуда берётся распознавание речи. */
enum class AsrMode {
    /** Системный SpeechRecognizer: быстро, бесплатно, язык выбирается вручную. */
    ON_DEVICE,

    /** Whisper на бэкенде: качественнее и определяет язык сам, но аудио уходит на сервер. */
    SERVER,
}

/** Кто превращает сырой текст в готовое сообщение. */
enum class ProcessingMode {
    /** Бэкенд с LLM: стили, перевод, полноценный рерайт. */
    SERVER,

    /** На устройстве: чистка слов-паразитов и пунктуации, без сети. */
    LOCAL,
}

data class Settings(
    val serverUrl: String = "",
    val apiToken: String = "",
    val styleId: String = "mine",
    val targetLang: String = "off",
    /** Оформить готовое сообщение списком-чек-листом вместо сплошного текста. */
    val asList: Boolean = false,
    /** ИИ сам расставляет уместные эмодзи по смыслу. */
    val emoji: Boolean = false,
    val asrMode: AsrMode = AsrMode.ON_DEVICE,
    /** BCP-47 тег для распознавания на устройстве. */
    val asrLanguage: String = "ru-RU",
    val processingMode: ProcessingMode = ProcessingMode.SERVER,
    /** Кнопка записи внизу, под опциями — чтобы дотягиваться большим пальцем одной руки. */
    val micAtBottom: Boolean = false,
    val bubbleEnabled: Boolean = false,
    /** Звуки в играх (клики, верно/неверно, победа). Синтезируются в коде — без файлов. */
    val soundEnabled: Boolean = true,
    /** Токен админ-панели. Пока пуст — раздела «Админ» в приложении нет. */
    val adminToken: String = "",
    /** На какой язык переводит экран живого перевода. Язык говорящего определяется сам. */
    val liveTargetLang: String = LiveLanguage.DEFAULT,
    /** Проговаривать перевод в наушник, а не только показывать текстом. */
    val liveSpeak: Boolean = true,
) {
    val isServerConfigured: Boolean
        get() = serverUrl.isNotBlank() && apiToken.isNotBlank()

    val isAdmin: Boolean
        get() = adminToken.isNotBlank()
}

data class Style(val id: String, val name: String)

data class Language(val code: String, val name: String)

/**
 * Стили и языки перевода. Приходят с бэкенда (`/api/options`), чтобы правки в
 * админ-панели подхватывались без пересборки приложения; до первого удачного
 * запроса используются значения по умолчанию — они совпадают с дефолтами бэкенда.
 */
data class Options(
    val styles: List<Style>,
    val languages: List<Language>,
) {
    companion object {
        val DEFAULT = Options(
            styles = listOf(
                Style("mine", "Мой стиль"),
                Style("friendly", "Дружелюбно"),
                Style("business", "Деловой"),
            ),
            languages = listOf(
                Language("off", "Без перевода"),
                Language("de", "Немецкий"),
                Language("en", "Английский"),
                Language("ru", "Русский"),
            ),
        )
    }
}

/**
 * Языки живого перевода — те, НА которые переводим.
 *
 * Список закрытый и лежит в приложении, а не приходит с сервера: он должен совпадать
 * с [LANG_NAMES] бэкенда, которому эти коды нужны для промпта перевода. Язык
 * говорящего здесь не нужен вовсе — его определяет модель.
 */
object LiveLanguage {
    const val DEFAULT = "ru"

    /** Только коды: названия берутся из ресурсов, чтобы список говорил на языке интерфейса. */
    val CODES = listOf("ru", "de", "en", "it", "zh")
}

/** Языки, доступные для распознавания на устройстве. */
val ON_DEVICE_LANGUAGES = listOf(
    Language("ru-RU", "Русский"),
    Language("de-DE", "Немецкий"),
    Language("en-US", "Английский"),
)

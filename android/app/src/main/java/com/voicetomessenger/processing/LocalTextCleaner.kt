package com.voicetomessenger.processing

/**
 * Локальная чистка расшифровки без сети и без LLM.
 *
 * Умышленно консервативна: убирает только те слова-паразиты, которые не несут
 * смысла ни в одном контексте («эм», «ähm», «um»), и приводит в порядок пробелы,
 * заглавные буквы и финальную точку. Настоящий рерайт, стили и перевод — это
 * серверный режим; здесь их нет, и приложение об этом честно предупреждает.
 */
object LocalTextCleaner {

    private val FILLERS = listOf(
        // Русский
        "это самое", "как бы", "эм", "эмм", "ммм", "мм", "ээ", "э-э",
        // Немецкий
        "ähm", "äh", "öhm", "öh",
        // Английский
        "erm", "uhm", "um", "uh",
    )

    private val fillerRegexes = FILLERS.map { filler ->
        // \b не работает с кириллицей одинаково на всех платформах — берём границы по не-буквам.
        Regex("(?<![\\p{L}])${Regex.escape(filler)}(?![\\p{L}])", RegexOption.IGNORE_CASE)
    }

    private val spaceBeforePunctuation = Regex("\\s+([,.!?;:…])")
    private val repeatedPunctuation = Regex("([,.!?;:])\\1+")
    private val danglingSeparators = Regex("(^|(?<=[.!?]\\s))\\s*[,;:]\\s*")
    private val multipleSpaces = Regex("[ \\t]{2,}")
    private val sentenceStart = Regex("(^|[.!?…]\\s+)(\\p{Ll})")

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        fillerRegexes.forEach { text = it.replace(text, " ") }

        text = multipleSpaces.replace(text, " ")
        text = spaceBeforePunctuation.replace(text, "$1")
        text = repeatedPunctuation.replace(text, "$1")
        text = danglingSeparators.replace(text, "$1")
        text = multipleSpaces.replace(text, " ").trim()
        if (text.isEmpty()) return ""

        text = sentenceStart.replace(text) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }

        if (text.last() !in ".!?…") text += "."
        return text
    }
}

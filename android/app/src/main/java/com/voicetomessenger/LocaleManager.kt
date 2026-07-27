package com.voicetomessenger

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Язык интерфейса, выбранный в приложении, независимо от языка системы.
 *
 * Хранится в `SharedPreferences` (а не в DataStore), потому что читается синхронно
 * в `Activity.attachBaseContext` — ещё до создания UI. Пустая строка = язык системы.
 */
object LocaleManager {

    private const val PREFS = "app_prefs"
    private const val KEY = "app_locale"

    /** BCP-47 тег выбранного языка или "" для системного. */
    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** Оборачивает контекст выбранной локалью. "" — оставить системную как есть. */
    fun wrap(base: Context): Context {
        val tag = getLanguage(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/**
 * Контекст приложения с выбранным языком — для всего, что живёт вне Compose.
 *
 * `attachBaseContext` оборачивает только Activity, поэтому строки, взятые из контекста
 * приложения или сервиса, шли бы по системной локали. ViewModel, конвейер записи и
 * сервис пузыря переживают экран и не пересоздаются при смене языка, так что локаль
 * перечитывается на каждый вызов: таких вызовов единицы (ошибки, снекбары, пузырь).
 */
fun Context.localizedContext(): Context {
    val app = applicationContext
    val tag = LocaleManager.getLanguage(app)
    if (tag.isEmpty()) return app
    // Кэшируем по тегу: createConfigurationContext не бесплатный, а панель пузыря
    // перерисовывается на каждое изменение уровня звука — десять раз в секунду.
    return synchronized(localizedContexts) {
        localizedContexts.getOrPut(tag) { LocaleManager.wrap(app) }
    }
}

private val localizedContexts = mutableMapOf<String, Context>()

/** Строка ресурсов на языке, выбранном в приложении. */
fun Context.localizedString(@StringRes id: Int, vararg args: Any): String {
    val localized = localizedContext()
    return if (args.isEmpty()) localized.getString(id) else localized.getString(id, *args)
}

/** Достаёт Activity из обёрнутого контекста — нужно, чтобы вызвать recreate() при смене языка. */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

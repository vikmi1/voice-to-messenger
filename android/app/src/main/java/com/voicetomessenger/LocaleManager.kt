package com.voicetomessenger

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
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

/** Достаёт Activity из обёрнутого контекста — нужно, чтобы вызвать recreate() при смене языка. */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

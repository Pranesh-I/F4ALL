package com.sai.sports.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The languages the app ships.
 *
 * Hindi, plus Tamil and Bengali as the first regional languages — together with
 * English that covers a large share of the athletes this is built for. Adding
 * one is a `values-xx/strings.xml` and an entry here; `TranslationCompletenessTest`
 * fails the build if any string is missing from any language.
 *
 * [nativeName] is written in the language itself, so a person who cannot read
 * English can still find theirs on the very first screen.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी"),
    TAMIL("ta", "தமிழ்"),
    BENGALI("bn", "বাংলা");

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * The athlete's chosen language, applied by wrapping the activity's context.
 *
 * Done by hand rather than with AppCompat's per-app locales, which only apply to
 * an `AppCompatActivity` below Android 13 — this app is Compose on a plain
 * `ComponentActivity` and supports Android 8.
 */
object LanguageStore {

    private const val PREFERENCES = "app_language"
    private const val KEY_TAG = "tag"

    fun current(context: Context): AppLanguage? =
        AppLanguage.fromTag(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_TAG, null)
        )

    @android.annotation.SuppressLint("ApplySharedPref")
    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAG, language.tag)
            // commit: the activity is recreated immediately after this call and
            // reads the value back in attachBaseContext.
            .commit()
    }

    /** A context whose resources resolve in the chosen language. */
    fun wrap(base: Context): Context {
        val language = current(base) ?: return base
        val locale = Locale.forLanguageTag(language.tag)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}

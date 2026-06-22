package app.gamenative.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import app.gamenative.PrefManager
import app.gamenative.enums.Language
import java.util.Locale

/**
 * Helper class for managing app locale/language settings.
 */
object LocaleHelper {

    /**
     * Supported languages in the app.
     * Key is the language code, value is the display name.
     * Only includes languages that have actual string resource files.
     */
    val SUPPORTED_LANGUAGES = linkedMapOf(
        "" to "System Default",
        "da" to "Dansk (Danish)",
        "de" to "Deutsch",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "it" to "Italiano",
        "ja" to "日本語 (Japanese)",
        "ko" to "한국어 (Korean)",
        "pl" to "Polski",
        "pt-BR" to "Português Brasileiro (Brazilian Portuguese)",
        "ro" to "Română (Romanian)",
        "ru" to "Русский",
        "uk" to "Українська",
        "zh-CN" to "简体中文",
        "zh-TW" to "正體中文"
    )

    /**
     * Apply the saved language preference to the context.
     * @param context The context to update
     * @param languageCode The language code to apply (empty string for system default)
     * @return Updated context with the new locale
     */
    fun applyLanguage(context: Context, languageCode: String): Context {
        if (languageCode.isEmpty()) {
            // Use system default - no need to override
            return context
        }

        val locale = getLocaleFromCode(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * Convert a language code string to a Locale object.
     * Handles language codes with region (e.g., "pt-BR", "zh-CN").
     */
    private fun getLocaleFromCode(languageCode: String): Locale {
        return when {
            languageCode.contains("-") -> {
                val parts = languageCode.split("-")
                if (parts.size == 2) {
                    Locale(parts[0], parts[1])
                } else {
                    Locale(parts[0])
                }
            }
            else -> Locale(languageCode)
        }
    }

    /**
     * Get the display name for a language code.
     * @param languageCode The language code
     * @return The display name, or the code itself if not found
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return SUPPORTED_LANGUAGES[languageCode] ?: languageCode
    }

    /**
     * Maps the app's current language to the Steam Community guide language *tag*
     * (e.g. "english", "schinese"). Steam encodes a guide's language as a
     * `requiredtags` value, and [Language]'s entry names are exactly those tags.
     *
     * Resolves from [PrefManager.appLanguage]; when blank ("System Default") it
     * derives the code from [Locale.getDefault] (using country to disambiguate
     * Chinese / Brazilian Portuguese). Returns null when there's no sensible tag,
     * in which case the Guides tab simply won't offer language filtering.
     */
    fun steamGuideLanguageTag(): String? {
        val code = PrefManager.appLanguage.ifBlank { currentSystemLanguageCode() }
        val language = when (code) {
            "da" -> Language.danish
            "de" -> Language.german
            "en" -> Language.english
            "es" -> Language.spanish
            "fr" -> Language.french
            "it" -> Language.italian
            "ja" -> Language.japanese
            "ko" -> Language.koreana
            "pl" -> Language.polish
            "pt-BR" -> Language.brazilian
            "ro" -> Language.romanian
            "ru" -> Language.russian
            "uk" -> Language.ukrainian
            "zh-CN" -> Language.schinese
            "zh-TW" -> Language.tchinese
            else -> Language.unknown
        }
        return if (language == Language.unknown) null else language.name
    }

    /**
     * Builds a [SUPPORTED_LANGUAGES]-style code (e.g. "en", "zh-CN", "pt-BR")
     * from the device locale so [steamGuideLanguageTag] can map it.
     */
    private fun currentSystemLanguageCode(): String {
        val locale = Locale.getDefault()
        val lang = locale.language.lowercase()
        val country = locale.country.uppercase()
        return when (lang) {
            // These app languages carry a region; reattach it when present.
            "zh" -> if (country == "TW" || country == "HK" || country == "MO") "zh-TW" else "zh-CN"
            "pt" -> "pt-BR"
            else -> lang
        }
    }

    /**
     * Get the list of supported language codes.
     */
    fun getSupportedLanguageCodes(): List<String> {
        return SUPPORTED_LANGUAGES.keys.toList()
    }

    /**
     * Get the list of supported language display names.
     */
    fun getSupportedLanguageNames(): List<String> {
        return SUPPORTED_LANGUAGES.values.toList()
    }
}

package com.mettyoung.deconstructchinese.storage

import com.mettyoung.deconstructchinese.config.defaultApiKeys
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.TranslationProvider
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set

object AppSettings {
    private val settings: Settings = Settings()
    private const val KEY_USE_SIMPLIFIED = "use_simplified"
    private const val KEY_PREFIX_API_KEY = "api_key_"
    private const val KEY_LANGUAGE_PAIR = "language_pair"
    private const val KEY_SELECTED_PROVIDER = "selected_provider"
    private const val KEY_PREFIX_MODEL = "model_"

    var useSimplified: Boolean
        get() = settings.getBoolean(KEY_USE_SIMPLIFIED, false)
        set(value) {
            settings[KEY_USE_SIMPLIFIED] = value
        }

    /** True once the user has picked a language pair (first-launch gate). */
    val hasSelectedLanguagePair: Boolean
        get() = settings.hasKey(KEY_LANGUAGE_PAIR)

    var languagePair: LanguagePair
        get() = LanguagePair.fromId(settings.getString(KEY_LANGUAGE_PAIR, LanguagePair.DEFAULT.id))
        set(value) {
            settings[KEY_LANGUAGE_PAIR] = value.id
        }

    /** Effective key for [provider] ("qwen", "gemini", ...): a user override if set, else the bundled build-time key. */
    fun apiKey(provider: String): String =
        settings.getString("$KEY_PREFIX_API_KEY$provider", defaultApiKeys[provider].orEmpty())

    /** Just the user-entered override for [provider], blank if none — for displaying in a settings field. */
    fun apiKeyOverride(provider: String): String =
        settings.getString("$KEY_PREFIX_API_KEY$provider", "")

    fun setApiKey(provider: String, value: String) {
        settings["$KEY_PREFIX_API_KEY$provider"] = value
    }

    /** User-selected model override for [provider], blank if none (falls back to `provider.defaultModel`). */
    fun model(provider: String): String =
        settings.getString("$KEY_PREFIX_MODEL$provider", "")

    fun setModel(provider: String, value: String) {
        settings["$KEY_PREFIX_MODEL$provider"] = value
    }

    /**
     * null = Auto: ignores any per-provider key/model override and just uses
     * the bundled default key + default model for each provider, trying Qwen
     * then Gemini. Non-null = exclusively use that one provider, with its
     * saved key/model override if set.
     */
    var selectedProvider: TranslationProvider?
        get() = TranslationProvider.fromId(settings.getString(KEY_SELECTED_PROVIDER, ""))
        set(value) {
            settings[KEY_SELECTED_PROVIDER] = value?.id.orEmpty()
        }
}

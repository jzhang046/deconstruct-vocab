package com.mettyoung.deconstructchinese.storage

import com.mettyoung.deconstructchinese.config.defaultApiKeys
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set

object AppSettings {
    private val settings: Settings = Settings()
    private const val KEY_USE_SIMPLIFIED = "use_simplified"
    private const val KEY_PREFIX_API_KEY = "api_key_"

    var useSimplified: Boolean
        get() = settings.getBoolean(KEY_USE_SIMPLIFIED, false)
        set(value) {
            settings[KEY_USE_SIMPLIFIED] = value
        }

    /** Effective key for [provider] ("qwen", "gemini", ...): a user override if set, else the bundled build-time key. */
    fun apiKey(provider: String): String =
        settings.getString("$KEY_PREFIX_API_KEY$provider", defaultApiKeys[provider].orEmpty())

    fun setApiKey(provider: String, value: String) {
        settings["$KEY_PREFIX_API_KEY$provider"] = value
    }
}

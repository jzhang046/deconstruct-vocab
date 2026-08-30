package com.mettyoung.deconstructchinese.model

/**
 * A studied language paired with English. This is the only place that knows
 * which concrete languages exist — everything below it (prompts, TTS/speech
 * locales, vocab storage) is generic and keyed off [id] or the flags here.
 */
enum class LanguagePair(
    val id: String,
    val label: String,
    val foreignName: String,
    val language: Language,
    val hasScriptVariants: Boolean,
    val hasPhoneticGuide: Boolean,
    val phoneticGuideName: String,
    val speechLocale: String,
    val ttsLocale: String
) {
    CHINESE(
        id = "zh",
        label = "Chinese",
        foreignName = "Chinese",
        language = Language.CHINESE_TRADITIONAL,
        hasScriptVariants = true,
        hasPhoneticGuide = true,
        phoneticGuideName = "pinyin with tone marks",
        speechLocale = "zh-CN",
        ttsLocale = "zh-CN"
    ),
    MALAY(
        id = "ms",
        label = "Bahasa Malaysia",
        foreignName = "Bahasa Malaysia",
        language = Language.MALAY,
        hasScriptVariants = false,
        hasPhoneticGuide = false,
        phoneticGuideName = "",
        speechLocale = "ms-MY",
        ttsLocale = "ms-MY"
    );

    companion object {
        val DEFAULT = CHINESE
        fun fromId(id: String): LanguagePair = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

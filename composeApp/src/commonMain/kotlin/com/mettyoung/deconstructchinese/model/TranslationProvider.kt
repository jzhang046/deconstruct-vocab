package com.mettyoung.deconstructchinese.model

/**
 * A configurable LLM provider. Like [LanguagePair], this is the only place
 * provider identity is meant to be named for UI/settings purposes — the
 * actual service classes are only instantiated in
 * `network/AutoSwitchingTranslationService.kt`'s `createTranslationService()`.
 */
enum class TranslationProvider(
    val id: String,
    val label: String,
    val models: List<String>,
    val defaultModel: String
) {
    QWEN(
        id = "qwen",
        label = "Qwen",
        models = listOf("qwen-turbo", "qwen-plus", "qwen-max"),
        defaultModel = "qwen-plus"
    ),
    GEMINI(
        id = "gemini",
        label = "Gemini",
        // 3.1-flash-lite has the highest free-tier RPM of the lot (30 vs 15
        // for 3.5-flash-lite) — prioritized as the default for that reason.
        models = listOf(
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash"
        ),
        defaultModel = "gemini-3.1-flash-lite"
    );

    companion object {
        fun fromId(id: String): TranslationProvider? = entries.firstOrNull { it.id == id }
    }
}

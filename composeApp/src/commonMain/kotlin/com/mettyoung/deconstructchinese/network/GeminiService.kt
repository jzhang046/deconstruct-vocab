package com.mettyoung.deconstructchinese.network

// Google's OpenAI-compatible endpoint for the Gemini API. Unlike Qwen/Doubao,
// Gemini has a genuinely permanent free tier (rate-limited, no trial expiry)
// as long as billing stays disabled on the project — see
// https://ai.google.dev/gemini-api/docs/openai for setup and current limits.
@Suppress("unused")
class GeminiService(
    apiKey: String,
    model: String = DEFAULT_MODEL,
    baseUrl: String = DEFAULT_BASE_URL
) : OpenAiCompatibleTranslator(
    apiKey = apiKey,
    baseUrl = baseUrl,
    model = model,
    providerLabel = "Gemini",
    // "none" is rejected (400 INVALID_ARGUMENT) on Gemini 3.x models — the
    // 2.5 series accepted it, but 3.x apparently doesn't let reasoning be
    // fully disabled. "minimal" is the lowest value that's actually accepted.
    reasoningEffort = "minimal"
) {
    companion object {
        // Google periodically retires model IDs outright (even its own
        // "-latest" aliases get deprecated and hot-swapped) — there's no
        // permanent name here. When this 404s, check
        // https://ai.google.dev/gemini-api/docs/models for the current GA
        // flash model and update this constant.
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    }
}

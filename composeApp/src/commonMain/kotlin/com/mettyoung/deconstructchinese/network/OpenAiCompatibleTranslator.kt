package com.mettyoung.deconstructchinese.network

import com.mettyoung.deconstructchinese.model.Language
import com.mettyoung.deconstructchinese.model.LanguagePair
import com.mettyoung.deconstructchinese.model.TranslationResult
import com.mettyoung.deconstructchinese.model.VocabularyItem
import com.mettyoung.deconstructchinese.util.ChineseScriptConverter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    val response_format: ResponseFormat? = null,
    val stream: Boolean = false,
    val thinking: Thinking? = null,
    // Gemini's OpenAI-compat layer uses this (values: none/low/medium/high)
    // to disable its default-on reasoning; unrelated to Doubao's `thinking` field.
    val reasoning_effort: String? = null
)

@Serializable
private data class Thinking(val type: String)

@Serializable
private data class StreamChunk(
    val choices: List<StreamChoice>? = null
)

@Serializable
private data class StreamChoice(
    val delta: StreamDelta? = null
)

@Serializable
private data class StreamDelta(
    val content: String? = null
)

@Serializable
private data class ResponseFormat(val type: String)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice>? = null,
    val id: String? = null
)

@Serializable
private data class ChatChoice(
    val message: ChatMessage? = null,
    val finish_reason: String? = null
)

abstract class OpenAiCompatibleTranslator(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val providerLabel: String,
    private val useJsonMode: Boolean = true,
    private val userPromptPrefix: String = "",
    // Doubao's seed models are hybrid reasoning models that stream a
    // chain-of-thought before the answer — the dominant latency cost. Adapters
    // that hit such a model set this true to request a direct (non-thinking) reply.
    private val disableThinking: Boolean = false,
    // Same idea as disableThinking but for providers (e.g. Gemini) whose
    // OpenAI-compat layer takes a `reasoning_effort` string instead.
    private val reasoningEffort: String? = null
) : TranslationService {

    private val thinkingMode: Thinking? =
        if (disableThinking) Thinking("disabled") else null

    final override suspend fun translate(
        text: String,
        languagePair: LanguagePair,
        toEnglish: Boolean,
        useSimplified: Boolean,
        includeGrammarNote: Boolean
    ): TranslationResult {
        val cacheKey = CacheKey(providerLabel, text, languagePair.id, toEnglish, useSimplified, includeGrammarNote)
        getCached(cacheKey)?.let { cached ->
            println("[TranslationService] cache hit provider=$providerLabel chars=${text.length}")
            return cached
        }

        val t0 = currentTimeMillis()
        println("[TranslationService] start provider=$providerLabel model=$model url=$baseUrl includeGrammarNote=$includeGrammarNote jsonMode=$useJsonMode chars=${text.length}")
        val systemPrompt = if (toEnglish) systemToEnglish(languagePair) else systemToForeign(languagePair, useSimplified)
        val baseUserPrompt = if (toEnglish) buildPromptToEnglish(text, languagePair, includeGrammarNote)
        else buildPromptToForeign(text, languagePair, useSimplified, includeGrammarNote)
        val userPrompt = if (userPromptPrefix.isNotEmpty()) "$userPromptPrefix\n$baseUserPrompt" else baseUserPrompt

        val requestBody = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            response_format = if (useJsonMode) ResponseFormat("json_object") else null,
            thinking = thinkingMode,
            reasoning_effort = reasoningEffort
        )

        logCurl(requestBody)

        val tSend = currentTimeMillis()
        val response: HttpResponse = sharedClient.post(baseUrl) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val tHeaders = currentTimeMillis()

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("$providerLabel API error: ${response.status} - $errorBody")
        }

        val body: ChatResponse = response.body()
        val tBody = currentTimeMillis()
        val rawText = body.choices?.firstOrNull()?.message?.content
            ?: throw Exception("Empty response from $providerLabel")

        val result = parseResponse(rawText, text, languagePair, toEnglish, useSimplified)
        putCached(cacheKey, result)
        val tDone = currentTimeMillis()
        println("[TranslationService] done provider=$providerLabel total=${tDone - t0}ms send=${tSend - t0}ms headers=${tHeaders - tSend}ms body=${tBody - tHeaders}ms parse=${tDone - tBody}ms outChars=${rawText.length}")
        return result
    }

    final override fun translateStream(
        text: String,
        languagePair: LanguagePair,
        toEnglish: Boolean,
        useSimplified: Boolean
    ): Flow<PartialTranslation> = flow {
        val t0 = currentTimeMillis()
        println("[TranslationService] stream start provider=$providerLabel model=$model chars=${text.length}")
        val systemPrompt = STREAM_SYSTEM
        val userPrompt = buildStreamPrompt(text, languagePair, toEnglish, useSimplified)

        val requestBody = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            stream = true,
            thinking = thinkingMode,
            reasoning_effort = reasoningEffort
        )

        sharedClient.preparePost(baseUrl) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw Exception("$providerLabel API error: ${response.status} - $errorBody")
            }
            val channel = response.bodyAsChannel()
            val acc = StringBuilder()
            var firstToken = true
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val delta = runCatching {
                    jsonConfig.decodeFromString<StreamChunk>(payload)
                        .choices?.firstOrNull()?.delta?.content
                }.getOrNull() ?: continue
                if (delta.isEmpty()) continue
                if (firstToken) {
                    println("[TranslationService] stream first-token=${currentTimeMillis() - t0}ms")
                    firstToken = false
                }
                acc.append(delta)
                // Output format: "<translation>|||<pinyin>". Split so the
                // translation paints while it streams, then pinyin fills in.
                val parts = acc.split(STREAM_DELIMITER, limit = 2)
                val translation = parts[0].trimEnd('|', '\n', ' ')
                val pinyin = parts.getOrNull(1)?.trim().orEmpty()
                emit(PartialTranslation(translation, pinyin))
            }
        }
        println("[TranslationService] stream done provider=$providerLabel total=${currentTimeMillis() - t0}ms")
    }

    private fun currentTimeMillis(): Long = io.ktor.util.date.getTimeMillis()

    private fun logCurl(requestBody: ChatRequest) {
        val bodyString = jsonConfig.encodeToString(requestBody)
        val curl = """
            curl "$baseUrl" \
            -H "Authorization: Bearer $apiKey" \
            -H "Content-Type: application/json" \
            -d '${bodyString.replace("'", "\\'")}'
        """.trimIndent()

        println("── DEBUG: $providerLabel API cURL ──────────────────────────────")
        println(curl)
        println("────────────────────────────────────────────────────────────────")
    }

    private fun parseResponse(
        rawText: String,
        originalText: String,
        languagePair: LanguagePair,
        toEnglish: Boolean,
        useSimplified: Boolean
    ): TranslationResult {
        val cleanJson = rawText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        @Serializable
        data class VocabDto(
            val word: String,
            val altScript: String? = null,
            val phonetic: String = "",
            val meaning: String
        )

        @Serializable
        data class TranslationDto(
            val translatedText: String,
            val phoneticText: String = "",
            val grammarNote: String = "",
            val vocabulary: List<VocabDto>
        )

        val parsed = jsonConfig.decodeFromString<TranslationDto>(cleanJson)
        val foreignLang = foreignLanguage(languagePair, useSimplified)
        // Script normalization is a deterministic character mapping — done
        // locally instead of asking the LLM to redo it in the response.
        val foreignText = if (toEnglish) {
            if (languagePair.hasScriptVariants) {
                if (useSimplified) ChineseScriptConverter.toSimplified(originalText)
                else ChineseScriptConverter.toTraditional(originalText)
            } else originalText
        } else parsed.translatedText

        return TranslationResult(
            originalText = originalText,
            translatedText = parsed.translatedText,
            foreignText = foreignText,
            phoneticText = parsed.phoneticText,
            grammarNote = parsed.grammarNote,
            vocabulary = parsed.vocabulary.map {
                VocabularyItem(it.word, it.phonetic, it.meaning, altScript = it.altScript, languagePairId = languagePair.id)
            },
            sourceLanguage = if (toEnglish) foreignLang else Language.ENGLISH,
            targetLanguage = if (toEnglish) Language.ENGLISH else foreignLang
        )
    }

    private fun foreignLanguage(languagePair: LanguagePair, useSimplified: Boolean): Language =
        if (languagePair.hasScriptVariants) {
            if (useSimplified) Language.CHINESE_SIMPLIFIED else Language.CHINESE_TRADITIONAL
        } else languagePair.language

    companion object {
        private const val SIMPLIFIED = "Simplified Chinese (简体中文)"
        private const val TRADITIONAL = "Traditional Chinese (繁體中文)"

        // Full-breakdown results are memoized so re-translating the same text
        // (retyping it, swapping direction and back, re-opening a saved word)
        // skips the network call entirely. Capped and evicted oldest-first so
        // it can't grow unbounded across a long session.
        private data class CacheKey(
            val provider: String,
            val text: String,
            val languagePairId: String,
            val toEnglish: Boolean,
            val useSimplified: Boolean,
            val includeGrammarNote: Boolean
        )

        private const val CACHE_CAPACITY = 50
        private val cacheMutex = Mutex()
        private val translationCache = LinkedHashMap<CacheKey, TranslationResult>()

        private suspend fun getCached(key: CacheKey): TranslationResult? =
            cacheMutex.withLock { translationCache[key] }

        private suspend fun putCached(key: CacheKey, value: TranslationResult) {
            cacheMutex.withLock {
                translationCache[key] = value
                val oldest = translationCache.keys.firstOrNull()
                if (translationCache.size > CACHE_CAPACITY && oldest != null) {
                    translationCache.remove(oldest)
                }
            }
        }

        // Stage 1: small output for fast first paint — translation + sentence
        // pronunciation guide, but NO per-word vocabulary breakdown.
        const val STREAM_DELIMITER = "|||"

        private const val STREAM_SYSTEM =
            "You are a translator. Follow the output format exactly. " +
                "No labels, no quotes, no explanations, no extra words."

        private fun buildStreamPrompt(
            text: String,
            languagePair: LanguagePair,
            toEnglish: Boolean,
            useSimplified: Boolean
        ): String = if (toEnglish) {
            if (languagePair.hasPhoneticGuide) {
                // pronunciation guide is of the foreign-language INPUT.
                "Translate the following ${languagePair.foreignName} to English.\n" +
                    "Output exactly: <English translation>$STREAM_DELIMITER<${languagePair.phoneticGuideName} of the ${languagePair.foreignName} input>\n\n" +
                    "${languagePair.foreignName}:\n$text"
            } else {
                "Translate the following ${languagePair.foreignName} to English.\n" +
                    "Output exactly: <English translation>. No labels, no extra text.\n\n" +
                    "${languagePair.foreignName}:\n$text"
            }
        } else {
            val displayName = foreignDisplayName(languagePair, useSimplified)
            if (languagePair.hasPhoneticGuide) {
                // pronunciation guide is of the foreign-language OUTPUT.
                "Translate the following English to $displayName.\n" +
                    "Output exactly: <$displayName translation>$STREAM_DELIMITER<${languagePair.phoneticGuideName} of that translation>\n\n" +
                    "English:\n$text"
            } else {
                "Translate the following English to $displayName.\n" +
                    "Output exactly: <$displayName translation>. No labels, no extra text.\n\n" +
                    "English:\n$text"
            }
        }

        private fun systemToEnglish(languagePair: LanguagePair): String =
            "You are a professional ${languagePair.foreignName} language teacher and translator. " +
                "Translate ${languagePair.foreignName} text into English, and provide a detailed ${languagePair.foreignName} vocabulary breakdown. " +
                "Respond ONLY with valid JSON."

        private val jsonConfig = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        // One HttpClient for the whole process: connection pool + TLS session
        // survive across popup launches and avoid a fresh handshake per call.
        private val sharedClient: HttpClient by lazy {
            HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 120_000
                }
                install(ContentNegotiation) {
                    json(jsonConfig)
                }
                install(Logging) {
                    level = LogLevel.INFO
                }
            }
        }

        private fun foreignDisplayName(languagePair: LanguagePair, useSimplified: Boolean): String =
            if (languagePair.hasScriptVariants) (if (useSimplified) SIMPLIFIED else TRADITIONAL)
            else languagePair.foreignName

        private fun phoneticFieldRule(languagePair: LanguagePair): String =
            if (languagePair.hasPhoneticGuide)
                "phoneticText and every vocabulary phonetic must be ${languagePair.phoneticGuideName}."
            else
                "phoneticText and every vocabulary phonetic must be an empty string \"\" — ${languagePair.foreignName} does not need a pronunciation guide."

        private fun phoneticValueHint(languagePair: LanguagePair, of: String): String =
            if (languagePair.hasPhoneticGuide) "${languagePair.phoneticGuideName} for $of" else "always an empty string"

        private fun vocabWordFieldsHint(languagePair: LanguagePair): String =
            if (languagePair.hasScriptVariants)
                "\"word\": \"the Traditional Chinese form of this word\",\n      \"altScript\": \"the Simplified Chinese form — omit this field only if traditional and simplified are identical\","
            else
                "\"word\": \"the ${languagePair.foreignName} form of this word\","

        private fun vocabWordRule(languagePair: LanguagePair): String =
            if (languagePair.hasScriptVariants)
                "word is ALWAYS the Traditional Chinese form regardless of the preferred script. altScript is ALWAYS the Simplified Chinese form, omitted only when the characters are identical."
            else
                "word is the ${languagePair.foreignName} form of the vocabulary entry; omit altScript entirely."

        private fun systemToForeign(languagePair: LanguagePair, useSimplified: Boolean): String {
            val displayName = foreignDisplayName(languagePair, useSimplified)
            return "You are a professional translator and language teacher specializing in $displayName. " +
                "Translate English into $displayName and provide a vocabulary breakdown. Respond ONLY with valid JSON."
        }

        private fun buildPromptToForeign(
            text: String,
            languagePair: LanguagePair,
            useSimplified: Boolean,
            includeGrammarNote: Boolean
        ): String {
            val displayName = foreignDisplayName(languagePair, useSimplified)
            val scriptRule = if (languagePair.hasScriptVariants) {
                if (useSimplified)
                    "- translatedText must use Simplified Chinese characters (简体中文), never Traditional.\n"
                else
                    "- translatedText must use Traditional Chinese characters (繁體中文), never Simplified.\n"
            } else ""
            val grammarField = if (includeGrammarNote)
                "\"grammarNote\": \"one sentence in English describing the $displayName sentence structure and grammar used\",\n  "
            else ""
            val grammarRule = if (includeGrammarNote)
                "- grammarNote must be in English, describing the grammar of the $displayName output.\n"
            else ""
            return """
Translate the following English text into $displayName.

Input: "$text"

Return this exact JSON:
{
  "translatedText": "the full translation in $displayName",
  "phoneticText": "${phoneticValueHint(languagePair, "the entire translatedText")}",
  $grammarField"vocabulary": [
    {
      ${vocabWordFieldsHint(languagePair)}
      "phonetic": "${phoneticValueHint(languagePair, "this word")}",
      "meaning": "English meaning of this word"
    }
  ]
}

Rules:
$scriptRule- ${phoneticFieldRule(languagePair)}
$grammarRule- vocabulary must segment translatedText into natural words or short phrases a learner would look up individually. Do not split compound words, and do not skip any.
- ${vocabWordRule(languagePair)}
- Return ONLY the JSON, nothing else.
            """.trimIndent()
        }

        private fun buildPromptToEnglish(
            text: String,
            languagePair: LanguagePair,
            includeGrammarNote: Boolean
        ): String {
            val grammarField = if (includeGrammarNote)
                "\"grammarNote\": \"one sentence in English describing the ${languagePair.foreignName} sentence structure and grammar\",\n  "
            else ""
            val grammarRule = if (includeGrammarNote)
                "- grammarNote must be in English, describing the grammar of the ${languagePair.foreignName} input.\n"
            else ""
            val inputCaveat = if (languagePair.hasScriptVariants)
                " The input may be Traditional Chinese, Simplified Chinese, or a mix."
            else ""
            return """
Translate the following ${languagePair.foreignName} text into English.$inputCaveat

Input: "$text"

Return this exact JSON:
{
  "translatedText": "the full translation in English",
  "phoneticText": "${phoneticValueHint(languagePair, "the input ${languagePair.foreignName} text")}",
  $grammarField"vocabulary": [
    {
      ${vocabWordFieldsHint(languagePair)}
      "phonetic": "${phoneticValueHint(languagePair, "this word")}",
      "meaning": "English meaning of this word"
    }
  ]
}

Rules:
- phoneticText is the pronunciation of the input ${languagePair.foreignName} text, not the English translation.
$grammarRule- vocabulary must segment the input into natural words or short phrases a learner would look up individually. Do not split compound words, and do not skip any.
- ${vocabWordRule(languagePair)}
- Return ONLY the JSON, nothing else.
            """.trimIndent()
        }
    }
}

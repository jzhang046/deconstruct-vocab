// Port of OpenAiCompatibleTranslator.kt + QwenService.kt + GeminiService.kt +
// AutoSwitchingTranslationService.kt. The backend now holds both provider API
// keys (Workers secrets) — this is the only place they're used.
//
// Deliberate simplifications vs. the Kotlin client version:
//  - No "sticky last-good provider" retry ordering: each Worker request is
//    independent and (unlike a long-lived app process) has no meaningful
//    place to remember cross-request state, so we always try Qwen then Gemini
//    in fixed order.
//  - No response memoization/LRU cache: could be added later via KV if
//    repeat-translation volume justifies it.

import type { LanguagePair } from "./languagePair";
import type { LanguageCode, TranslationResult, PartialTranslation } from "../types";
import { toSimplified, toTraditional } from "./scriptConverter";
import {
  STREAM_DELIMITER,
  STREAM_SYSTEM,
  buildPromptToEnglish,
  buildPromptToForeign,
  buildStreamPrompt,
  systemToEnglish,
  systemToForeign,
} from "./promptBuilder";

export interface ProviderConfig {
  label: string;
  apiKey: string;
  baseUrl: string;
  model: string;
  disableThinking?: boolean;
  reasoningEffort?: string;
}

export function qwenConfig(apiKey: string, model = "qwen-plus"): ProviderConfig {
  return {
    label: "Qwen",
    apiKey,
    model,
    baseUrl: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
  };
}

export function geminiConfig(apiKey: string, model = "gemini-3.1-flash-lite"): ProviderConfig {
  return {
    label: "Gemini",
    apiKey,
    model,
    baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
    // "none" 400s on Gemini 3.x — "minimal" is the lowest value it accepts.
    reasoningEffort: "minimal",
  };
}

interface ChatMessage {
  role: "system" | "user";
  content: string;
}

function buildRequestBody(
  config: ProviderConfig,
  messages: ChatMessage[],
  opts: { stream?: boolean; jsonMode?: boolean },
) {
  return {
    model: config.model,
    messages,
    temperature: 0,
    response_format: opts.jsonMode ? { type: "json_object" } : undefined,
    stream: opts.stream ?? false,
    thinking: config.disableThinking ? { type: "disabled" } : undefined,
    reasoning_effort: config.reasoningEffort,
  };
}

async function postChat(
  config: ProviderConfig,
  messages: ChatMessage[],
  opts: { stream?: boolean; jsonMode?: boolean },
): Promise<Response> {
  const response = await fetch(config.baseUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${config.apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(buildRequestBody(config, messages, opts)),
  });
  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(`${config.label} API error: ${response.status} - ${errorBody}`);
  }
  return response;
}

function foreignLanguage(languagePair: LanguagePair, useSimplified: boolean): LanguageCode {
  if (languagePair.hasScriptVariants) return useSimplified ? "zh-CN" : "zh-TW";
  return languagePair.language;
}

function stripJsonFences(raw: string): string {
  return raw.replace(/^```json/, "").replace(/^```/, "").replace(/```$/, "").trim();
}

interface VocabDto {
  word: string;
  altScript?: string;
  phonetic?: string;
  meaning: string;
}

interface TranslationDto {
  translatedText: string;
  phoneticText?: string;
  grammarNote?: string;
  correction?: string;
  vocabulary: VocabDto[];
}

export async function translateWithProvider(
  config: ProviderConfig,
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
  includeGrammarNote: boolean,
  checkGrammar = false,
): Promise<TranslationResult> {
  const systemPrompt = toEnglish ? systemToEnglish(languagePair) : systemToForeign(languagePair, useSimplified);
  const userPrompt = toEnglish
    ? buildPromptToEnglish(text, languagePair, includeGrammarNote, checkGrammar)
    : buildPromptToForeign(text, languagePair, useSimplified, includeGrammarNote);

  const response = await postChat(
    config,
    [
      { role: "system", content: systemPrompt },
      { role: "user", content: userPrompt },
    ],
    { jsonMode: true },
  );

  const body = (await response.json()) as {
    choices?: { message?: { content?: string } }[];
  };
  const rawText = body.choices?.[0]?.message?.content;
  if (!rawText) throw new Error(`Empty response from ${config.label}`);

  const parsed = JSON.parse(stripJsonFences(rawText)) as TranslationDto;
  const foreignLang = foreignLanguage(languagePair, useSimplified);

  const foreignText = toEnglish
    ? languagePair.hasScriptVariants
      ? useSimplified
        ? toSimplified(text)
        : toTraditional(text)
      : text
    : parsed.translatedText;

  return {
    originalText: text,
    translatedText: parsed.translatedText,
    foreignText,
    phoneticText: parsed.phoneticText ?? "",
    grammarNote: parsed.grammarNote ?? "",
    correction: parsed.correction ?? "",
    vocabulary: parsed.vocabulary.map((v) => ({
      word: v.word,
      phonetic: v.phonetic ?? "",
      meaning: v.meaning,
      frequency: 0,
      altScript: v.altScript ?? null,
      languagePairId: languagePair.id,
    })),
    sourceLanguage: toEnglish ? foreignLang : "en",
    targetLanguage: toEnglish ? "en" : foreignLang,
  };
}

export async function* translateStreamWithProvider(
  config: ProviderConfig,
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
): AsyncGenerator<PartialTranslation> {
  const userPrompt = buildStreamPrompt(text, languagePair, toEnglish, useSimplified);
  const response = await postChat(
    config,
    [
      { role: "system", content: STREAM_SYSTEM },
      { role: "user", content: userPrompt },
    ],
    { stream: true },
  );

  const reader = response.body?.getReader();
  if (!reader) throw new Error(`${config.label} returned no stream body`);

  const decoder = new TextDecoder();
  let buffer = "";
  let acc = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        if (!line.startsWith("data:")) continue;
        const payload = line.slice(5).trim();
        if (!payload || payload === "[DONE]") continue;

        let delta: string | undefined;
        try {
          const chunk = JSON.parse(payload) as { choices?: { delta?: { content?: string } }[] };
          delta = chunk.choices?.[0]?.delta?.content ?? undefined;
        } catch {
          continue;
        }
        if (!delta) continue;

        acc += delta;
        const splitIndex = acc.indexOf(STREAM_DELIMITER);
        const translation =
          splitIndex >= 0 ? acc.slice(0, splitIndex) : acc.replace(/[|\n ]+$/, "");
        const pinyin = splitIndex >= 0 ? acc.slice(splitIndex + STREAM_DELIMITER.length).trim() : "";
        yield { translation, pinyin };
      }
    }
  } finally {
    reader.releaseLock();
  }
}

// --- Auto-switching across providers (Qwen -> Gemini), skipping blank keys ---

export async function autoSwitchTranslate(
  providers: ProviderConfig[],
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
  includeGrammarNote: boolean,
  checkGrammar = false,
): Promise<TranslationResult> {
  const configured = providers.filter((p) => p.apiKey.trim().length > 0);
  let lastError: unknown;
  for (const config of configured) {
    try {
      return await translateWithProvider(
        config,
        text,
        languagePair,
        toEnglish,
        useSimplified,
        includeGrammarNote,
        checkGrammar,
      );
    } catch (err) {
      lastError = err;
      console.warn(`[AutoSwitch] ${config.label} failed, trying next:`, err);
    }
  }
  throw lastError instanceof Error
    ? lastError
    : new Error("No translation provider is configured. Add a Qwen or Gemini API key.");
}

export async function* autoSwitchTranslateStream(
  providers: ProviderConfig[],
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
): AsyncGenerator<PartialTranslation> {
  const configured = providers.filter((p) => p.apiKey.trim().length > 0);
  let lastError: unknown;
  for (const config of configured) {
    try {
      yield* translateStreamWithProvider(config, text, languagePair, toEnglish, useSimplified);
      return;
    } catch (err) {
      lastError = err;
      console.warn(`[AutoSwitch] ${config.label} stream failed, trying next:`, err);
    }
  }
  throw lastError instanceof Error
    ? lastError
    : new Error("No translation provider is configured. Add a Qwen or Gemini API key.");
}

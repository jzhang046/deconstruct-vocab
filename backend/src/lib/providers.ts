// Port of OpenAiCompatibleTranslator.kt + QwenService.kt + GeminiService.kt +
// AutoSwitchingTranslationService.kt. The backend holds the provider API key
// (a Workers secret) — this is the only place it's used.
//
// Deliberate simplifications vs. the Kotlin client version:
//  - No "sticky last-good provider" retry ordering: each Worker request is
//    independent and (unlike a long-lived app process) has no meaningful
//    place to remember cross-request state.
//  - No response memoization/LRU cache: could be added later via KV if
//    repeat-translation volume justifies it.
//
// Qwen is temporarily removed from the active chain (see qwenConfig, still
// defined below but unused) in favor of a single-provider, two-model setup:
// Gemini's primary model (fast/cheap) falls back to a secondary model only on
// a retryable failure (429, 503, or a request timeout) — see
// GEMINI_PRIMARY_MODEL/GEMINI_FALLBACK_MODEL and autoSwitchTranslate below.

import type { LanguagePair } from "./languagePair";
import type { LanguageCode, TranslationResult, TranslateStreamEvent } from "../types";
import { toSimplified, toTraditional } from "./scriptConverter";
import { buildPromptToEnglish, buildPromptToForeign, systemToEnglish, systemToForeign } from "./promptBuilder";

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

export const GEMINI_PRIMARY_MODEL = "gemini-3.5-flash-lite";
export const GEMINI_FALLBACK_MODEL = "gemini-3.1-flash-lite";

// Shown to the user when the primary model is rate-limited, returns a 503, or
// times out and we're retrying with the fallback model. Deliberately generic —
// never names a model.
export const RETRY_NOTICE = "The translation service is busy — retrying, this may take a few extra seconds.";

// Gemini rejects requests whose apparent origin (the calling Cloudflare PoP,
// not anything we control) is outside its supported regions, with a 400 body
// containing this phrase. Not retryable — every configured provider shares
// the same Worker network location, so the fallback model would fail
// identically. There's nothing the user can do about it either, so we swap
// in a plain "come back later" message instead of surfacing the raw
// Google/Cloudflare error text.
const LOCATION_UNSUPPORTED_MARKER = "User location is not supported";
export const LOCATION_UNSUPPORTED_MESSAGE =
  "AI translation isn't available right now. Please try again in a little while.";

// Streaming: fetch() resolves once the first SSE bytes arrive, so this is a
// true time-to-first-byte check — flash-lite models normally start streaming
// in 1-3s.
const STREAM_TIMEOUT_MS = 12_000;
// Non-streaming (jsonMode without stream): most chat-completion APIs don't
// emit response headers until the full completion is generated, so fetch()
// resolving here is close to an end-to-end generation timeout, not just
// connect time. Only used by the standalone (non-streaming) POST
// /api/translate path — the interactive UI uses the streaming+jsonMode
// combination below, timed via STREAM_TIMEOUT_MS instead.
const JSON_TIMEOUT_MS = 20_000;

// Thrown by postChat for failures worth retrying with the fallback model
// (rate limit, transient server error, or the request timing out). Any other
// error (bad request, malformed response, etc.) is not retryable — a
// different model won't fix it.
class RetryableProviderError extends Error {}

function isRetryable(err: unknown): boolean {
  return err instanceof RetryableProviderError;
}

export function geminiConfig(apiKey: string, model = GEMINI_PRIMARY_MODEL): ProviderConfig {
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
  // Timeout only covers the connect + response-headers phase (i.e. it's
  // cleared as soon as fetch() resolves), so a legitimately slow-but-flowing
  // stream is never aborted mid-read — only a provider that never starts
  // responding is treated as retryable.
  const controller = new AbortController();
  const timeoutMs = opts.stream ? STREAM_TIMEOUT_MS : JSON_TIMEOUT_MS;
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response: Response;
  try {
    response = await fetch(config.baseUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(buildRequestBody(config, messages, opts)),
      signal: controller.signal,
    });
  } catch (err) {
    if (err instanceof Error && err.name === "AbortError") {
      throw new RetryableProviderError(`${config.label} (${config.model}) request timed out`);
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) {
    const errorBody = await response.text();
    if (errorBody.includes(LOCATION_UNSUPPORTED_MARKER)) {
      throw new Error(LOCATION_UNSUPPORTED_MESSAGE);
    }
    const message = `${config.label} (${config.model}) API error: ${response.status} - ${errorBody}`;
    if (response.status === 429 || response.status === 503) {
      throw new RetryableProviderError(message);
    }
    throw new Error(message);
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

// Shapes the model's raw DTO into the API's TranslationResult — shared by the
// non-streaming and streaming paths so foreignText/script-normalization logic
// lives in exactly one place.
function shapeResult(
  parsed: TranslationDto,
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
): TranslationResult {
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
  return shapeResult(parsed, text, languagePair, toEnglish, useSimplified);
}

// Single call, streamed: the model still produces the full accurate
// translation+vocabulary JSON (same prompt as translateWithProvider — no
// quality tradeoff), but the raw text is relayed to the caller chunk by chunk
// as `{ delta }` events for optimistic client-side rendering, then a final
// `{ result }` event carries the authoritative shaped TranslationResult once
// the complete response has been parsed. Replaces what used to be two
// separate requests (a cheap delimiter-formatted preview call, then this full
// call) — halves the input-token cost per translation and removes the risk of
// the preview and the final translation disagreeing.
export async function* translateStreamJsonWithProvider(
  config: ProviderConfig,
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
  includeGrammarNote: boolean,
  checkGrammar = false,
): AsyncGenerator<{ delta: string } | { result: TranslationResult }> {
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
    { stream: true, jsonMode: true },
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
        yield { delta };
      }
    }
  } finally {
    reader.releaseLock();
  }

  if (!acc) throw new Error(`Empty response from ${config.label}`);
  const parsed = JSON.parse(stripJsonFences(acc)) as TranslationDto;
  yield { result: shapeResult(parsed, text, languagePair, toEnglish, useSimplified) };
}

// --- Auto-switching across model configs (primary -> fallback), skipping
// blank keys. Only a retryable failure (429/503/timeout — see postChat)
// advances to the next config; any other error is surfaced immediately since
// swapping models wouldn't fix it. ---

const NO_PROVIDER_ERROR = "No translation provider is configured. Add a Gemini API key.";

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
  if (configured.length === 0) throw new Error(NO_PROVIDER_ERROR);

  for (let i = 0; i < configured.length; i++) {
    const config = configured[i];
    try {
      const result = await translateWithProvider(
        config,
        text,
        languagePair,
        toEnglish,
        useSimplified,
        includeGrammarNote,
        checkGrammar,
      );
      return i > 0 ? { ...result, notice: RETRY_NOTICE } : result;
    } catch (err) {
      const canRetry = isRetryable(err) && i < configured.length - 1;
      console.warn(`[AutoSwitch] ${config.label} failed${canRetry ? ", retrying with fallback" : ""}:`, err);
      if (!canRetry) throw err instanceof Error ? err : new Error("Translation failed");
    }
  }
  throw new Error("Translation failed");
}

export async function* autoSwitchTranslateStream(
  providers: ProviderConfig[],
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
  includeGrammarNote: boolean,
  checkGrammar = false,
): AsyncGenerator<TranslateStreamEvent> {
  const configured = providers.filter((p) => p.apiKey.trim().length > 0);
  if (configured.length === 0) throw new Error(NO_PROVIDER_ERROR);

  for (let i = 0; i < configured.length; i++) {
    const config = configured[i];
    let yieldedAny = false;
    try {
      for await (const event of translateStreamJsonWithProvider(
        config,
        text,
        languagePair,
        toEnglish,
        useSimplified,
        includeGrammarNote,
        checkGrammar,
      )) {
        yieldedAny = true;
        yield event;
      }
      return;
    } catch (err) {
      // Only safe to restart with the fallback model if nothing has streamed
      // to the client yet — once partial text is out, switching models mid-
      // stream would visibly overwrite it.
      const canRetry = isRetryable(err) && !yieldedAny && i < configured.length - 1;
      console.warn(`[AutoSwitch] ${config.label} stream failed${canRetry ? ", retrying with fallback" : ""}:`, err);
      if (!canRetry) throw err instanceof Error ? err : new Error("Translation failed");
      yield { notice: RETRY_NOTICE };
    }
  }
}

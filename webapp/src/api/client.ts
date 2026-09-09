import type { PartialTranslation, TranslationResult, User, VocabularyItem } from "../lib/types";

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { credentials: "include", ...init });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(body.error ?? `Request failed: ${res.status}`, res.status);
  }
  return res.json() as Promise<T>;
}

export function loginUrl(provider: "google" = "google"): string {
  return `/api/auth/login/${provider}`;
}

export async function fetchMe(): Promise<User | null> {
  try {
    return await request<User>("/api/auth/me");
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) return null;
    throw err;
  }
}

export async function logout(): Promise<void> {
  await request("/api/auth/logout", { method: "POST" });
}

export interface TranslateParams {
  text: string;
  languagePairId: string;
  toEnglish: boolean;
  useSimplified: boolean;
  includeGrammarNote?: boolean;
  checkGrammar?: boolean;
}

export function translate(params: TranslateParams): Promise<TranslationResult> {
  return request<TranslationResult>("/api/translate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ includeGrammarNote: true, ...params }),
  });
}

// Reads the SSE stream manually via fetch()+ReadableStream instead of
// EventSource — EventSource auto-reconnects when the server closes the
// stream (which it always does once translation finishes), which would
// silently re-trigger the translate call.
export async function* streamTranslate(
  params: Omit<TranslateParams, "includeGrammarNote">,
  signal?: AbortSignal,
): AsyncGenerator<{ type: "partial"; data: PartialTranslation } | { type: "error"; message: string }> {
  const url = new URL("/api/translate/stream", location.origin);
  url.searchParams.set("text", params.text);
  url.searchParams.set("languagePairId", params.languagePairId);
  url.searchParams.set("toEnglish", String(params.toEnglish));
  url.searchParams.set("useSimplified", String(params.useSimplified));

  const res = await fetch(url, { credentials: "include", signal });
  if (!res.ok || !res.body) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(body.error ?? `Stream request failed: ${res.status}`, res.status);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let pendingEvent = "message";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        if (line.startsWith("event:")) {
          pendingEvent = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          const payload = line.slice(5).trim();
          if (!payload) continue;
          const parsed = JSON.parse(payload);
          if (pendingEvent === "error") {
            yield { type: "error", message: parsed.error ?? "Translation failed" };
          } else {
            yield { type: "partial", data: parsed as PartialTranslation };
          }
          pendingEvent = "message";
        } else if (line === "") {
          pendingEvent = "message";
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export function listVocab(): Promise<VocabularyItem[]> {
  return request<VocabularyItem[]>("/api/vocab");
}

export function saveVocab(item: {
  word: string;
  phonetic: string;
  meaning: string;
  altScript?: string | null;
  languagePairId: string;
}): Promise<VocabularyItem> {
  return request<VocabularyItem>("/api/vocab", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(item),
  });
}

export function deleteVocab(id: string): Promise<void> {
  return request<{ ok: true }>(`/api/vocab/${id}`, { method: "DELETE" }).then(() => undefined);
}

// Mirrors backend/src/types.ts — the API contract. Keep in sync if the
// backend's request/response shape changes.

export type LanguageCode =
  | "en"
  | "zh-TW"
  | "zh-CN"
  | "ms-MY"
  | "ja"
  | "ko"
  | "fr"
  | "es"
  | "de";

export interface VocabularyItem {
  id: string;
  word: string;
  phonetic: string;
  meaning: string;
  frequency: number;
  altScript?: string | null;
  languagePairId: string;
}

export interface TranslationResult {
  originalText: string;
  translatedText: string;
  foreignText: string;
  phoneticText: string;
  vocabulary: Omit<VocabularyItem, "id">[];
  grammarNote: string;
  correction: string;
  sourceLanguage: LanguageCode;
  targetLanguage: LanguageCode;
  // Set only when the backend had to retry with a fallback model — a
  // generic status message, never names a model.
  notice?: string;
}

// Events read from the /api/translate/stream SSE response — see the
// identical type in backend/src/types.ts.
export type TranslateStreamEvent = { delta: string } | { result: TranslationResult } | { notice: string };

export interface User {
  id: string;
  email: string;
  name: string;
  avatarUrl: string | null;
}

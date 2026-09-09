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
}

export interface PartialTranslation {
  translation: string;
  pinyin: string;
}

export interface User {
  id: string;
  email: string;
  name: string;
  avatarUrl: string | null;
}

// Mirrors composeApp/src/commonMain/kotlin/.../model/TranslationResult.kt and LanguagePair.kt.
// This is the API contract the webapp frontend consumes — keep in sync with those Kotlin
// files if the app's prompt/response shape changes.

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
  vocabulary: VocabularyItem[];
  grammarNote: string;
  // Only populated when checkGrammar was requested and toEnglish is true (the
  // user typed the foreign-language sentence themselves); "" means no issues found.
  correction: string;
  sourceLanguage: LanguageCode;
  targetLanguage: LanguageCode;
  // Set only when the primary model failed with a retryable error (rate
  // limit, 503, or timeout) and this result came from the fallback model.
  // Never names a model — see RETRY_NOTICE in lib/providers.ts.
  notice?: string;
}

export interface PartialTranslation {
  translation: string;
  pinyin: string;
  notice?: string;
}

export interface TranslateRequestBody {
  text: string;
  languagePairId: string;
  toEnglish: boolean;
  useSimplified: boolean;
  includeGrammarNote: boolean;
  checkGrammar?: boolean;
}

// Port of composeApp/.../model/LanguagePair.kt — the only place a studied
// language is named. Everything else (prompt builder, providers, vocab routes)
// branches only on these flags, keyed by `id`.

import type { LanguageCode } from "../types";

export interface LanguagePair {
  id: string;
  label: string;
  foreignName: string;
  language: LanguageCode;
  hasScriptVariants: boolean;
  hasPhoneticGuide: boolean;
  phoneticGuideName: string;
}

export const LANGUAGE_PAIRS: Record<string, LanguagePair> = {
  zh: {
    id: "zh",
    label: "Chinese",
    foreignName: "Chinese",
    language: "zh-TW",
    hasScriptVariants: true,
    hasPhoneticGuide: true,
    phoneticGuideName: "pinyin with tone marks",
  },
  ms: {
    id: "ms",
    label: "Bahasa Malaysia",
    foreignName: "Bahasa Malaysia",
    language: "ms-MY",
    hasScriptVariants: false,
    hasPhoneticGuide: false,
    phoneticGuideName: "",
  },
};

export const DEFAULT_LANGUAGE_PAIR = LANGUAGE_PAIRS.zh;

export function languagePairFromId(id: string): LanguagePair {
  return LANGUAGE_PAIRS[id] ?? DEFAULT_LANGUAGE_PAIR;
}

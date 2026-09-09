// Mirrors backend/src/lib/languagePair.ts (itself a port of the Kotlin
// LanguagePair enum) — only the flags the UI needs to render conditionally.

import type { LanguageCode } from "./types";

export interface LanguagePairMeta {
  id: string;
  label: string;
  language: LanguageCode;
  hasScriptVariants: boolean;
  hasPhoneticGuide: boolean;
  speechLocale: string;
  ttsLocale: string;
}

export const LANGUAGE_PAIRS: LanguagePairMeta[] = [
  {
    id: "zh",
    label: "Chinese",
    language: "zh-TW",
    hasScriptVariants: true,
    hasPhoneticGuide: true,
    speechLocale: "zh-CN",
    ttsLocale: "zh-CN",
  },
  {
    id: "ms",
    label: "Bahasa Malaysia",
    language: "ms-MY",
    hasScriptVariants: false,
    hasPhoneticGuide: false,
    speechLocale: "ms-MY",
    ttsLocale: "ms-MY",
  },
];

export const DEFAULT_LANGUAGE_PAIR = LANGUAGE_PAIRS[0];

export function languagePairFromId(id: string): LanguagePairMeta {
  return LANGUAGE_PAIRS.find((p) => p.id === id) ?? DEFAULT_LANGUAGE_PAIR;
}

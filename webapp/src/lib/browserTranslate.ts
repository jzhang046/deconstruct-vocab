// Chrome's on-device Translator API (self.Translator, backed by Gemini
// Nano) — not a web standard, Chrome-only, feature-detected. Used purely as
// an instant, free, client-side preview shown while the accurate backend
// translation streams in (see useTranslate.ts): it has no notion of a
// pronunciation guide or vocabulary breakdown, so it never replaces the
// backend result, only fills the gap before the first delta arrives.
//
// Deliberately does not trigger a model download: availability() is checked
// first and the preview is skipped unless the language pack is already
// installed, so this never surprises the user with unsolicited bandwidth use.

import { languagePairFromId } from "./languagePair";
import type { TranslateParams } from "../api/client";

interface TranslatorInstance {
  translate(text: string): Promise<string>;
}

interface TranslatorStatic {
  availability(opts: { sourceLanguage: string; targetLanguage: string }): Promise<string>;
  create(opts: { sourceLanguage: string; targetLanguage: string }): Promise<TranslatorInstance>;
}

declare global {
  interface Window {
    Translator?: TranslatorStatic;
  }
}

// BCP-47 codes the API is known to support; anything else (e.g. Bahasa
// Malaysia, not offered by Chrome's translator) skips the preview entirely
// rather than guessing.
function toBcp47(code: string): string | null {
  if (code === "zh-CN") return "zh-Hans";
  if (code === "zh-TW") return "zh-Hant";
  if (code === "en") return "en";
  return null;
}

export async function browserTranslatePreview(params: TranslateParams): Promise<string | null> {
  const Translator = window.Translator;
  if (!Translator) return null;

  const pair = languagePairFromId(params.languagePairId);
  const foreign = pair.hasScriptVariants ? (params.useSimplified ? "zh-CN" : "zh-TW") : pair.language;
  const sourceLanguage = toBcp47(params.toEnglish ? foreign : "en");
  const targetLanguage = toBcp47(params.toEnglish ? "en" : foreign);
  if (!sourceLanguage || !targetLanguage) return null;

  try {
    const availability = await Translator.availability({ sourceLanguage, targetLanguage });
    if (availability !== "available") return null;
    const translator = await Translator.create({ sourceLanguage, targetLanguage });
    return await translator.translate(params.text);
  } catch {
    return null;
  }
}

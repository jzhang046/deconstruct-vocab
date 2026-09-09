// Port of the prompt-building logic in
// composeApp/.../network/OpenAiCompatibleTranslator.kt (companion object).
// Keep these strings identical to the Kotlin source so client and server
// produce the same model behavior.

import type { LanguagePair } from "./languagePair";

const SIMPLIFIED = "Simplified Chinese (简体中文)";
const TRADITIONAL = "Traditional Chinese (繁體中文)";

// Stage 1 stream output format: "<translation>|||<pinyin>"
export const STREAM_DELIMITER = "|||";

export const STREAM_SYSTEM =
  "You are a translator. Follow the output format exactly. " +
  "No labels, no quotes, no explanations, no extra words.";

function foreignDisplayName(languagePair: LanguagePair, useSimplified: boolean): string {
  if (languagePair.hasScriptVariants) return useSimplified ? SIMPLIFIED : TRADITIONAL;
  return languagePair.foreignName;
}

function phoneticFieldRule(languagePair: LanguagePair): string {
  return languagePair.hasPhoneticGuide
    ? `phoneticText and every vocabulary phonetic must be ${languagePair.phoneticGuideName}.`
    : `phoneticText and every vocabulary phonetic must be an empty string "" — ${languagePair.foreignName} does not need a pronunciation guide.`;
}

function phoneticValueHint(languagePair: LanguagePair, of: string): string {
  return languagePair.hasPhoneticGuide ? `${languagePair.phoneticGuideName} for ${of}` : "always an empty string";
}

function vocabWordFieldsHint(languagePair: LanguagePair): string {
  return languagePair.hasScriptVariants
    ? `"word": "the Traditional Chinese form of this word",\n      "altScript": "the Simplified Chinese form — omit this field only if traditional and simplified are identical",`
    : `"word": "the ${languagePair.foreignName} form of this word",`;
}

function vocabWordRule(languagePair: LanguagePair): string {
  return languagePair.hasScriptVariants
    ? "word is ALWAYS the Traditional Chinese form regardless of the preferred script. altScript is ALWAYS the Simplified Chinese form, omitted only when the characters are identical."
    : `word is the ${languagePair.foreignName} form of the vocabulary entry; omit altScript entirely.`;
}

export function systemToEnglish(languagePair: LanguagePair): string {
  return (
    `You are a professional ${languagePair.foreignName} language teacher and translator. ` +
    `Translate ${languagePair.foreignName} text into English, and provide a detailed ${languagePair.foreignName} vocabulary breakdown. ` +
    `Respond ONLY with valid JSON.`
  );
}

export function systemToForeign(languagePair: LanguagePair, useSimplified: boolean): string {
  const displayName = foreignDisplayName(languagePair, useSimplified);
  return (
    `You are a professional translator and language teacher specializing in ${displayName}. ` +
    `Translate English into ${displayName} and provide a vocabulary breakdown. Respond ONLY with valid JSON.`
  );
}

export function buildPromptToForeign(
  text: string,
  languagePair: LanguagePair,
  useSimplified: boolean,
  includeGrammarNote: boolean,
): string {
  const displayName = foreignDisplayName(languagePair, useSimplified);
  const scriptRule = languagePair.hasScriptVariants
    ? useSimplified
      ? "- translatedText must use Simplified Chinese characters (简体中文), never Traditional.\n"
      : "- translatedText must use Traditional Chinese characters (繁體中文), never Simplified.\n"
    : "";
  const grammarField = includeGrammarNote
    ? `"grammarNote": "one sentence in English describing the ${displayName} sentence structure and grammar used",\n  `
    : "";
  const grammarRule = includeGrammarNote
    ? `- grammarNote must be in English, describing the grammar of the ${displayName} output.\n`
    : "";

  return `Translate the following English text into ${displayName}.

Input: "${text}"

Return this exact JSON:
{
  "translatedText": "the full translation in ${displayName}",
  "phoneticText": "${phoneticValueHint(languagePair, "the entire translatedText")}",
  ${grammarField}"vocabulary": [
    {
      ${vocabWordFieldsHint(languagePair)}
      "phonetic": "${phoneticValueHint(languagePair, "this word")}",
      "meaning": "English meaning of this word"
    }
  ]
}

Rules:
${scriptRule}- ${phoneticFieldRule(languagePair)}
${grammarRule}- vocabulary must segment translatedText into natural words or short phrases a learner would look up individually. Do not split compound words, and do not skip any.
- ${vocabWordRule(languagePair)}
- Return ONLY the JSON, nothing else.`;
}

export function buildPromptToEnglish(
  text: string,
  languagePair: LanguagePair,
  includeGrammarNote: boolean,
  checkCorrection = false,
): string {
  const grammarField = includeGrammarNote
    ? `"grammarNote": "one sentence in English describing the ${languagePair.foreignName} sentence structure and grammar",\n  `
    : "";
  const grammarRule = includeGrammarNote
    ? `- grammarNote must be in English, describing the grammar of the ${languagePair.foreignName} input.\n`
    : "";
  const inputCaveat = languagePair.hasScriptVariants
    ? " The input may be Traditional Chinese, Simplified Chinese, or a mix."
    : "";
  // The learner typed this sentence themselves (practicing production, not just
  // reading) — check it for grammar/word-choice errors as a corrected-input DTO
  // field alongside the normal translation, instead of a second API call.
  const correctionField = checkCorrection
    ? `"correction": "if the input ${languagePair.foreignName} text has grammar or word-choice errors, a corrected version of it plus a brief English note on what was wrong; empty string \\"\\" if the input is already correct",\n  `
    : "";
  const correctionRule = checkCorrection
    ? `- correction must judge only the input ${languagePair.foreignName} text's own correctness, not the English translation. Empty string if there is nothing to fix.\n`
    : "";

  return `Translate the following ${languagePair.foreignName} text into English.${inputCaveat}

Input: "${text}"

Return this exact JSON:
{
  "translatedText": "the full translation in English",
  "phoneticText": "${phoneticValueHint(languagePair, `the input ${languagePair.foreignName} text`)}",
  ${grammarField}${correctionField}"vocabulary": [
    {
      ${vocabWordFieldsHint(languagePair)}
      "phonetic": "${phoneticValueHint(languagePair, "this word")}",
      "meaning": "English meaning of this word"
    }
  ]
}

Rules:
- phoneticText is the pronunciation of the input ${languagePair.foreignName} text, not the English translation.
${grammarRule}${correctionRule}- vocabulary must segment the input into natural words or short phrases a learner would look up individually. Do not split compound words, and do not skip any.
- ${vocabWordRule(languagePair)}
- Return ONLY the JSON, nothing else.`;
}

export function buildStreamPrompt(
  text: string,
  languagePair: LanguagePair,
  toEnglish: boolean,
  useSimplified: boolean,
): string {
  if (toEnglish) {
    if (languagePair.hasPhoneticGuide) {
      return (
        `Translate the following ${languagePair.foreignName} to English.\n` +
        `Output exactly: <English translation>${STREAM_DELIMITER}<${languagePair.phoneticGuideName} of the ${languagePair.foreignName} input>\n\n` +
        `${languagePair.foreignName}:\n${text}`
      );
    }
    return (
      `Translate the following ${languagePair.foreignName} to English.\n` +
      `Output exactly: <English translation>. No labels, no extra text.\n\n` +
      `${languagePair.foreignName}:\n${text}`
    );
  }
  const displayName = foreignDisplayName(languagePair, useSimplified);
  if (languagePair.hasPhoneticGuide) {
    return (
      `Translate the following English to ${displayName}.\n` +
      `Output exactly: <${displayName} translation>${STREAM_DELIMITER}<${languagePair.phoneticGuideName} of that translation>\n\n` +
      `English:\n${text}`
    );
  }
  return (
    `Translate the following English to ${displayName}.\n` +
    `Output exactly: <${displayName} translation>. No labels, no extra text.\n\n` +
    `English:\n${text}`
  );
}

import { useCallback, useRef, useState } from "react";
import { ApiError, streamTranslate, translate as translateFull, type TranslateParams } from "../api/client";
import { languagePairFromId } from "../lib/languagePair";
import type { TranslationResult } from "../lib/types";

export type TranslationState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; result: TranslationResult; vocabLoading: boolean; vocabError: boolean }
  | { status: "error"; message: string };

function foreignLanguageCode(languagePairId: string, useSimplified: boolean) {
  const pair = languagePairFromId(languagePairId);
  if (pair.hasScriptVariants) return useSimplified ? ("zh-CN" as const) : ("zh-TW" as const);
  return pair.language;
}

// Stage 1 (translateStream) has no vocabulary/foreignText — this fills in a
// display-ready shape while the full breakdown is still loading, mirroring
// `PartialTranslation` -> `TranslationResult` in the Kotlin ViewModel.
function partialResult(params: TranslateParams, translation: string, pinyin: string): TranslationResult {
  const foreignLang = foreignLanguageCode(params.languagePairId, params.useSimplified);
  return {
    originalText: params.text,
    translatedText: translation,
    foreignText: params.toEnglish ? params.text : translation,
    phoneticText: pinyin,
    vocabulary: [],
    grammarNote: "",
    correction: "",
    sourceLanguage: params.toEnglish ? foreignLang : "en",
    targetLanguage: params.toEnglish ? "en" : foreignLang,
  };
}

export function useTranslate() {
  const [state, setState] = useState<TranslationState>({ status: "idle" });
  const lastParamsRef = useRef<TranslateParams | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const loadVocabulary = useCallback(async (params: TranslateParams, fallback: TranslationResult) => {
    try {
      const full = await translateFull(params);
      setState({ status: "success", result: full, vocabLoading: false, vocabError: false });
    } catch {
      setState({ status: "success", result: fallback, vocabLoading: false, vocabError: true });
    }
  }, []);

  const translate = useCallback(
    async (params: TranslateParams) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      lastParamsRef.current = params;
      setState({ status: "loading" });

      let lastPartial: TranslationResult | null = null;
      try {
        for await (const event of streamTranslate(params, controller.signal)) {
          if (event.type === "error") {
            setState({ status: "error", message: event.message });
            return;
          }
          lastPartial = partialResult(params, event.data.translation, event.data.pinyin);
          setState({ status: "success", result: lastPartial, vocabLoading: true, vocabError: false });
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === "AbortError") return;
        setState({ status: "error", message: err instanceof ApiError ? err.message : "Translation failed" });
        return;
      }

      if (!lastPartial) {
        setState({ status: "error", message: "No response from translation service" });
        return;
      }
      await loadVocabulary(params, lastPartial);
    },
    [loadVocabulary],
  );

  const retryVocabulary = useCallback(() => {
    if (state.status !== "success" || !lastParamsRef.current) return;
    setState({ status: "success", result: state.result, vocabLoading: true, vocabError: false });
    void loadVocabulary(lastParamsRef.current, state.result);
  }, [state, loadVocabulary]);

  return { state, translate, retryVocabulary };
}

import { useCallback, useRef, useState } from "react";
import { ApiError, streamTranslate, type TranslateParams } from "../api/client";
import { languagePairFromId } from "../lib/languagePair";
import { browserTranslatePreview } from "../lib/browserTranslate";
import { extractPartialFields } from "../lib/streamingJson";
import type { TranslationResult } from "../lib/types";

export type TranslationState =
  | { status: "idle" }
  | { status: "loading"; notice?: string }
  | {
      status: "success";
      result: TranslationResult;
      vocabLoading: boolean;
      vocabError: boolean;
      preview?: boolean;
      notice?: string;
    }
  | { status: "error"; message: string };

function foreignLanguageCode(languagePairId: string, useSimplified: boolean) {
  const pair = languagePairFromId(languagePairId);
  if (pair.hasScriptVariants) return useSimplified ? ("zh-CN" as const) : ("zh-TW" as const);
  return pair.language;
}

// Builds a display-ready TranslationResult from whatever has been extracted
// so far (browser preview, or a still-streaming backend delta) — mirrors the
// shape `shapeResult` in the backend produces once the real result lands.
function draftResult(params: TranslateParams, translatedText: string, extra: Partial<TranslationResult> = {}): TranslationResult {
  const foreignLang = foreignLanguageCode(params.languagePairId, params.useSimplified);
  return {
    originalText: params.text,
    translatedText,
    foreignText: params.toEnglish ? params.text : translatedText,
    phoneticText: "",
    vocabulary: [],
    grammarNote: "",
    correction: "",
    sourceLanguage: params.toEnglish ? foreignLang : "en",
    targetLanguage: params.toEnglish ? "en" : foreignLang,
    ...extra,
  };
}

export function useTranslate() {
  const [state, setState] = useState<TranslationState>({ status: "idle" });
  const lastParamsRef = useRef<TranslateParams | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const translate = useCallback(async (params: TranslateParams) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    lastParamsRef.current = params;
    setState({ status: "loading" });

    let buffer = "";
    let sawDelta = false;

    // Free, instant, on-device preview (Chrome only, feature-detected) —
    // purely cosmetic while the accurate backend stream is still in flight.
    // Dropped the moment a real delta arrives.
    void browserTranslatePreview(params).then((preview) => {
      if (!preview || sawDelta || controller.signal.aborted) return;
      setState({ status: "success", result: draftResult(params, preview), vocabLoading: true, vocabError: false, preview: true });
    });

    try {
      for await (const event of streamTranslate(params, controller.signal)) {
        if ("error" in event) {
          setState({ status: "error", message: event.error });
          return;
        }
        if ("notice" in event) {
          setState({ status: "loading", notice: event.notice });
          continue;
        }
        if ("delta" in event) {
          sawDelta = true;
          buffer += event.delta;
          const fields = extractPartialFields(buffer);
          setState({
            status: "success",
            result: draftResult(params, fields.translatedText, {
              phoneticText: fields.phoneticText,
              grammarNote: fields.grammarNote,
              correction: fields.correction,
              vocabulary: fields.vocabulary.map((v) => ({
                word: v.word,
                phonetic: v.phonetic ?? "",
                meaning: v.meaning,
                frequency: 0,
                altScript: v.altScript ?? null,
                languagePairId: params.languagePairId,
              })),
            }),
            vocabLoading: true,
            vocabError: false,
          });
          continue;
        }
        // event.result: the authoritative shaped translation, streamed last.
        sawDelta = true;
        setState({ status: "success", result: event.result, vocabLoading: false, vocabError: false, notice: event.result.notice });
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      const message = err instanceof ApiError ? err.message : "Translation failed";
      setState((prev) =>
        sawDelta && prev.status === "success" ? { ...prev, vocabLoading: false, vocabError: true } : { status: "error", message },
      );
      return;
    }

    if (!sawDelta) {
      setState({ status: "error", message: "No response from translation service" });
    }
  }, []);

  const retry = useCallback(() => {
    if (lastParamsRef.current) void translate(lastParamsRef.current);
  }, [translate]);

  return { state, translate, retry };
}

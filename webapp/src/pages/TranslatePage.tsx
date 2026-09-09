import { useState } from "react";
import { useTranslate } from "../hooks/useTranslate";
import type { useVocabulary } from "../hooks/useVocabulary";
import { speak, ttsSupported } from "../audio/speech";
import { speechRecognitionSupported, useSpeechRecognition } from "../audio/useSpeechRecognition";
import { languagePairFromId } from "../lib/languagePair";

interface Props {
  languagePairId: string;
  useSimplified: boolean;
  toEnglish: boolean;
  setToEnglish: (fn: (v: boolean) => boolean) => void;
  vocab: ReturnType<typeof useVocabulary>;
}

export function TranslatePage({ languagePairId, useSimplified, toEnglish, setToEnglish, vocab }: Props) {
  const [text, setText] = useState("");
  const [checkGrammar, setCheckGrammar] = useState(false);
  const { state, translate, retry } = useTranslate();
  const pair = languagePairFromId(languagePairId);
  const speechLocale = toEnglish ? pair.speechLocale : "en-US";
  const recognition = useSpeechRecognition(speechLocale);
  // Correction only makes sense when the learner typed the foreign-language
  // sentence themselves (toEnglish) — there's nothing of theirs to correct
  // when they're reading a translation generated the other direction.
  const correctionAvailable = toEnglish;

  const handleTranslate = () => {
    if (!text.trim()) return;
    void translate({
      text: text.trim(),
      languagePairId,
      toEnglish,
      useSimplified,
      checkGrammar: correctionAvailable && checkGrammar,
    });
  };

  return (
    <div className="translate-page">
      <div className="direction-bar">
        <span>{toEnglish ? pair.label : "English"}</span>
        <button className="icon-button" onClick={() => setToEnglish((v) => !v)} aria-label="Swap direction">
          ⇄
        </button>
        <span>{toEnglish ? "English" : pair.label}</span>
      </div>

      <div className="input-row">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={`Enter ${toEnglish ? pair.label : "English"} text…`}
          rows={3}
        />
        {speechRecognitionSupported && (
          <button
            className={`icon-button mic-button ${recognition.phase === "listening" ? "active" : ""}`}
            onMouseDown={() => recognition.start((transcript) => setText(transcript))}
            onMouseUp={recognition.stop}
            onMouseLeave={recognition.stop}
            aria-label="Hold to record"
          >
            🎤
          </button>
        )}
      </div>
      {recognition.error && <p className="hint error">{recognition.error}</p>}

      {correctionAvailable && (
        <label className="checkbox-row hint">
          <input type="checkbox" checked={checkGrammar} onChange={(e) => setCheckGrammar(e.target.checked)} />
          Check my {pair.label} for mistakes
        </label>
      )}

      <button className="button primary" onClick={handleTranslate} disabled={state.status === "loading"}>
        {state.status === "loading" ? "Translating…" : "Translate"}
      </button>

      {state.status === "loading" && state.notice && <p className="hint">{state.notice}</p>}
      {state.status === "error" && <p className="hint error">{state.message}</p>}

      {state.status === "success" && (
        <div className="result-card card">
          {state.notice && <p className="hint">{state.notice}</p>}
          <div className="result-foreign">
            <span>{state.result.foreignText}</span>
            {ttsSupported && (
              <button className="icon-button" onClick={() => speak(state.result.foreignText, pair.ttsLocale)} aria-label="Play">
                🔊
              </button>
            )}
            {state.preview && <span className="hint preview-tag">⚡ instant preview</span>}
          </div>
          {state.result.phoneticText && <p className="phonetic">{state.result.phoneticText}</p>}
          {!toEnglish ? null : <p className="translated-text">{state.result.translatedText}</p>}
          {state.result.grammarNote && <p className="grammar-note">{state.result.grammarNote}</p>}
          {state.result.correction && (
            <p className="correction-note">
              <strong>Correction:</strong> {state.result.correction}
            </p>
          )}

          {state.vocabLoading && <p className="hint">{state.preview ? "Translating…" : "Loading breakdown…"}</p>}
          {state.vocabError && (
            <div className="hint error">
              Couldn't finish the translation.{" "}
              <button className="link-button" onClick={retry}>
                Retry
              </button>
            </div>
          )}

          {state.result.vocabulary.length > 0 && (
            <ul className="vocab-breakdown">
              {state.result.vocabulary.map((v) => {
                const saved = vocab.isSaved(v.word, languagePairId);
                return (
                  <li key={v.word}>
                    <div>
                      <span className="vocab-word">{v.word}</span>
                      {v.altScript && <span className="vocab-alt">({v.altScript})</span>}
                      {v.phonetic && <span className="vocab-phonetic">{v.phonetic}</span>}
                      <span className="vocab-meaning">{v.meaning}</span>
                    </div>
                    <button
                      className="icon-button"
                      disabled={saved}
                      onClick={() =>
                        void vocab.save({
                          word: v.word,
                          phonetic: v.phonetic,
                          meaning: v.meaning,
                          altScript: v.altScript,
                          languagePairId,
                        })
                      }
                      aria-label={saved ? "Saved" : "Save"}
                    >
                      {saved ? "★" : "☆"}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

import { useCallback, useEffect, useRef, useState } from "react";

// The Web Speech API's SpeechRecognition type isn't in TS's DOM lib yet
// (still prefixed `webkit` in Chrome/Safari) — minimal shape for what we use.
interface SpeechRecognitionEventLike {
  results: { [index: number]: { [index: number]: { transcript: string }; isFinal: boolean }; length: number };
}
interface SpeechRecognitionLike extends EventTarget {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start(): void;
  stop(): void;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
}

type SpeechRecognitionCtor = new () => SpeechRecognitionLike;

function getRecognitionCtor(): SpeechRecognitionCtor | null {
  const w = window as unknown as { SpeechRecognition?: SpeechRecognitionCtor; webkitSpeechRecognition?: SpeechRecognitionCtor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export const speechRecognitionSupported = typeof window !== "undefined" && getRecognitionCtor() !== null;

export type RecordingPhase = "idle" | "listening";

/** Hold-to-record voice input, mirroring the native app's SpeechRecognizer usage. */
export function useSpeechRecognition(locale: string) {
  const [phase, setPhase] = useState<RecordingPhase>("idle");
  const [error, setError] = useState<string | null>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const onFinalRef = useRef<(text: string) => void>(() => {});

  const start = useCallback(
    (onFinal: (text: string) => void) => {
      const Ctor = getRecognitionCtor();
      if (!Ctor) {
        setError("Speech input isn't supported in this browser.");
        return;
      }
      onFinalRef.current = onFinal;
      const recognition = new Ctor();
      recognition.lang = locale;
      recognition.continuous = false;
      recognition.interimResults = false;
      recognition.onresult = (event) => {
        const last = event.results[event.results.length - 1];
        const transcript = last?.[0]?.transcript;
        if (transcript) onFinalRef.current(transcript);
      };
      recognition.onerror = (event) => setError(event.error);
      recognition.onend = () => setPhase("idle");
      recognitionRef.current = recognition;
      setError(null);
      setPhase("listening");
      recognition.start();
    },
    [locale],
  );

  const stop = useCallback(() => {
    recognitionRef.current?.stop();
    setPhase("idle");
  }, []);

  useEffect(() => () => recognitionRef.current?.stop(), []);

  return { phase, error, start, stop };
}

import { useEffect, useState } from "react";
import { hasMatchingVoice, ttsSupported } from "./speech";

// null = still checking (or TTS unsupported entirely); true/false once resolved.
export function useTtsVoiceAvailable(locale: string): boolean | null {
  const [available, setAvailable] = useState<boolean | null>(null);

  useEffect(() => {
    if (!ttsSupported) return;
    setAvailable(null);
    let cancelled = false;
    void hasMatchingVoice(locale).then((result) => {
      if (!cancelled) setAvailable(result);
    });
    return () => {
      cancelled = true;
    };
  }, [locale]);

  return available;
}

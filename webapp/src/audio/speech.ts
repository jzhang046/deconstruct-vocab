// Native browser TTS. Unlike the Kotlin web target (which stubs this out
// entirely — see CLAUDE.md), the browser's own SpeechSynthesis API gives the
// React app real TTS for free.

// Setting utterance.lang alone isn't enough: browsers often fall back to
// whatever voice is default-selected (usually an English one) instead of
// picking one that actually matches the requested locale, so the text gets
// read in the right words but the wrong accent/tone. Explicitly resolving
// and assigning a matching voice fixes that.
function pickVoice(locale: string): SpeechSynthesisVoice | null {
  const voices = window.speechSynthesis.getVoices();
  const lower = locale.toLowerCase();
  return (
    voices.find((v) => v.lang.toLowerCase() === lower) ??
    voices.find((v) => v.lang.toLowerCase().startsWith(lower.split("-")[0])) ??
    null
  );
}

export function speak(text: string, locale: string): void {
  if (!("speechSynthesis" in window) || !text) return;
  const synth = window.speechSynthesis;
  synth.cancel();

  const say = () => {
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = locale;
    utterance.voice = pickVoice(locale);
    synth.speak(utterance);
  };

  // Voice list loads asynchronously on first use in some browsers.
  if (synth.getVoices().length === 0) {
    synth.addEventListener("voiceschanged", say, { once: true });
  } else {
    say();
  }
}

export function stopSpeaking(): void {
  if ("speechSynthesis" in window) window.speechSynthesis.cancel();
}

export const ttsSupported = typeof window !== "undefined" && "speechSynthesis" in window;

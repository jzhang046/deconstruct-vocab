// Native browser TTS. Unlike the Kotlin web target (which stubs this out
// entirely — see CLAUDE.md), the browser's own SpeechSynthesis API gives the
// React app real TTS for free.

// Voice list loads asynchronously on first use in some browsers.
function getVoices(): Promise<SpeechSynthesisVoice[]> {
  const synth = window.speechSynthesis;
  const existing = synth.getVoices();
  if (existing.length > 0) return Promise.resolve(existing);
  return new Promise((resolve) => {
    synth.addEventListener("voiceschanged", () => resolve(synth.getVoices()), { once: true });
  });
}

// Setting utterance.lang alone isn't enough: browsers often fall back to
// whatever voice is default-selected (usually an English one) instead of
// picking one that actually matches the requested locale, so the text gets
// read in the right words but the wrong accent/tone. Explicitly resolving
// and assigning a matching voice fixes that.
function pickVoice(locale: string, voices: SpeechSynthesisVoice[]): SpeechSynthesisVoice | null {
  const lower = locale.toLowerCase();
  return (
    voices.find((v) => v.lang.toLowerCase() === lower) ??
    voices.find((v) => v.lang.toLowerCase().startsWith(lower.split("-")[0])) ??
    null
  );
}

export async function speak(text: string, locale: string): Promise<void> {
  if (!("speechSynthesis" in window) || !text) return;
  const synth = window.speechSynthesis;
  synth.cancel();
  const voices = await getVoices();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = locale;
  utterance.voice = pickVoice(locale, voices);
  synth.speak(utterance);
}

// Lets the UI warn the user when no voice matches their studied language,
// since in that case playback silently falls back to whatever the device's
// default voice is (usually English) instead of failing loudly.
export async function hasMatchingVoice(locale: string): Promise<boolean> {
  if (!("speechSynthesis" in window)) return false;
  const voices = await getVoices();
  return pickVoice(locale, voices) !== null;
}

export function stopSpeaking(): void {
  if ("speechSynthesis" in window) window.speechSynthesis.cancel();
}

export const ttsSupported = typeof window !== "undefined" && "speechSynthesis" in window;

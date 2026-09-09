// Native browser TTS. Unlike the Kotlin web target (which stubs this out
// entirely — see CLAUDE.md), the browser's own SpeechSynthesis API gives the
// React app real TTS for free.

export function speak(text: string, locale: string): void {
  if (!("speechSynthesis" in window) || !text) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = locale;
  window.speechSynthesis.speak(utterance);
}

export function stopSpeaking(): void {
  if ("speechSynthesis" in window) window.speechSynthesis.cancel();
}

export const ttsSupported = typeof window !== "undefined" && "speechSynthesis" in window;

// Coarse OS detection via user-agent sniffing — good enough to pick which
// single settings path to show for "how do I install a TTS voice", not
// meant for anything more precise (browser/version branching, etc).
export type DetectedOS = "macos" | "windows" | "ios" | "android" | "other";

export function detectOS(): DetectedOS {
  if (typeof navigator === "undefined") return "other";
  const ua = navigator.userAgent;
  // iPadOS 13+ reports as "MacIntel" with touch support, so UA alone can't tell it apart from macOS.
  const isIOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  if (isIOS) return "ios";
  if (/Android/.test(ua)) return "android";
  if (/Mac/.test(ua)) return "macos";
  if (/Win/.test(ua)) return "windows";
  return "other";
}

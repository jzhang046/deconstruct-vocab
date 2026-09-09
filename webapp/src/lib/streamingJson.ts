// Best-effort extraction of fields from an in-progress JSON object while it
// streams in (see useTranslate.ts). Only needs to be "close enough" for
// optimistic rendering — the backend sends an authoritative shaped
// TranslationResult once the stream completes, which replaces whatever this
// produced. Tailored to the fixed schema shape in promptBuilder.ts (flat
// string fields, then a `vocabulary` array of flat objects) rather than a
// general streaming-JSON parser.

export interface PartialVocab {
  word: string;
  altScript?: string;
  phonetic?: string;
  meaning: string;
}

export interface PartialFields {
  translatedText: string;
  phoneticText: string;
  grammarNote: string;
  correction: string;
  vocabulary: PartialVocab[];
}

function extractString(buffer: string, key: string): string {
  const match = buffer.match(new RegExp(`"${key}"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)"`));
  if (!match) return "";
  try {
    return JSON.parse(`"${match[1]}"`) as string;
  } catch {
    return "";
  }
}

// Scans the (possibly still-growing) `vocabulary` array and returns every
// object that has closed with a balanced `}` so far.
function extractVocabulary(buffer: string): PartialVocab[] {
  const key = buffer.indexOf('"vocabulary"');
  if (key === -1) return [];
  const arrayStart = buffer.indexOf("[", key);
  if (arrayStart === -1) return [];

  const items: PartialVocab[] = [];
  let i = arrayStart + 1;
  while (i < buffer.length) {
    while (i < buffer.length && /[\s,]/.test(buffer[i])) i++;
    if (buffer[i] !== "{") break;

    let depth = 0;
    let inString = false;
    let escaped = false;
    let j = i;
    for (; j < buffer.length; j++) {
      const ch = buffer[j];
      if (inString) {
        if (escaped) escaped = false;
        else if (ch === "\\") escaped = true;
        else if (ch === '"') inString = false;
      } else if (ch === '"') {
        inString = true;
      } else if (ch === "{") {
        depth++;
      } else if (ch === "}") {
        depth--;
        if (depth === 0) {
          j++;
          break;
        }
      }
    }
    if (depth !== 0) break; // object not closed yet — wait for more deltas

    try {
      items.push(JSON.parse(buffer.slice(i, j)) as PartialVocab);
    } catch {
      break;
    }
    i = j;
  }
  return items;
}

export function extractPartialFields(buffer: string): PartialFields {
  return {
    translatedText: extractString(buffer, "translatedText"),
    phoneticText: extractString(buffer, "phoneticText"),
    grammarNote: extractString(buffer, "grammarNote"),
    correction: extractString(buffer, "correction"),
    vocabulary: extractVocabulary(buffer),
  };
}

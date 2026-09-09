import type { useVocabulary } from "../hooks/useVocabulary";
import { languagePairFromId } from "../lib/languagePair";

interface Props {
  vocab: ReturnType<typeof useVocabulary>;
  languagePairId: string;
}

export function VocabularyPage({ vocab, languagePairId }: Props) {
  const filtered = vocab.items.filter((item) => item.languagePairId === languagePairId);
  const pair = languagePairFromId(languagePairId);

  if (vocab.loading) return <p className="hint">Loading saved words…</p>;
  if (filtered.length === 0) {
    return <p className="hint">No saved {pair.label} words yet. Save words from a translation to see them here.</p>;
  }

  return (
    <ul className="vocab-list">
      {filtered.map((item) => (
        <li key={item.id} className="vocab-item">
          <div className="vocab-item-main">
            <span className="vocab-word">{item.word}</span>
            {item.altScript && <span className="vocab-alt">({item.altScript})</span>}
            {item.phonetic && <span className="vocab-phonetic">{item.phonetic}</span>}
            <span className="vocab-meaning">{item.meaning}</span>
          </div>
          <div className="vocab-item-actions">
            <span className="badge">{item.frequency}</span>
            <button className="icon-button" onClick={() => void vocab.remove(item.id)} aria-label="Remove">
              ✕
            </button>
          </div>
        </li>
      ))}
    </ul>
  );
}

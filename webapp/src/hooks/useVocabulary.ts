import { useCallback, useEffect, useState } from "react";
import { deleteVocab, listVocab, saveVocab } from "../api/client";
import type { VocabularyItem } from "../lib/types";

export function useVocabulary() {
  const [items, setItems] = useState<VocabularyItem[]>([]);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await listVocab());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const save = useCallback(async (item: Omit<VocabularyItem, "id" | "frequency">) => {
    const saved = await saveVocab(item);
    setItems((prev) => {
      const withoutExisting = prev.filter((i) => i.id !== saved.id);
      return [...withoutExisting, saved].sort((a, b) => b.frequency - a.frequency);
    });
  }, []);

  const remove = useCallback(async (id: string) => {
    await deleteVocab(id);
    setItems((prev) => prev.filter((i) => i.id !== id));
  }, []);

  const isSaved = useCallback(
    (word: string, languagePairId: string) =>
      items.some((i) => i.word === word && i.languagePairId === languagePairId),
    [items],
  );

  return { items, loading, refresh, save, remove, isSaved };
}

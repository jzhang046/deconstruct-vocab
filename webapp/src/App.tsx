import { useEffect, useState } from "react";
import { fetchMe, logout } from "./api/client";
import { useVocabulary } from "./hooks/useVocabulary";
import { LANGUAGE_PAIRS, languagePairFromId } from "./lib/languagePair";
import type { User } from "./lib/types";
import { LoginPage } from "./pages/LoginPage";
import { TranslatePage } from "./pages/TranslatePage";
import { VocabularyPage } from "./pages/VocabularyPage";

type Tab = "translate" | "saved";

// Remembers the last language pair / script / direction across visits. Kept
// in localStorage (per-browser) rather than round-tripping to the backend —
// it's a lightweight UI preference, not data that needs to follow the user
// across devices.
const PREFS_KEY = "deconstruct:prefs";

interface StoredPrefs {
  languagePairId: string;
  useSimplified: boolean;
  toEnglish: boolean;
}

function loadPrefs(): StoredPrefs {
  const defaults: StoredPrefs = { languagePairId: "zh", useSimplified: true, toEnglish: false };
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    if (!raw) return defaults;
    return { ...defaults, ...JSON.parse(raw) };
  } catch {
    return defaults;
  }
}

export default function App() {
  const [user, setUser] = useState<User | null | undefined>(undefined);
  const [tab, setTab] = useState<Tab>("translate");
  const [prefs, setPrefs] = useState<StoredPrefs>(loadPrefs);
  const { languagePairId, useSimplified, toEnglish } = prefs;
  const vocab = useVocabulary();
  const pair = languagePairFromId(languagePairId);

  useEffect(() => {
    void fetchMe().then(setUser);
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
    } catch {
      // Storage can be unavailable (private browsing, quota) — losing the
      // remembered preference is harmless, so just skip persisting it.
    }
  }, [prefs]);

  const setLanguagePairId = (id: string) => setPrefs((p) => ({ ...p, languagePairId: id }));
  const setUseSimplified = (fn: (v: boolean) => boolean) =>
    setPrefs((p) => ({ ...p, useSimplified: fn(p.useSimplified) }));
  const setToEnglish = (fn: (v: boolean) => boolean) => setPrefs((p) => ({ ...p, toEnglish: fn(p.toEnglish) }));

  if (user === undefined) return <div className="centered-page">Loading…</div>;
  if (user === null) return <LoginPage />;

  return (
    <div className="app">
      <header className="app-header">
        <h1>Deconstruct</h1>
        <div className="header-controls">
          <select value={languagePairId} onChange={(e) => setLanguagePairId(e.target.value)}>
            {LANGUAGE_PAIRS.map((p) => (
              <option key={p.id} value={p.id}>
                {p.label}
              </option>
            ))}
          </select>
          {pair.hasScriptVariants && (
            <button className="button" onClick={() => setUseSimplified((v) => !v)}>
              {useSimplified ? "简体" : "繁體"}
            </button>
          )}
          <span className="user-name">{user.name}</span>
          <button className="button" onClick={() => void logout().then(() => setUser(null))}>
            Sign out
          </button>
        </div>
      </header>

      <nav className="tab-bar">
        <button className={tab === "translate" ? "active" : ""} onClick={() => setTab("translate")}>
          Translate
        </button>
        <button className={tab === "saved" ? "active" : ""} onClick={() => setTab("saved")}>
          Saved
        </button>
      </nav>

      {/* Both panels stay mounted so state (input text, in-flight translation)
          survives switching tabs on mobile, and so a wide viewport can show
          them side by side via CSS alone (see .app-content in index.css) —
          .panel visibility is what actually implements the mobile tabs. */}
      <main className="app-content" data-active-tab={tab}>
        <div className="panel panel-translate">
          <TranslatePage
            languagePairId={languagePairId}
            useSimplified={useSimplified}
            toEnglish={toEnglish}
            setToEnglish={setToEnglish}
            vocab={vocab}
          />
        </div>
        <div className="panel panel-saved">
          <h2 className="panel-title">Saved</h2>
          <VocabularyPage vocab={vocab} languagePairId={languagePairId} />
        </div>
      </main>
    </div>
  );
}

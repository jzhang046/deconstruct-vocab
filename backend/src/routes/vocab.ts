import { Hono } from "hono";
import { and, count, desc, eq } from "drizzle-orm";
import type { Bindings } from "../env";
import type { AuthedVariables } from "../lib/authMiddleware";
import { requireAuth } from "../lib/authMiddleware";
import { getDb } from "../db/client";
import { vocabulary } from "../db/schema";

// Per-user cap on saved vocabulary. See plan: keeps D1 storage and per-user
// query size bounded; easy to raise if it turns out too tight in practice.
const VOCAB_LIMIT = 500;

const vocab = new Hono<{ Bindings: Bindings; Variables: AuthedVariables }>();
vocab.use("*", requireAuth);

vocab.get("/", async (c) => {
  const db = getDb(c.env.DB);
  const session = c.get("session");
  const rows = await db
    .select()
    .from(vocabulary)
    .where(eq(vocabulary.userId, session.sub))
    .orderBy(desc(vocabulary.frequency));
  return c.json(rows);
});

interface SaveVocabBody {
  word: string;
  phonetic?: string;
  meaning: string;
  altScript?: string | null;
  languagePairId: string;
}

vocab.post("/", async (c) => {
  const db = getDb(c.env.DB);
  const session = c.get("session");
  const body = await c.req.json<Partial<SaveVocabBody>>();
  if (!body.word || !body.meaning || !body.languagePairId) {
    return c.json({ error: "word, meaning, and languagePairId are required" }, 400);
  }

  const [existing] = await db
    .select()
    .from(vocabulary)
    .where(
      and(
        eq(vocabulary.userId, session.sub),
        eq(vocabulary.word, body.word),
        eq(vocabulary.languagePairId, body.languagePairId),
      ),
    )
    .limit(1);

  const now = new Date();

  if (existing) {
    await db
      .update(vocabulary)
      .set({ frequency: existing.frequency + 1, updatedAt: now })
      .where(eq(vocabulary.id, existing.id));
    return c.json({ ...existing, frequency: existing.frequency + 1, updatedAt: now });
  }

  const [{ value: total }] = await db
    .select({ value: count() })
    .from(vocabulary)
    .where(eq(vocabulary.userId, session.sub));
  if (total >= VOCAB_LIMIT) {
    return c.json({ error: `Saved-word limit reached (${VOCAB_LIMIT}). Remove a word before adding another.` }, 403);
  }

  const row = {
    id: crypto.randomUUID(),
    userId: session.sub,
    word: body.word,
    phonetic: body.phonetic ?? "",
    meaning: body.meaning,
    frequency: 1,
    altScript: body.altScript ?? null,
    languagePairId: body.languagePairId,
    createdAt: now,
    updatedAt: now,
  };
  await db.insert(vocabulary).values(row);
  return c.json(row, 201);
});

vocab.delete("/:id", async (c) => {
  const db = getDb(c.env.DB);
  const session = c.get("session");
  const id = c.req.param("id");
  await db.delete(vocabulary).where(and(eq(vocabulary.id, id), eq(vocabulary.userId, session.sub)));
  return c.json({ ok: true });
});

export default vocab;

import { Hono } from "hono";
import { streamSSE } from "hono/streaming";
import type { Bindings } from "../env";
import type { AuthedVariables } from "../lib/authMiddleware";
import { requireAuth } from "../lib/authMiddleware";
import { languagePairFromId } from "../lib/languagePair";
import {
  autoSwitchTranslate,
  autoSwitchTranslateStream,
  geminiConfig,
  GEMINI_FALLBACK_MODEL,
  GEMINI_PRIMARY_MODEL,
} from "../lib/providers";
import { checkAndIncrementTranslateQuota, QuotaExceededError } from "../lib/rateLimit";
import { getDb } from "../db/client";
import type { TranslateRequestBody } from "../types";

const translate = new Hono<{ Bindings: Bindings; Variables: AuthedVariables }>();
translate.use("*", requireAuth);

// Gemini primary model first, falling back to a secondary model only on a
// retryable failure (see autoSwitchTranslate in lib/providers.ts). Qwen is
// temporarily removed from the chain.
function providersFromEnv(env: Bindings) {
  const apiKey = env.GEMINI_API_KEY ?? "";
  return [geminiConfig(apiKey, GEMINI_PRIMARY_MODEL), geminiConfig(apiKey, GEMINI_FALLBACK_MODEL)];
}

translate.post("/", async (c) => {
  const body = await c.req.json<Partial<TranslateRequestBody>>();
  if (!body.text || typeof body.text !== "string") {
    return c.json({ error: "text is required" }, 400);
  }

  const db = getDb(c.env.DB);
  const session = c.get("session");
  try {
    await checkAndIncrementTranslateQuota(db, session.sub);
  } catch (err) {
    if (err instanceof QuotaExceededError) return c.json({ error: err.message }, 429);
    throw err;
  }

  const languagePair = languagePairFromId(body.languagePairId ?? "zh");
  try {
    const result = await autoSwitchTranslate(
      providersFromEnv(c.env),
      body.text,
      languagePair,
      body.toEnglish ?? false,
      body.useSimplified ?? true,
      body.includeGrammarNote ?? true,
      body.checkGrammar ?? false,
    );
    return c.json(result);
  } catch (err) {
    return c.json({ error: err instanceof Error ? err.message : "Translation failed" }, 502);
  }
});

translate.get("/stream", async (c) => {
  const text = c.req.query("text");
  if (!text) return c.json({ error: "text is required" }, 400);

  const db = getDb(c.env.DB);
  const session = c.get("session");
  try {
    await checkAndIncrementTranslateQuota(db, session.sub);
  } catch (err) {
    if (err instanceof QuotaExceededError) return c.json({ error: err.message }, 429);
    throw err;
  }

  const languagePair = languagePairFromId(c.req.query("languagePairId") ?? "zh");
  const toEnglish = c.req.query("toEnglish") === "true";
  const useSimplified = c.req.query("useSimplified") !== "false";
  const includeGrammarNote = c.req.query("includeGrammarNote") !== "false";
  const checkGrammar = c.req.query("checkGrammar") === "true";

  return streamSSE(c, async (stream) => {
    try {
      for await (const event of autoSwitchTranslateStream(
        providersFromEnv(c.env),
        text,
        languagePair,
        toEnglish,
        useSimplified,
        includeGrammarNote,
        checkGrammar,
      )) {
        await stream.writeSSE({ data: JSON.stringify(event) });
      }
    } catch (err) {
      await stream.writeSSE({
        event: "error",
        data: JSON.stringify({ error: err instanceof Error ? err.message : "Translation failed" }),
      });
    }
  });
});

export default translate;

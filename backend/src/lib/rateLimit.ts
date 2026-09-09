// Per-user daily translation quota. Added as a hard requirement of the
// Cloudflare migration (see plan): the backend now holds one shared Qwen/Gemini
// key for every user, so an unbounded /api/translate is a direct line to
// unbounded API spend.

import { and, eq } from "drizzle-orm";
import type { Db } from "../db/client";
import { usageCounters } from "../db/schema";

export const DAILY_TRANSLATE_LIMIT = 200;

export class QuotaExceededError extends Error {
  constructor(limit: number) {
    super(`Daily translation limit reached (${limit}/day). Try again tomorrow.`);
  }
}

function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Throws QuotaExceededError if the user is already at the daily limit, else increments the counter. */
export async function checkAndIncrementTranslateQuota(
  db: Db,
  userId: string,
  limit: number = DAILY_TRANSLATE_LIMIT,
): Promise<void> {
  const date = todayUtc();
  const [row] = await db
    .select()
    .from(usageCounters)
    .where(and(eq(usageCounters.userId, userId), eq(usageCounters.date, date)))
    .limit(1);

  if (row && row.translateCount >= limit) {
    throw new QuotaExceededError(limit);
  }

  if (row) {
    await db
      .update(usageCounters)
      .set({ translateCount: row.translateCount + 1 })
      .where(and(eq(usageCounters.userId, userId), eq(usageCounters.date, date)));
  } else {
    await db.insert(usageCounters).values({ userId, date, translateCount: 1 });
  }
}

import type { Context, Next } from "hono";
import type { Bindings } from "../env";
import { readSession, type SessionPayload } from "./session";

export type AuthedVariables = { session: SessionPayload };

export async function requireAuth(
  c: Context<{ Bindings: Bindings; Variables: AuthedVariables }>,
  next: Next,
) {
  const session = await readSession(c);
  if (!session) {
    return c.json({ error: "Not authenticated" }, 401);
  }
  c.set("session", session);
  await next();
}

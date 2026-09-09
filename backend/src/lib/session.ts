import { sign, verify } from "hono/jwt";
import { getCookie, setCookie, deleteCookie } from "hono/cookie";
import type { Context } from "hono";
import type { Bindings } from "../env";

const SESSION_COOKIE = "session";
const SESSION_TTL_SECONDS = 30 * 24 * 60 * 60; // 30 days

export interface SessionPayload {
  [key: string]: unknown;
  sub: string; // user id
  email: string;
  exp: number;
}

export async function issueSession<E extends { Bindings: Bindings }>(
  c: Context<E>,
  userId: string,
  email: string,
) {
  const payload: SessionPayload = {
    sub: userId,
    email,
    exp: Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS,
  };
  const token = await sign(payload, c.env.SESSION_SECRET);
  setCookie(c, SESSION_COOKIE, token, {
    httpOnly: true,
    // Browsers silently drop `Secure` cookies set over plain HTTP (even on
    // 127.0.0.1), so local `wrangler dev` needs it off — real deploys are
    // always HTTPS, so ENVIRONMENT there must never be "development".
    secure: c.env.ENVIRONMENT !== "development",
    sameSite: "Lax",
    path: "/",
    maxAge: SESSION_TTL_SECONDS,
  });
}

export function clearSession<E extends { Bindings: Bindings }>(c: Context<E>) {
  deleteCookie(c, SESSION_COOKIE, { path: "/" });
}

export async function readSession<E extends { Bindings: Bindings }>(
  c: Context<E>,
): Promise<SessionPayload | null> {
  const token = getCookie(c, SESSION_COOKIE);
  if (!token) return null;
  try {
    return (await verify(token, c.env.SESSION_SECRET, "HS256")) as unknown as SessionPayload;
  } catch {
    return null;
  }
}

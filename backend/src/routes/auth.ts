import { Hono } from "hono";
import { getCookie, setCookie, deleteCookie } from "hono/cookie";
import { eq, and } from "drizzle-orm";
import type { Bindings } from "../env";
import type { AuthedVariables } from "../lib/authMiddleware";
import { requireAuth } from "../lib/authMiddleware";
import { codeChallengeFromVerifier, randomToken } from "../lib/pkce";
import { issueSession, clearSession } from "../lib/session";
import { getDb } from "../db/client";
import { users } from "../db/schema";

const STATE_COOKIE = "oauth_state";
const VERIFIER_COOKIE = "oauth_verifier";
const OAUTH_COOKIE_TTL = 600; // 10 minutes — just long enough for the redirect round-trip

// Only Google is wired up today. Adding GitHub: add an entry here (GitHub's
// token endpoint needs an `Accept: application/json` header and doesn't
// support PKCE — code_verifier can just be omitted from its token exchange),
// plus GITHUB_CLIENT_ID/GITHUB_CLIENT_SECRET bindings in wrangler.toml + env.ts.
const GOOGLE = {
  id: "google",
  authorizeUrl: "https://accounts.google.com/o/oauth2/v2/auth",
  tokenUrl: "https://oauth2.googleapis.com/token",
  userinfoUrl: "https://www.googleapis.com/oauth2/v3/userinfo",
  scope: "openid email profile",
};

const auth = new Hono<{ Bindings: Bindings; Variables: AuthedVariables }>();

auth.get("/login/:provider", async (c) => {
  const provider = c.req.param("provider");
  if (provider !== "google") return c.json({ error: `Unknown provider: ${provider}` }, 404);

  const verifier = randomToken();
  const challenge = await codeChallengeFromVerifier(verifier);
  const state = randomToken(16);

  const secure = c.env.ENVIRONMENT !== "development";
  setCookie(c, VERIFIER_COOKIE, verifier, {
    httpOnly: true,
    secure,
    sameSite: "Lax",
    path: "/api/auth",
    maxAge: OAUTH_COOKIE_TTL,
  });
  setCookie(c, STATE_COOKIE, state, {
    httpOnly: true,
    secure,
    sameSite: "Lax",
    path: "/api/auth",
    maxAge: OAUTH_COOKIE_TTL,
  });

  const redirectUri = new URL("/api/auth/callback/google", c.req.url).toString();
  const authorizeUrl = new URL(GOOGLE.authorizeUrl);
  authorizeUrl.searchParams.set("client_id", c.env.GOOGLE_CLIENT_ID);
  authorizeUrl.searchParams.set("redirect_uri", redirectUri);
  authorizeUrl.searchParams.set("response_type", "code");
  authorizeUrl.searchParams.set("scope", GOOGLE.scope);
  authorizeUrl.searchParams.set("state", state);
  authorizeUrl.searchParams.set("code_challenge", challenge);
  authorizeUrl.searchParams.set("code_challenge_method", "S256");
  authorizeUrl.searchParams.set("access_type", "online");
  authorizeUrl.searchParams.set("prompt", "select_account");

  return c.redirect(authorizeUrl.toString());
});

auth.get("/callback/:provider", async (c) => {
  const provider = c.req.param("provider");
  if (provider !== "google") return c.json({ error: `Unknown provider: ${provider}` }, 404);

  const code = c.req.query("code");
  const state = c.req.query("state");
  const expectedState = getCookie(c, STATE_COOKIE);
  const verifier = getCookie(c, VERIFIER_COOKIE);
  deleteCookie(c, STATE_COOKIE, { path: "/api/auth" });
  deleteCookie(c, VERIFIER_COOKIE, { path: "/api/auth" });

  if (!code || !state || !expectedState || state !== expectedState || !verifier) {
    return c.json({ error: "Invalid OAuth callback (missing or mismatched state)" }, 400);
  }

  const redirectUri = new URL("/api/auth/callback/google", c.req.url).toString();
  const tokenResponse = await fetch(GOOGLE.tokenUrl, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: c.env.GOOGLE_CLIENT_ID,
      client_secret: c.env.GOOGLE_CLIENT_SECRET,
      code,
      code_verifier: verifier,
      grant_type: "authorization_code",
      redirect_uri: redirectUri,
    }),
  });
  if (!tokenResponse.ok) {
    return c.json({ error: `Google token exchange failed: ${await tokenResponse.text()}` }, 502);
  }
  const tokens = (await tokenResponse.json()) as { access_token: string };

  const userinfoResponse = await fetch(GOOGLE.userinfoUrl, {
    headers: { Authorization: `Bearer ${tokens.access_token}` },
  });
  if (!userinfoResponse.ok) {
    return c.json({ error: "Failed to fetch Google userinfo" }, 502);
  }
  const profile = (await userinfoResponse.json()) as {
    sub: string;
    email: string;
    name?: string;
    picture?: string;
  };

  const db = getDb(c.env.DB);
  const [existing] = await db
    .select()
    .from(users)
    .where(and(eq(users.oauthProvider, "google"), eq(users.oauthSubject, profile.sub)))
    .limit(1);

  let userId: string;
  if (existing) {
    userId = existing.id;
    await db
      .update(users)
      .set({ name: profile.name ?? existing.name, avatarUrl: profile.picture ?? existing.avatarUrl })
      .where(eq(users.id, userId));
  } else {
    userId = crypto.randomUUID();
    await db.insert(users).values({
      id: userId,
      email: profile.email,
      name: profile.name ?? profile.email,
      avatarUrl: profile.picture,
      oauthProvider: "google",
      oauthSubject: profile.sub,
      createdAt: new Date(),
    });
  }

  await issueSession(c, userId, profile.email);
  return c.redirect("/");
});

// Local-dev-only bypass so the app is testable without a real Google OAuth
// app registered. Gated on ENVIRONMENT so it can never be reachable once
// deployed — see wrangler.toml's [vars] (only "development" locally; a real
// deploy's `[env.production]` should set this to something else).
auth.get("/dev-login", async (c) => {
  if (c.env.ENVIRONMENT !== "development") return c.notFound();

  const db = getDb(c.env.DB);
  const email = "dev@example.com";
  const [existing] = await db.select().from(users).where(eq(users.email, email)).limit(1);
  const userId = existing?.id ?? crypto.randomUUID();
  if (!existing) {
    await db.insert(users).values({
      id: userId,
      email,
      name: "Dev User",
      oauthProvider: "dev",
      oauthSubject: "dev",
      createdAt: new Date(),
    });
  }
  await issueSession(c, userId, email);
  return c.redirect("/");
});

auth.post("/logout", (c) => {
  clearSession(c);
  return c.json({ ok: true });
});

auth.get("/me", requireAuth, async (c) => {
  const session = c.get("session");
  const db = getDb(c.env.DB);
  const [user] = await db.select().from(users).where(eq(users.id, session.sub)).limit(1);
  if (!user) return c.json({ error: "User not found" }, 404);
  return c.json({ id: user.id, email: user.email, name: user.name, avatarUrl: user.avatarUrl });
});

export default auth;

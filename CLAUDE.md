# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Maintenance Rules
- This CLAUDE.md is a living document. After any major architectural change, refactor, or new convention, update the relevant sections immediately.
- When I say "update CLAUDE.md", revise only the changed parts and keep the file concise.
- **Self-update on every commit**: after each `git commit`, review whether the commit changed architecture, conventions, or build/network/platform wiring; if so, update the relevant CLAUDE.md sections in the same change. A `PostToolUse` hook in `.claude/settings.json` injects this reminder after commits run inside Claude Code.

## Project Overview

**DeconstructChinese** — a language-learning translation web app: translate text via an OpenAI-compatible LLM provider (Gemini; Qwen is temporarily out of the chain — see Architecture) and save vocabulary with frequency tracking, per logged-in user. Supports multiple studied language pairs (Chinese, Bahasa Malaysia) behind a single `LanguagePair` abstraction, ported in parallel on both sides — see Architecture below.

Two pieces, one deploy:
- **`backend/`** — Hono on Cloudflare Workers (TypeScript). Owns both provider API keys as Workers secrets (never shipped to the client), gates everything behind Google OAuth + JWT sessions, stores vocab in D1, enforces a per-user daily translate quota.
- **`webapp/`** — React + TypeScript + Vite frontend. Talks to `backend/` only via `/api/*`; no API keys or provider logic live here.

A single `wrangler deploy` from `backend/` ships both: `wrangler.toml`'s `[assets]` binding serves the built `webapp/dist`, and the Worker's `fetch` handler owns `/api/*` — same origin, no CORS.

**Archived**: this project used to also ship native Android/iOS/Desktop apps as a Kotlin Multiplatform (KMP) Compose project, each bundling its own API key and storing vocab locally (no accounts). That code has been moved off `main`/feature branches onto the `kmp-archive` branch (unmodified, full history) so this repo can focus on the web app — `git checkout kmp-archive` recovers it if native apps are revisited. Nothing here talks to it; the web app has its own separate accounts/vocab, not synced with the old native local storage.

## Build Commands

```bash
# Backend (Hono on Workers) — local dev at http://localhost:8787
cd backend && npm install && npm run dev

# Frontend (React/Vite) — local dev at http://localhost:5173, proxies /api to :8787
cd webapp && npm install && npm run dev

# Typecheck
cd backend && npx tsc --noEmit
cd webapp && npx tsc --noEmit    # or: npm run build (tsc -b && vite build)

# Deploy (single Worker serves both) — see backend/README.md for first-time
# Cloudflare/D1/Google-OAuth setup
cd webapp && npm run build && cd ../backend && npx wrangler deploy
```

## Architecture

### API Surface

- `POST /api/translate` — body `{text, languagePairId, toEnglish, useSimplified, includeGrammarNote, checkGrammar}` → `TranslationResult` (translation, phonetic guide, grammar note, optional correction, vocab breakdown).
- `GET /api/translate/stream` (SSE) — the interactive UI's sole translate path: streams the same full-accuracy prompt `POST /api/translate` uses (translation + phonetic guide + grammar note + vocab breakdown), not a cheaper preview. Frontend reads it manually via `fetch()` + `ReadableStream`, deliberately not `EventSource` (see Streaming below).
- `GET /api/vocab` / `POST /api/vocab` / `DELETE /api/vocab/:id` — per-user vocab CRUD, capped at 500 words/user.
- `GET /api/auth/login/:provider`, `GET /api/auth/callback/:provider`, `POST /api/auth/logout`, `GET /api/auth/me` — Google OAuth (PKCE) + session cookie.

### Backend (`backend/`)

`backend/src/index.ts` mounts routes and ends with `app.get("*", c => c.env.ASSETS.fetch(c.req.raw))` as the SPA-fallback catch-all — required even with `not_found_handling = "single-page-application"` configured in `wrangler.toml`, since that setting only takes effect on an explicit `ASSETS.fetch()` call, not automatically ahead of the Worker.

**Ported logic** (kept in sync with the Kotlin source on `kmp-archive` by hand — no shared package, that app is frozen): `backend/src/lib/languagePair.ts` (`LanguagePair` enum: id, `hasScriptVariants`/`hasPhoneticGuide`/`phoneticGuideName`/foreign name — the only place a studied language is named; everything else branches only on these flags), `promptBuilder.ts` (prompt/JSON-schema strings, generic per `languagePair` not hardcoded to Chinese), `scriptConverter.ts` (~400-pair Simplified↔Traditional OpenCC-derived table — authoritative normalization for script-variant pairs, not left to the LLM), `providers.ts` (Qwen/Gemini adapters — deliberately drops the old Kotlin client's sticky-last-good-provider retry ordering and response LRU cache, neither meaningful across stateless per-request Workers). `webapp/src/lib/{types,languagePair}.ts` mirror the API contract for the frontend.

**Provider chain (currently Gemini-only, two models)**: Qwen is temporarily removed from `providersFromEnv` in `backend/src/routes/translate.ts` (its `qwenConfig` factory still exists in `providers.ts`, just unused). `autoSwitchTranslate`/`autoSwitchTranslateStream` try Gemini's primary model (`GEMINI_PRIMARY_MODEL`), then fall back to `GEMINI_FALLBACK_MODEL` only on a *retryable* failure (429, 503, or a connect/headers timeout — `STREAM_TIMEOUT_MS`/`JSON_TIMEOUT_MS`). Any other error surfaces immediately, since a different model wouldn't fix it. A successful fallback carries a generic `notice: RETRY_NOTICE` (never names a model) through `TranslationResult`/`TranslateStreamEvent`, surfaced in `TranslatePage.tsx`.

**Gemini "unsupported location" errors**: Google's Gemini API geo-restricts by the *source IP of the outbound request*, not anything the app sends. Since the Worker calls Gemini directly from `postChat` (`providers.ts`), that source IP is whichever Cloudflare PoP ran the Worker — by default the PoP closest to the requesting user, so a user connecting from a Gemini-unsupported region gets a 400 body containing `"User location is not supported for the API use"`. This isn't retryable (every configured provider/model shares the same Worker network location, so the fallback model fails identically) and there's nothing the user can do about it, so `postChat` matches on that phrase and throws a fixed, friendly `LOCATION_UNSUPPORTED_MESSAGE` ("try again in a little while") instead of the raw Google error text, which flows straight through to `TranslatePage.tsx`'s error display. `wrangler.toml` also sets `[placement] mode = "smart"`, hoping Cloudflare anchors the Worker near its backends (D1, Gemini) rather than near each user — a latency heuristic that may reduce how often this fires, not a geo-compliance guarantee.

**Why a backend at all**: a client-bundled API key is fine for a native app binary but not a public JS bundle, so the backend holds both provider keys as Workers secrets and proxies every translation call, gated by per-user login.

**Auth**: Google OAuth (Authorization Code + PKCE), `backend/src/routes/auth.ts`. Session is a signed JWT (Hono's `hono/jwt`, HS256, `SESSION_SECRET` Workers secret) in an HttpOnly/SameSite=Lax cookie, 30-day expiry (`backend/src/lib/session.ts`). `requireAuth` middleware (`authMiddleware.ts`) gates `/api/translate/*` and `/api/vocab/*`. `GET /api/auth/dev-login` is a passwordless bypass reachable only when `ENVIRONMENT == "development"` (never in a real deploy — see `wrangler.toml`'s `[vars]`), for local testing without a registered Google OAuth client.

**Rate limiting**: `backend/src/lib/rateLimit.ts` enforces a per-user daily translate quota (default 200/day) via a D1 `usage_counters` table, checked before every provider call — the backend fronts one shared paid API key for every user, so this is a hard gate, not a nice-to-have.

**Vocab cap**: 500 words/user, enforced in `backend/src/routes/vocab.ts` against a D1 `vocabulary` table (unique on `user_id, word, language_pair_id`). `POST /api/vocab` bumps frequency on an existing word or inserts a new one (403 past the cap).

**Streaming, one call per translate**: `GET /api/translate/stream` is SSE (Hono's `streamSSE`, read manually via `fetch()` + `ReadableStream` in `client.ts` — not `EventSource`, which would auto-reconnect and silently re-trigger the call once the stream closes). It streams the same full prompt `POST /api/translate` uses (`translateStreamJsonWithProvider` in `providers.ts`: `stream: true` + `response_format: json_object`); both paths share a `shapeResult` helper so foreignText/script-normalization logic lives in one place. Each payload is a `TranslateStreamEvent`: `{ delta }` chunks of the model's in-progress JSON, an optional `{ notice }` on fallback-model retry, then one final `{ result }` — the authoritative `TranslationResult`.

This replaced an older two-call pipeline (a cheap preview, then a full breakdown call) that doubled tokens/quota per translate and risked the two disagreeing. `webapp/src/lib/streamingJson.ts` best-effort-extracts fields from the growing `{ delta }` buffer for optimistic rendering (`vocabLoading: true`); the final `{ result }` event replaces state with the authoritative value. `retry()` re-runs the whole call.

**On-device preview**: `webapp/src/lib/browserTranslate.ts` optionally fires Chrome's on-device `Translator` API in parallel, purely as an instant, free, cosmetic placeholder until the first real `{ delta }` arrives — feature-detected, and skipped for language pairs with no BCP-47 mapping (e.g. Bahasa Malaysia today).

**Local dev**: `backend/.dev.vars` (gitignored) sets `ENVIRONMENT=development` (flips cookies to non-`Secure`, since browsers drop `Secure` cookies over plain HTTP even on `127.0.0.1`) plus provider/OAuth keys — dummy values are fine for testing everything except real translation content. See `backend/README.md` for first-time Cloudflare/D1/Google-OAuth setup and deploy steps.

**Custom domain + `run_worker_first` gotcha**: production is served at `deconstruct-vocab.xyz` via `wrangler.toml`'s `routes = [{ pattern = "deconstruct-vocab.xyz", custom_domain = true }]` (requires the domain's nameservers to be delegated to Cloudflare first). Adding an explicit `routes` entry silently disables the `workers.dev` fallback URL — keep `workers_dev = true` alongside it if you still want that URL live. Critically, `[assets].run_worker_first = ["/api/*"]` is also required: real browser top-level navigations (`Sec-Fetch-Mode: navigate` — a link click or an OAuth redirect, as opposed to `fetch()`/XHR) are matched against static assets *before* the Worker runs, so without this, `not_found_handling = "single-page-application"` serves `index.html` for `/api/auth/login/...` and `/api/auth/callback/...` instead of ever invoking the Worker — looks exactly like a login button silently doing nothing. Invisible in `wrangler dev --local` (Miniflare doesn't reproduce this precedence), only shows up on a real edge deploy.

### Frontend (`webapp/`)

`App.tsx` owns top-level state (language pair, script preference, direction, auth) and renders `LoginPage`/`TranslatePage`/`VocabularyPage`. `api/client.ts` is the sole fetch wrapper — every backend call goes through it, `credentials: "include"` for the session cookie.

**Preferences & responsive layout**: last-used `languagePairId`/`useSimplified`/`toEnglish` persist to `localStorage` (per-browser, not per-account — deliberately not round-tripped through the backend since it's a low-stakes UI preference) and restore on load. Above a 900px viewport, Translate and Saved render side by side as a CSS grid instead of tab-switched (`index.css`'s `.app-content`/`.panel` rules) — both panels stay mounted at all times so switching tabs on mobile doesn't reset in-progress state.

**Grammar correction**: a "Check my `<language>` for mistakes" toggle (`checkGrammar` param) is only offered when translating *from* the studied language *to* English (i.e., the user typed the sentence themselves). When on, `buildPromptToEnglish` asks the model for one extra `correction` JSON field alongside the existing breakdown call — no second API call, small output-token cost.

**Native browser APIs**: `audio/speech.ts` uses `SpeechSynthesis` for TTS and `audio/useSpeechRecognition.ts` uses `SpeechRecognition`/`webkitSpeechRecognition` for voice input — real support the old KMP web target never had (it only had empty stubs).

**Visual design system** (`index.css`): ink-on-paper palette (`--paper`/`--ink`/`--accent`/`--mark`, light+dark via `prefers-color-scheme`) with Fraunces (display) and IBM Plex Sans/Mono loaded from Google Fonts in `index.html` — Plex Mono is reserved specifically for phonetic transcriptions and frequency counts, not general UI. `.frame` is the one shared boundary convention (hairline border, no fill/shadow) applied to every grouped read-only surface (login card, header language switcher, translate result, saved list); the composer is the deliberate exception, staying filled since it's the one thing you type into. The vocabulary breakdown under a translation and the saved-words list both render as the same one-row-per-word "lexicon" list (`.vocab-item`/`.vocab-item-main`) rather than separate stylings, so a word looks the same whether it's freshly translated or being looked up again.

## Key Design Decisions

1. **No provider is structurally privileged**: adding a provider means one new `ProviderConfig` factory in `backend/src/lib/providers.ts` (label, baseUrl, model, optional `disableThinking`/`reasoningEffort`) and adding it to the ordered list `providersFromEnv` builds in `backend/src/routes/translate.ts`, which `autoSwitchTranslate`/`autoSwitchTranslateStream` try in order — currently that list is Gemini-only (two models, see Provider chain above); Qwen's factory still exists but isn't in the list.

2. **No studied language is structurally privileged**: `LanguagePair` (`backend/src/lib/languagePair.ts`, mirrored in `webapp/src/lib/languagePair.ts`) is the only place "Chinese"/"Malay" mean anything; prompt building, script conversion, and every UI component below it branch only on the enum's flags, never on the language itself. Adding a pair means one new entry in both — nothing else changes.

3. **Vocab identity**: matched by `(user_id, word, languagePairId)` — scopes frequency tracking to the studied pair and lets identical-looking words in different pairs coexist without collision for the same user.

4. **Stateless-Worker simplifications**: the backend deliberately does not replicate the old native client's sticky-last-good-provider retry ordering or response memoization/LRU cache — neither is meaningful across independent per-request Workers with no shared in-memory state. A response cache could be added later via KV if repeat-translation volume justifies it.

## Notes

- **No strict null safety for API responses**: lenient JSON parsing (`providers.ts`); malformed responses aren't fatal. Markdown fences (` ```json ` / ` ``` `) are stripped before parsing.
- **`QWEN_API_KEY`/`GEMINI_API_KEY` are both optional** on `Bindings` (`backend/src/env.ts`) — `autoSwitchTranslate` skips whichever key is blank/unset rather than crashing (currently only `GEMINI_API_KEY` is read, since Qwen is out of the active chain).

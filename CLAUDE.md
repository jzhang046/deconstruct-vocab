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

**Provider chain (currently Gemini-only, two models)**: Qwen is temporarily removed from `providersFromEnv` in `backend/src/routes/translate.ts` (its `qwenConfig` factory is still defined in `providers.ts`, just unused — trivial to re-add). In its place, `autoSwitchTranslate`/`autoSwitchTranslateStream` try Gemini's primary model (`GEMINI_PRIMARY_MODEL` = `gemini-3.5-flash-lite`) then, only on a *retryable* failure — HTTP 429, HTTP 503, or a request timeout (covering just the connect/headers phase, so an already-flowing SSE stream is never aborted mid-read — `STREAM_TIMEOUT_MS` = 12s, used by the streaming path that now backs the interactive UI; `JSON_TIMEOUT_MS` = 20s for the standalone non-streaming `POST /api/translate`, since for a non-streaming completion "headers arrive" is close to "generation finished") — fall back to `GEMINI_FALLBACK_MODEL` (`gemini-3.1-flash-lite`). Any other error (bad request, malformed JSON, etc.) is surfaced immediately without retrying, since a different model wouldn't fix it. A successful fallback attaches `notice: RETRY_NOTICE` to the result (non-stream) or yields a notice-only `TranslateStreamEvent` before restarting the stream (stream; only possible if nothing has streamed yet, since mid-stream model-swapping would visibly overwrite output) — `RETRY_NOTICE` is a generic "service is busy, retrying" string that deliberately never names a model, mirrored through `TranslationResult.notice`/`TranslateStreamEvent` in both `backend/src/types.ts` and `webapp/src/lib/types.ts` and surfaced in `TranslatePage.tsx`.

**Why a backend at all**: a client-bundled API key is fine for a native app binary but not a public JS bundle, so the backend holds both provider keys as Workers secrets and proxies every translation call, gated by per-user login.

**Auth**: Google OAuth (Authorization Code + PKCE), `backend/src/routes/auth.ts`. Session is a signed JWT (Hono's `hono/jwt`, HS256, `SESSION_SECRET` Workers secret) in an HttpOnly/SameSite=Lax cookie, 30-day expiry (`backend/src/lib/session.ts`). `requireAuth` middleware (`authMiddleware.ts`) gates `/api/translate/*` and `/api/vocab/*`. `GET /api/auth/dev-login` is a passwordless bypass reachable only when `ENVIRONMENT == "development"` (never in a real deploy — see `wrangler.toml`'s `[vars]`), for local testing without a registered Google OAuth client.

**Rate limiting**: `backend/src/lib/rateLimit.ts` enforces a per-user daily translate quota (default 200/day) via a D1 `usage_counters` table, checked before every provider call — the backend fronts one shared paid API key for every user, so this is a hard gate, not a nice-to-have.

**Vocab cap**: 500 words/user, enforced in `backend/src/routes/vocab.ts` against a D1 `vocabulary` table (unique on `user_id, word, language_pair_id`). `POST /api/vocab` bumps frequency on an existing word or inserts a new one (403 past the cap).

**Streaming**: `GET /api/translate/stream` is SSE (Hono's `streamSSE`). The frontend deliberately does NOT use `EventSource` — it auto-reconnects when the server closes the stream (which it always does once translation finishes), silently re-triggering the call. `webapp/src/api/client.ts`'s `streamTranslate()` reads the SSE response manually via `fetch()` + `ReadableStream` instead. Each SSE `data:` payload is a `TranslateStreamEvent` (`backend/src/types.ts`, mirrored in `webapp/src/lib/types.ts`): zero or more `{ delta }` events carrying raw fragments of the model's in-progress JSON (from `translateStreamJsonWithProvider` in `providers.ts`, which requests `stream: true` + `response_format: json_object` together against the same full prompt `translateWithProvider` uses — both funnel through a shared `shapeResult` helper so foreignText/script-normalization logic lives in exactly one place), an optional `{ notice }` on fallback-model retry, then exactly one final `{ result }` carrying the authoritative shaped `TranslationResult`, parsed server-side once the full response has accumulated.

**Single streamed call (was: two-phase translation)**: `webapp/src/hooks/useTranslate.ts` used to mirror the old native app's two-stage pipeline — a cheap preview call, then a second full call for the per-word breakdown — which cost double the input tokens and quota per translate action, and risked the preview and final translation disagreeing (the preview used a much simpler prompt than the full breakdown, so it was sometimes measurably less accurate). Replaced with one `streamTranslate` call: `webapp/src/lib/streamingJson.ts` incrementally extracts `translatedText`/`phoneticText`/`vocabulary` items from the growing `{ delta }` buffer for optimistic rendering (best-effort only — schema-shaped regex/bracket-matching, not a general JSON parser) while `vocabLoading: true`; the final `{ result }` event replaces state wholesale with the authoritative value and `vocabLoading: false`. If the stream errors after at least one delta arrived, the last optimistic state is kept with `vocabError: true` (`retry()` re-runs the whole call — there's no cheaper "just the vocab" retry anymore, since there's only one call now).

**On-device preview**: `webapp/src/lib/browserTranslate.ts` fires Chrome's on-device `Translator` API (`self.Translator`, not a web standard) in parallel with the real request, purely as an instant, free, cosmetic preview shown until the first real `{ delta }` arrives — feature-detected, checks `availability()` before use (never triggers a model download), and only offers it for language pairs/directions it maps to a supported BCP-47 code (`toBcp47` in that file; e.g. Bahasa Malaysia currently has no mapping and always skips the preview). Never a substitute for the real result — it has no notion of a phonetic guide or vocab breakdown.

**Local dev**: `backend/.dev.vars` (gitignored) sets `ENVIRONMENT=development` (flips cookies to non-`Secure`, since browsers drop `Secure` cookies over plain HTTP even on `127.0.0.1`) plus provider/OAuth keys — dummy values are fine for testing everything except real translation content. See `backend/README.md` for first-time Cloudflare/D1/Google-OAuth setup and deploy steps.

**Custom domain + `run_worker_first` gotcha**: production is served at `deconstruct-vocab.xyz` via `wrangler.toml`'s `routes = [{ pattern = "deconstruct-vocab.xyz", custom_domain = true }]` (requires the domain's nameservers to be delegated to Cloudflare first). Adding an explicit `routes` entry silently disables the `workers.dev` fallback URL — keep `workers_dev = true` alongside it if you still want that URL live. Critically, `[assets].run_worker_first = ["/api/*"]` is also required: real browser top-level navigations (`Sec-Fetch-Mode: navigate` — a link click or an OAuth redirect, as opposed to `fetch()`/XHR) are matched against static assets *before* the Worker runs, so without this, `not_found_handling = "single-page-application"` serves `index.html` for `/api/auth/login/...` and `/api/auth/callback/...` instead of ever invoking the Worker — looks exactly like a login button silently doing nothing. Invisible in `wrangler dev --local` (Miniflare doesn't reproduce this precedence), only shows up on a real edge deploy.

### Frontend (`webapp/`)

`App.tsx` owns top-level state (language pair, script preference, direction, auth) and renders `LoginPage`/`TranslatePage`/`VocabularyPage`. `api/client.ts` is the sole fetch wrapper — every backend call goes through it, `credentials: "include"` for the session cookie.

**Preferences & responsive layout**: last-used `languagePairId`/`useSimplified`/`toEnglish` persist to `localStorage` (per-browser, not per-account — deliberately not round-tripped through the backend since it's a low-stakes UI preference) and restore on load. Above a 900px viewport, Translate and Saved render side by side as a CSS grid instead of tab-switched (`index.css`'s `.app-content`/`.panel` rules) — both panels stay mounted at all times so switching tabs on mobile doesn't reset in-progress state.

**Grammar correction**: a "Check my `<language>` for mistakes" toggle (`checkGrammar` param) is only offered when translating *from* the studied language *to* English (i.e., the user typed the sentence themselves). When on, `buildPromptToEnglish` asks the model for one extra `correction` JSON field alongside the existing breakdown call — no second API call, small output-token cost.

**Native browser APIs**: `audio/speech.ts` uses `SpeechSynthesis` for TTS and `audio/useSpeechRecognition.ts` uses `SpeechRecognition`/`webkitSpeechRecognition` for voice input — real support the old KMP web target never had (it only had empty stubs).

## Key Design Decisions

1. **No provider is structurally privileged**: adding a provider means one new `ProviderConfig` factory in `backend/src/lib/providers.ts` (label, baseUrl, model, optional `disableThinking`/`reasoningEffort`) and adding it to the ordered list `providersFromEnv` builds in `backend/src/routes/translate.ts`, which `autoSwitchTranslate`/`autoSwitchTranslateStream` try in order — currently that list is Gemini-only (two models, see Provider chain above); Qwen's factory still exists but isn't in the list.

2. **No studied language is structurally privileged**: `LanguagePair` (`backend/src/lib/languagePair.ts`, mirrored in `webapp/src/lib/languagePair.ts`) is the only place "Chinese"/"Malay" mean anything; prompt building, script conversion, and every UI component below it branch only on the enum's flags, never on the language itself. Adding a pair means one new entry in both — nothing else changes.

3. **Vocab identity**: matched by `(user_id, word, languagePairId)` — scopes frequency tracking to the studied pair and lets identical-looking words in different pairs coexist without collision for the same user.

4. **Stateless-Worker simplifications**: the backend deliberately does not replicate the old native client's sticky-last-good-provider retry ordering or response memoization/LRU cache — neither is meaningful across independent per-request Workers with no shared in-memory state. A response cache could be added later via KV if repeat-translation volume justifies it.

## Notes

- **No strict null safety for API responses**: lenient JSON parsing (`providers.ts`); malformed responses aren't fatal. Markdown fences (` ```json ` / ` ``` `) are stripped before parsing.
- **`QWEN_API_KEY`/`GEMINI_API_KEY` are both optional** on `Bindings` (`backend/src/env.ts`) — `autoSwitchTranslate` skips whichever key is blank/unset rather than crashing (currently only `GEMINI_API_KEY` is read, since Qwen is out of the active chain).

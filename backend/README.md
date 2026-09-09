# Backend (Cloudflare Worker)

Hono API + Google OAuth + D1-backed vocabulary storage. Also serves the built
`webapp/` React SPA as static assets — one Worker, one deploy, no CORS.

## Local dev

```bash
cd backend
npm install
cp .dev.vars.example .dev.vars   # fill in real or dummy values, see below
npx wrangler d1 migrations apply deconstruct-db --local
npm run dev                      # http://localhost:8787
```

`webapp/dist` must exist for the Worker to boot (the `[assets]` binding in
`wrangler.toml` requires the directory to be present even if empty) — run
`npm run build` in `webapp/` at least once first.

For `.dev.vars`, `QWEN_API_KEY`/`GEMINI_API_KEY` can be dummy values if you're
only testing auth/vocab (translate calls will just fail with a provider
error). `SESSION_SECRET` can be any string locally. `GOOGLE_CLIENT_ID`/
`GOOGLE_CLIENT_SECRET` need a real Google Cloud OAuth client (see below) to
exercise the real login flow — otherwise use `GET /api/auth/dev-login`, a
bypass that's only reachable when `ENVIRONMENT == "development"`.

## First-time Cloudflare setup (once per environment)

```bash
npx wrangler login
npx wrangler d1 create deconstruct-db   # copy the returned database_id into wrangler.toml
npx wrangler d1 migrations apply deconstruct-db --remote
npx wrangler secret put QWEN_API_KEY
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put SESSION_SECRET       # any long random string
npx wrangler secret put GOOGLE_CLIENT_ID
npx wrangler secret put GOOGLE_CLIENT_SECRET
```

### Google OAuth client

In Google Cloud Console → APIs & Services → Credentials → Create OAuth client
ID (Web application):
- Authorized redirect URI: `https://<your-worker-domain>/api/auth/callback/google`
  (and `http://127.0.0.1:8787/api/auth/callback/google` for local testing)

## Deploy

```bash
cd ../webapp && npm run build   # produces webapp/dist, which wrangler.toml points at
cd ../backend && npx wrangler deploy
```

`wrangler.toml`'s `[vars] ENVIRONMENT = "production"` is what a bare `wrangler
deploy` ships with — it gates both the `Secure` cookie flag and the
`/api/auth/dev-login` bypass, both off by default. Local dev only sees
`ENVIRONMENT=development` because `.dev.vars` overrides it (and `.dev.vars`
is never read by `deploy`, only by `wrangler dev`).

CI: `.github/workflows/deploy-web.yml` runs this on every push to `main`
(needs `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` repo secrets).

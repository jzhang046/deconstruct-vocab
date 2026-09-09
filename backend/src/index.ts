import { Hono } from "hono";
import type { Bindings } from "./env";
import type { AuthedVariables } from "./lib/authMiddleware";
import auth from "./routes/auth";
import translate from "./routes/translate";
import vocab from "./routes/vocab";

const app = new Hono<{ Bindings: Bindings; Variables: AuthedVariables }>();

app.get("/api/health", (c) => c.json({ ok: true, env: c.env.ENVIRONMENT }));

app.route("/api/auth", auth);
app.route("/api/translate", translate);
app.route("/api/vocab", vocab);

// Everything else falls through to the static SPA build (webapp/dist). The
// [assets] binding's `not_found_handling = "single-page-application"` only
// takes effect when explicitly called like this — an unmatched path isn't
// auto-resolved to index.html before the Worker runs.
app.get("*", (c) => c.env.ASSETS.fetch(c.req.raw));

export default app;

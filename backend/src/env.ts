export type Bindings = {
  DB: D1Database;
  ASSETS: Fetcher;
  ENVIRONMENT: string;
  // Optional by design — a deployment can run with just one provider
  // configured; autoSwitchTranslate skips whichever key is blank/unset.
  QWEN_API_KEY?: string;
  GEMINI_API_KEY?: string;
  SESSION_SECRET: string;
  GOOGLE_CLIENT_ID: string;
  GOOGLE_CLIENT_SECRET: string;
};

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Backend runs as a separate `wrangler dev` process in local dev
    // (see ../backend); production serves both from one Worker, so no
    // proxy/CORS config is needed there.
    proxy: {
      "/api": "http://127.0.0.1:8787",
    },
  },
});
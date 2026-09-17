import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// In development the API is proxied, so the dashboard and API share an origin
// and no CORS round trip is needed. In production VITE_API_BASE_URL points at
// the deployed API, which allows this origin explicitly.
//
// F4ALL_API_URL overrides the proxy target for a backend on another port.
const apiTarget = process.env.F4ALL_API_URL ?? "http://localhost:8000";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": apiTarget,
      "/health": apiTarget,
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});

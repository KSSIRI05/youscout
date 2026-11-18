import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Port fixe : il doit correspondre aux redirections du client Keycloak youscout-admin et au CORS de la Gateway.
export default defineConfig({
  plugins: [react()],
  server: { port: 7358, strictPort: true, host: "localhost" },
  preview: { port: 7358, strictPort: true, host: "localhost" },
  test: { environment: "jsdom", globals: true, restoreMocks: true, setupFiles: ["src/setupTests.ts"] },
});

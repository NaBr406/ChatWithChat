import { defineConfig } from "@playwright/test";
export default defineConfig({ testDir: "./tests", timeout: 30000, use: { baseURL: "http://127.0.0.1:4178", viewport: { width: 390, height: 844 } }, webServer: { command: "node scripts/serve.mjs", url: "http://127.0.0.1:4178", reuseExistingServer: true } });

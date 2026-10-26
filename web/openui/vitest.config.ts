import { defineConfig } from "vitest/config";
import { fileURLToPath, URL } from "node:url";
// Upstream date assertions explicitly expect UTC; UI date helpers retain device local time.
process.env.TZ = "UTC";
export default defineConfig({ resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } }, test: { include: ["src/**/*.test.ts"] } });

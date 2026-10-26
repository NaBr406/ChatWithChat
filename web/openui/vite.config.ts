import { defineConfig } from "vite";
import { fileURLToPath, URL } from "node:url";
export default defineConfig({
 base: "./",
 resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
 build: { outDir: "../../app/src/main/assets/openui", emptyOutDir: true, target: "chrome101", assetsInlineLimit: 0, rollupOptions: { input: { index: fileURLToPath(new URL("./index.html", import.meta.url)), gallery: fileURLToPath(new URL("./gallery.html", import.meta.url)) } } },
});

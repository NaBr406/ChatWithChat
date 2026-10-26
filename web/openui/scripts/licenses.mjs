import { readFile, readdir, copyFile, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
const out = path.resolve("../../app/src/main/assets/openui");
await mkdir(path.join(out, "licenses"), { recursive: true });
await copyFile("LICENSE.upstream", path.join(out, "licenses/Open-Intelligent-UI.txt"));
const lock = JSON.parse(await readFile("package-lock.json", "utf8"));
const rows = [];
for (const [directory, item] of Object.entries(lock.packages)) {
 if (!directory || item.dev) continue;
 const packageDir = path.resolve(directory);
 const metadata = JSON.parse(await readFile(path.join(packageDir, "package.json"), "utf8"));
 const name = metadata.name;
 const filenames = (await readdir(packageDir)).filter(name => /^licen[sc]e(?:\.|$)|^copying(?:\.|$)/i.test(name));
 for (const file of filenames) await copyFile(path.join(packageDir, file), path.join(out, "licenses", `${name.replaceAll("/", "_")}-${file}`));
 rows.push({ name, version: metadata.version, license: metadata.license ?? item.license ?? "See upstream package", files: filenames });
}
await writeFile(path.join(out, "THIRD_PARTY_NOTICES.json"), JSON.stringify({ upstream: { repository: "https://github.com/thesysdev/open-intelligent-ui", commit: "03213439358bf3408ad61a46ec9e3230c5572e48", license: "MIT" }, packages: rows }, null, 2) + "\n");

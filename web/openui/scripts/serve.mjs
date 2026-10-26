import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
const root = path.resolve("../../app/src/main/assets/openui");
const types = {".html":"text/html", ".js":"application/javascript", ".mjs":"application/javascript", ".css":"text/css", ".woff2":"font/woff2", ".woff":"font/woff", ".ttf":"font/ttf", ".json":"application/json"};
createServer(async (req,res) => { try { const name = decodeURIComponent(new URL(req.url,"http://localhost").pathname); const file = path.resolve(root,"."+(name === "/" ? "/index.html" : name)); if (!file.startsWith(root+path.sep)) throw Error(); const data = await readFile(file); res.setHeader("Content-Type",types[path.extname(file)]||"application/octet-stream"); res.end(data); } catch { res.statusCode=404;res.end("Not found"); } }).listen(4178,"127.0.0.1");

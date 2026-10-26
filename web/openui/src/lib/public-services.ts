/** Wikipedia/Commons require origin=* for unauthenticated browser CORS. */
async function pages(host: string, params: Record<string, string>): Promise<Record<string, any>[]> {
 const url = new URL(`https://${host}/w/api.php`);
 url.search = new URLSearchParams({ action: "query", format: "json", formatversion: "2", origin: "*", ...params }).toString();
 const response = await fetch(url, { signal: AbortSignal.timeout(8000), credentials: "omit", referrerPolicy: "no-referrer" });
 if (!response.ok) throw new Error("Image service unavailable");
 const data = await response.json();
 if (data.error) throw new Error("Image service unavailable");
 return data.query?.pages ?? [];
}
export async function searchPublicImages(query: string): Promise<string[]> {
 const q = query.replace(/\s+/g, " ").trim().slice(0, 200);
 if (q.length < 2) return [];
 const results = await Promise.allSettled([
  pages("en.wikipedia.org", { generator: "search", gsrsearch: q, gsrlimit: "5", prop: "pageimages", piprop: "thumbnail", pithumbsize: "960" }),
  pages("commons.wikimedia.org", { generator: "search", gsrsearch: `${q} filetype:bitmap`, gsrnamespace: "6", gsrlimit: "5", prop: "imageinfo", iiprop: "url|mime", iiurlwidth: "960" })
 ]);
 return [...new Set(results.flatMap(r => r.status === "fulfilled" ? r.value.flatMap(page => {
  const info = page.imageinfo?.[0];
  const url = page.thumbnail?.source ?? (info?.mime?.startsWith("image/") ? info.thumburl ?? info.url : undefined);
  return typeof url === "string" && url.startsWith("https://") ? [url] : [];
 }) : []))].slice(0, 6);
}

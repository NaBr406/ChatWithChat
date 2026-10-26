# ChatWithChat OpenUI renderer

This local React/WebView frontend vendors the complete 55-component library from
[`thesysdev/open-intelligent-ui`](https://github.com/thesysdev/open-intelligent-ui)
at commit `03213439358bf3408ad61a46ec9e3230c5572e48` (MIT). The original components,
functions, styles, generated specification and example programs are retained.
`Line` is included in the 55-component registry. This is a client renderer, without
the upstream Next.js chat server, API key management or analytics.

## Build and verify

Use Node.js 24 or later from this directory:

```powershell
npm ci --ignore-scripts
npm test
npm run build
npx playwright install chromium
npm run test:browser
```

The pinned lockfile matches the selected upstream dependency versions. Build runs
TypeScript checking, Vite, then license collection. Generated output replaces only
`app/src/main/assets/openui/`. Fonts, KaTeX, all Lucide icon modules and the MapLibre
worker are local assets. The main bundle and MapLibre emit Vite size warnings;
the full icon catalog is deliberately retained. `gallery.html` is a diagnostic
gallery with clearly separate demo data tools, not the production answer runtime.

`LICENSE.upstream` preserves the original MIT attribution. Each build collects
production dependency license files in `licenses/` and records versions in
`THIRD_PARTY_NOTICES.json` beside the Android assets.

## Native contract and state

The host calls `window.OpenUiHost.update({identity,revision,source,streaming,
interactive,theme,format?})` and `window.OpenUiHost.setPaused(boolean)`. Formats
are `openui` (default) and optional `markdown`. Source is limited to 1,048,576
characters. Older revisions for the same identity are rejected.

One React root survives appended source chunks and stream completion. Rollback,
replacement and a new identity reset component state. `Set` and `Reset` update
local state. `ToAssistant` requests a native draft with current explicit context
and form values; it never sends a chat message. HTTP(S) links and clipboard copy
request native handling. Values above 32,768 characters are rejected. No action
is admitted while streaming, noninteractive or paused.

Native messages use `NativeOpenUi.postMessage(JSON.stringify(message))` with
`ready`, `height`, `action` and `selection` types. Heights are CSS pixels measured
with ResizeObserver, document fonts and image-load updates. Android must expose
the bridge only to the main trusted appassets origin; this frontend does not use
an unrestricted JavaScriptInterface. Historical Markdown can remain native Compose.

Controls, portals, map targets and iframes become inert while read-only/paused;
capture guards also reject synthetic actions. Text outside controls remains
selectable. Timer ticking suspends without discarding state. HtmlView locally
suspends timeout/interval/RAF callbacks and freezes `Date.now()` across pauses.
This does not globally pause timers in other WebViews.

Popover opens in document flow so Android's content-height WebView includes the
panel and its controls. Map expansion stays in flow with a bounded 400–600 px
height, rather than a fixed overlay sized to a potentially very long answer.
These are deliberate embedded-host adaptations to the upstream desktop layout.

## HtmlView and network behavior

HtmlView navigates a separate `sandbox.html` bootstrap with only `allow-scripts`,
an opaque origin, and a restrictive CSP. Inline game/canvas code works; external
scripts, fetch, nested frames, form submission, popups and HTTPS images are denied.
Only `data:` and `blob:` image URLs are allowed inside this sandbox. This restriction
applies to HtmlView only; the regular `Image`/`SearchImage` components and maps keep
their existing host-page network behavior. The bootstrap accepts markup once from its parent, then removes
its listener before writing the document. Height messages require the exact child
window. The child cannot access the native bridge or parent DOM. Source changes
reload its sandbox; lifecycle pause preserves its DOM and local state. Inline code
is not an execution-quota sandbox: an infinite synchronous loop can still stall
that WebView. CSS animation and arbitrary external media behavior are not general
purpose application lifecycle APIs.

SearchImage and Image fallback call Wikipedia/Commons directly with CORS
`origin=*`, bounded search and timeout; unavailable results show a real unavailable
state. Map uses OpenFreeMap vector data, Esri raster fallback and public OSRM
**driving** routes. Failed routing remains a labelled route overview; failed tiles
or unavailable WebGL show a status and preserve the surrounding answer. YouTube
uses youtube-nocookie and needs connectivity. These services are not fabricated
successes and are not offline features. No paid provider key or server is bundled.

## Verification boundary

See [VERIFICATION.md](./VERIFICATION.md) for the test matrix. Desktop Chromium
checks do not prove Android WebView IME, native selection, device WebGL, or physical
scroll behavior. Android instrumentation and device acceptance are separate.

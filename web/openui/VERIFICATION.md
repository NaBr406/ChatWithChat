# Verification matrix

Validated on Windows with the production build served over localhost, not Vite's
development server. The main page's production CSP is therefore exercised.

| Layer | Coverage | Result |
|---|---|---|
| TypeScript + Vite | Local production assets and dependency licenses | Build passes; bundle size warnings remain |
| Vitest | Upstream functions, map utilities, gallery syntax, all 55 registry names/signature ordering, full gallery catalog coverage, host reset/validation | 323 tests pass |
| Browser state | Real Input and Set/Reset; append preserves edited state; rollback/replacement/new identity reset | Pass |
| Browser host actions | Draft contains current Ada input; URL/copy route to native request and URL remains unchanged | Pass |
| Browser read-only | Synthetic control click cannot mutate or emit; controls inert | Pass |
| Browser sandbox | Inline game button works; parent DOM denied; child bridge undefined; malicious postMessage cannot emit native action | Pass |
| Browser sandbox network | Remote HTTPS images are blocked while data images remain renderable inside HtmlView | Pass |
| Browser streaming | Incomplete chunks converge; long Chinese content reports height above 10,000 CSS px; dark ink | Pass |
| Browser long non-streaming text | 264k-character `Text` answer reports large height and preserves plain `FINALTAIL931` sentinel at the tail | Pass |
| Browser catalog smoke | 23 component/composition programs individually in production; 45 gallery examples including 18 working-tool and 4 prompt programs | Pass; no parser alerts/pageerror after settling |
| Browser lifecycle | Real Timer and sandbox interval stop, preserve state, resume; child inert during pause | Pass |
| Browser controls | Checkbox, Select, RadioGroup, SegmentedControl, Slider keyboard, DatePicker, TextArea and Flashcard | Covered by browser suite |
| Browser WebGL failure | Forced unavailable WebGL preserves answer and reports map error | Covered by browser suite |
| Browser embedded overlays | 36px initial Popover viewport expands/collapses measured height; Map expansion bounded even in 20,000px answer viewport | Covered by browser suite |

Gallery smoke checks are rendering coverage, not proof that every interaction of
every component is exhaustively exercised. Demo tools exist only in the diagnostic
gallery. Product programs do not silently receive mock data or fake tool successes.
External URLs are deliberately aborted in gallery smoke checks to make those
checks independent of public service uptime.

Separate live desktop HTTP probes returned status 200 on 2026-10-26 local session
(all API/tile endpoints sent `Access-Control-Allow-Origin: *`):

| Service | Live result |
|---|---|
| Wikipedia query API, `red panda` with pageimages | Valid JSON and a thumbnail URL |
| Returned `thumb.wikimedia.org` red panda JPEG | 200, image/jpeg, 167,159 bytes |
| Commons query API, bitmap namespace search | 200, JSON, 1,568 bytes |
| OSRM Berlin driving route | 200, route GeoJSON response, 3,542 bytes |
| OpenFreeMap `/planet` vector source | 200, JSON, 19,254 bytes |
| OpenFreeMap `/styles/liberty` | 200, JSON, 43,079 bytes |
| Esri World_Street_Map tile `/1/0/0` | 200, image/jpeg, 23,019 bytes |

These probes demonstrate service reachability from the development host at that
time. They do not verify Android network reachability, Android WebGL rendering,
YouTube playback, or future third-party availability. Device tests are reported by
the Android host work separately.

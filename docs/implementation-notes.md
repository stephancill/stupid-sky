# Implementation notes

Why stupid sky is built the way it is. **Read this before changing code**, and **add an entry when you make a
decision another contributor would otherwise have to rediscover**: a trade-off, a dead end, a measured
limit, a workaround, or a reason not to do the obvious thing. Code comments say *what* a piece of code
handles; this file records *why*, across files.

## How to add an entry

Add new entries at the top of the log, newest first. Keep each one short and factual:

```md
### YYYY-MM-DD — Short title
**Context:** what problem or observation prompted this.
**Decision:** what was done (name files/functions).
**Why / alternatives:** why this over the obvious options; what was tried and failed.
**Watch out:** anything that will bite the next person (optional).
```

If a later change reverses an earlier decision, add a new entry and mark the old one
`(superseded by …)` instead of deleting it.

---

## Log

### 2026-10-15 — App reopens where the map was left
**Context:** The owner wanted closing and reopening the app to keep the map position.
**Decision:** The camera was already saved on every camera idle (`camera` SharedPreferences in `MapHost`), but
`MainActivity` centred on the device at every launch. Now it only does so when nothing is saved
(`hasSavedCamera`), i.e. first launch, which is also when the location permission is first asked for.
The "my location" button is unchanged. Selection (aircraft/airport) isn't restored.
**Supersedes:** "start on the user's location" in "No requests on pan; refresh the loaded area" (first launch only now).

### 2026-10-15 — Zoomed-out views load as tiles, once, on request
**Context:** Viewing all of South Africa showed ~15 aircraft; FlightRadar24 showed 45 at the same moment. Both
networks cap an area query at 250 nm, so a zoomed-out view only loaded a circle around its centre.
Measured the same evening: adsb.lol across 7 circles covering South Africa had 23 of those aircraft, OpenSky
added 6 more (29 of 45). Most of the rest were on the ground at JNB or cruising where no community feeder hears
them, so it's coverage, not something the app can fix with these sources.
**Decision:** `Viewport.radiusNm` is no longer capped; a view over 250 nm is `wide`. `Tiles.cover` covers its
box with up to 24 circles (centre first; the centre is kept and the rest cropped when more would be needed) and
`FlightData.areas` queries them one after another through the throttle, publishing as each tile arrives. A wide
area is loaded once and **not polled** (owner's choice), and a "Not live when zoomed out · Refresh" prompt shows
straight away. OpenSky gap-fill also runs once for a wide area, with a box up to 20°×20° (4 credits) instead of 5°.
**Why / alternatives:** Tiling at the normal 10 s poll would multiply requests ~20× and break the etiquette rule;
polling slower was offered and rejected in favour of manual refresh.
**Watch out:** A portrait phone showing all of South Africa needs ~21 circles (~25 s to fill in at ~1 s per tile).

### 2026-10-15 — Search covers airports and places
**Context:** The owner wanted to search for locations and airports, not only aircraft.
**Decision:** Typing shows suggestions: airports from the bundled `airports.geojson` (`Airports.search`, instant
and offline; exact IATA/ICAO code first, then word-prefix matches on name/city, bigger airports first) and places
from Photon (`Places.search`, debounced 400 ms, 3+ characters). Enter tries an exact airport code, then the
existing aircraft search, then the top suggestion. Picking an airport opens its panel at zoom 11; a place fits its
extent only when it's wider than 0.1°, otherwise centres at zoom 10, so a building doesn't zoom to street level.
**Why / alternatives:** Nominatim's usage policy forbids autocomplete; Photon (komoot, OSM data) allows it under
fair use and needs no key. adsbdb has no airport search.
**Watch out:** Photon often returns a node and a way for the same place; `Places.parse` dedupes them (duplicate
LazyColumn keys crashed the app before). Photon replies took ~5 s on the emulator.

### 2026-10-15 — Detail sheet with drag stops
**Context:** The owner asked for a dynamically adjustable detail sheet with stops and interruptible animations.
The earlier button-only panel used `animateContentSize`, which a drag can't interrupt.
**Decision:** `DetailSheet` (aircraft, airport and METAR panels) is a fixed-height Surface positioned by a
foundation `AnchoredDraggableState<SheetStop>` with Hidden / Peek / Half / Full anchors. Peek ends at the
panel header (measured via a `headerModifier` the panel applies), Half is 50% of the screen, Full fits the
content but stops below the settings/search row. Half is dropped when it's within 48 dp of Peek or Full.
The handle drags via `anchoredDraggable`; the content drags the sheet through a `NestedScrollConnection`
(push up expands before scrolling; pull down at the top collapses), and releases settle with `pickStop`
(nearest stop when slow, next stop in the fling direction when fast). The chevron and close button call
`animateTo`, so any drag or button press takes over from wherever the sheet is. Settling at Hidden dismisses;
settling at Peek fits the camera to the flight path. The map buttons are positioned from the sheet offset in
the layout phase (no recomposition while dragging) and fade out past the middle of the screen.
**Why / alternatives:** Material3 `BottomSheetScaffold` has only partial/expanded and would put the whole
screen under its scaffold; `ModalBottomSheet` blocks the map. foundation 1.8's `AnchoredDraggableState`
gives arbitrary anchors and mutex-based interruption.
**Watch out:** Until the content is measured the sheet takes the full screen height off-screen, so its children
can lay out; anchors are recomputed whenever the content height changes (e.g. the photo loads).
Verified on the emulator: open at Half, drag to Full, chevron to Peek, drag interrupting the open animation,
fling from content to Peek, fling on the handle to dismiss.

### 2026-10-15 — Callsign routes checked against the flown leg
**Context:** SKW5471 showed SFO → Arcata with a dashed line north while its trail came from Spokane. The
trail was right (GEG → SFO, new-leg flag at takeoff); adsbdb's callsign route was another leg of that callsign.
**Decision:** `Trace.contradictsOrigin` treats the route as wrong when the traced leg starts below 5,000 ft
(a takeoff) more than 50 km from the listed origin. The selected aircraft's panel and collapsed header then
hide the route and say why, and `MapHost` skips the remaining-route line. `UiState.legStart` holds the first
traced point. A leg first seen at cruise (coverage gap) is never judged.
**Why / alternatives:** Destination can't be checked until landing, and routes are keyed by callsign only, so
the takeoff point is the one reliable signal. Map labels and the list still show the adsbdb route, since only
the selected aircraft has a trace.
**Watch out:** Fixture `trace_turnaround.json` is a real capture trimmed to its last two legs.

### 2026-10-15 — Logos keyed by callsign prefix, not by route lookup
**Context:** Logos took 30+ s to fill in: each needed that flight's adsbdb route (one request per callsign at
0.5 s, nearest first, GA callsigns in the same queue).
**Decision:** `Airlines.kt` maps the callsign's ICAO prefix (three letters then a digit) to an IATA code from a
bundled table, `assets/airlines.tsv`, generated from OpenFlights `airlines.dat` by `tools/build_airlines.py`.
`airlineIata()` prefers the resolved route's airline and falls back to the table. Logo loading also starts on
every position refresh, not only after route batches.
**Why / alternatives:** adsbdb `/v0/airline/{icao}` per prefix still shares the throttled adsbdb queue.
OpenFlights airline data is ODbL (credited in the README and Settings) and dated, so the route answer wins.
**Watch out:** Where several airlines share an ICAO code, active ones win, then the lowest OpenFlights id.

### 2026-10-15 — Map controls unified; weather toggles removed
**Context:** The owner found the floating controls inconsistent (tonal circles, filled toggles, square FABs,
a black status pill), the bottom sheet a tinted grey, and asked for the radar and METAR toggles to go.
**Decision:** `MapButton` (48 dp circle, surface colour, primary when active) for every map control; the status
pill uses the same surface and height. Panels use `panelColors()` (plain `surface`, not Card's
`surfaceContainerHighest`), and both `ModalBottomSheet`s pass `containerColor = surface`. The radar/METAR toggle buttons are gone; the airport panel still shows METAR.
**Watch out:** The bottom row is `SpaceBetween` with only the pill weighted (`fill = false`). A second weighted
spacer split the free space with the pill and left a gap on the right.
Panels apply `navigationBarsPadding()` to their content, not the outer column, so the sheet reaches the
bottom edge. The button row pads itself only when no panel is open.

### 2026-10-15 — Airport markers are pins
**Context:** The owner wanted airports to look like map pins rather than circles.
**Decision:** `MapLayers.pinBitmap` draws a teardrop (blue civil, green military) with a white outline and plane;
`LAYER_APT` is a SymbolLayer anchored at the bottom, sized by rank and zoom. It allows overlap and ignores
placement, as the circles did, so pins never hide aircraft labels.
A tap directly on a pin (point query on `LAYER_APT` in `MapHost`) opens the airport before the 24 dp aircraft
box is checked; without that, pins at busy airports were untappable behind parked aircraft.

### 2026-10-15 — Releases are published by GitHub Actions, not by agents
**Context:** Agent sessions can push branches but are refused when creating repositories, tags, releases or
changing repo settings (description, topics).
**Decision:** `.github/workflows/release.yml` builds and publishes. It runs on `v*` tag pushes and on
**Run workflow** with a version input, in which case `gh release create --target $GITHUB_SHA` creates the
tag itself. APK names are fixed (`stupid-sky-arm64.apk`, `stupid-sky-universal.apk`).
**Why / alternatives:** Manual uploads don't scale; the paid upload host used earlier (upload.stupidtech.net,
about $0.07 per build) produced a new URL per build. Fixed asset names make
`releases/latest/download/stupid-sky-arm64.apk` a permanent link.
**Watch out:** CI signs with the runner's debug key, so APKs built locally can't be installed over CI builds
(uninstall first). There's no release signing key yet.
Agents *can* start the workflow: `POST /repos/stephancill/stupid-sky/actions/workflows/release.yml/dispatches`
with `{"ref":"main","inputs":{"tag":"vX.Y.Z"}}` (returns 204), then poll `/actions/runs`. This is how v0.1.0
was published (the run took about 5 minutes).

### 2026-10-15 — Package renamed to `net.stupidtech.sky`; no backwards compatibility
**Context:** Rebrand from "SkyWatch" to "stupid sky".
**Decision:** applicationId/namespace `net.stupidtech.sky`, DataStore file `stupidsky`, project `stupid-sky`.
The old `com.skywatch.tracker` app is not upgraded in place.
**Why:** The owner explicitly chose a clean rename over compatibility at this stage.

### 2026-10-15 — App icon generated from an SVG by script
**Context:** The icon is a loop: solid first half, a filled dot at the midpoint, then dots (from the owner's
sketch). The SVG uses `pathLength`, dash arrays and a mask, which Android VectorDrawables don't support.
**Decision:** `tools/loop-icon.svg` is the source of truth; `tools/build_icon.py` computes real geometry (exact
de Casteljau split at half the arc length, circles for each dot, centre circle) and writes
`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_stat_logo.xml` and `tools/icon-preview.svg`.
Black mark on a white background; the mark stays within the 66dp safe zone (scale 2.4 on the 108dp canvas).
**Watch out:** Edit the SVG and rerun the script; never hand-edit the generated XML. Dot spacing is 5% of the
path length so one dot hides under the centre dot and the last lands on the end.

### 2026-10-15 — Aircraft icons use runtime-generated SDFs for outline and shadow
**Context:** Icons needed a dark outline and a drop shadow for contrast. MapLibre `icon-halo-*` only works
well on true signed-distance-field images; the old plain silhouettes barely showed a halo.
**Decision:** `MapLayers.toSdf()` converts the white silhouettes to SDFs (radius 8, edge at alpha 0.75) using
Tiny-SDF's method: antialiased coverage becomes *squared* sub-pixel offsets. A separate shadow layer draws the
same icon in black, blurred and translated in viewport space.
**Why / alternatives:** A binary inside/outside SDF gave stair-stepped edges; adding offsets to plain
(non-squared) distances gave a lumpy halo. Both were caught by porting the function to Python and rendering a
preview with headless Chromium, which is the way to check visual changes without a device.

### 2026-10-15 — Detail panel: buttons only, no drag (superseded by "Detail sheet with drag stops")
**Context:** The panel collapses to a one-line bar so the flight path is visible (collapsing also fits the
camera to the path and destination).
**Decision:** Minimise/expand via buttons (or tapping the bar). The drag handle and swipe gestures were removed.
**Why / alternatives:** The resize uses `animateContentSize`, which can't be interrupted by a drag, so dragging
felt wrong. A proper draggable sheet would need a rewrite onto Material3 `BottomSheetScaffold` /
anchored-draggable; deferred until it can be tested on a device.

### 2026-10-15 — UI simplifications requested by the owner
**Decision / why:** These are deliberate; don't reintroduce them without asking:
- No "All traffic / Military" toggle. Military aircraft are always shown (green); the list has a Military filter.
- No data-source picker; sources are merged automatically.
- No "hide aircraft on ground" option.
- Search is behind a toolbar button; the bottom-left pill (list icon + count *in view*) opens the list.
- Settings uses a cog; OpenSky lives in a collapsed **Advanced** section.
- The big "Refresh this area" banner appears only after 5 minutes away from the loaded area; the small
  refresh button highlights immediately.

### 2026-10-15 — Airports bundled from OurAirports
**Context:** Show airport markers and a tap-for-details panel without any API.
**Decision:** `tools/build_airports.py` turns OurAirports `airports.csv` (public domain) into
`assets/airports.geojson`: large + medium airports and small ones that have an ICAO/IATA code (~11.6k,
2.4 MB; adds ~0.7 MB to the APK). Shown by rank with zoom (4 / 7 / 9). Military is detected by name.
The airport panel's board is built from tracked aircraft + resolved routes, not schedules.
**Watch out:** Name-based military detection misses some bases (e.g. Langebaanweg, FALW). Free schedule APIs
were rejected (tiny quotas).

### 2026-10-15 — Airline logos fetched at runtime, never bundled
**Context:** The owner wanted airline logos above aircraft.
**Decision:** `Logos.kt` fetches `pics.avs.io/240/120/{IATA}.png` (404 = no logo), draws each onto a white card,
caches per session, and shows it from zoom 8 with collision hiding. Toggle in Settings.
**Why:** Logos are airline trademarks; downloading them at runtime avoids redistributing them in the repo/APK.
The CDN is undocumented and may disappear; the app simply shows no logos then.
**Watch out:** `ConcurrentHashMap` + Kotlin `in` checks values, not keys; use `containsKey` (this bug caused
repeated downloads before it was caught).

### 2026-10-15 — Flown path from adsb.lol trace files
**Context:** Selected aircraft should show the path since takeoff, not just points seen since the app opened.
**Decision:** `Trace.kt` fetches `adsb.lol/data/traces/<last2hex>/trace_full_<hex>.json` (follows the redirect
from `globe.adsb.lol`) and keeps only the current leg: the last row with flags bit 2 (new leg), else a gap
over 30 minutes. Falls back to OpenSky `/tracks/all`. The live trail is appended after the history; a dashed
great-circle line goes to the destination when the route is known.
**Why / alternatives:** `trace_full` covers the whole UTC day (multiple flights). `globe.adsb.fi` traces are
behind a Cloudflare challenge. These trace files are the web map's data, not a documented API.

### 2026-10-15 — Routes resolved per callsign, nearest first
**Context:** The list and map labels show airline and origin → destination.
**Decision:** `Routes.kt` queries adsbdb `/v0/callsign/{cs}` one at a time through the throttle; the ViewModel
resolves the selected aircraft first, then by distance from the map centre, in batches of 5. Airline falls back
to the callsign's ICAO prefix (`/v0/airline/{icao}`). Cached for 6 hours, including "unknown".
**Why / alternatives:** adsb.lol's bulk `POST /api/0/routeset` returns 201 with an empty body. Unknown callsigns
are 404 (cache them); unknown airlines return a plain string (parse error, treat as unknown); network errors
return null and are retried after the next refresh, so the cache never fills with false negatives.
**Watch out:** Routes are keyed by callsign and can be wrong for charters, diversions and military flights.

### 2026-10-15 — Metric units in the UI only
**Decision:** Models keep source units (ft, kt, ft/min, nm); `ui/Units.kt` formats metres (rounded to 10 m),
km/h, m/s and km. Owner's request.

### 2026-10-15 — OpenSky as gap-fill, budgeted to its free credits
**Context:** At Cape Town International (CPT), adsb.lol and adsb.fi had **zero** ground aircraft (no feeder
close enough; ground transmissions need line of sight), while OpenSky had one (SFR311). Only ~8–14 aircraft
were tracked within 250 nm of CPT at all.
**Decision:** `OpenSky.kt` adds aircraft the main feeds lack. Ground positions are kept up to 15 minutes old,
airborne ones only 60 s. Boxes are cropped to 5°×5° (1 credit each). The poll interval spreads the remaining
credits (`X-Rate-Limit-Remaining`) until the reset at midnight UTC, with a minimum of 300 s anonymous / 30 s
with an OAuth client (`auth.opensky-network.org` client credentials). On by default; the toggle and account
fields live under Settings → Advanced.
**Watch out:** The anonymous quota observed from a shared IP was ~200, not the documented 400. Non-commercial
use only. The real fix for local ground coverage is a receiver near the airport.

### 2026-10-15 — No requests on pan; refresh the loaded area
**Context:** Users hit HTTP 429s. Causes: 5 s polling, polling continued in the background, every pan/zoom
fired a request, and the initial view (zoom 5 over Europe) requested the maximum 250 nm radius.
**Decision:** Poll the *loaded area* every 10 s only while the activity is started; panning never triggers a
request ("Refresh this area" instead); start on the user's location at zoom 8.5 (first launch only since
"App reopens where the map was left") (much smaller radius); the
selected aircraft outside the area is looked up only every third poll.
**Why:** Explicit refresh also stops the map reloading while the user is still looking for a place.

### 2026-10-15 — adsb.lol + adsb.fi merged; per-host throttle and per-network cooldown
**Decision:** `FlightData.aggregate()` queries both networks in parallel, merges by hex (freshest `seen_pos`
wins; registration/type/desc/dbFlags filled from the other report). `Throttle` keeps a minimum gap per host;
a 429 puts only that network on cooldown (Retry-After, else 20 s doubling up to 5 min).
**Why / alternatives:** Merging gained a few percent in London and should help more where coverage is thin.
airplanes.live now returns 403 asking API users to email them; ADS-B Exchange's API is paid. adsb.fi's area
endpoint returns `aircraft` instead of `ac`; `alt_baro` can be the string `"ground"`.

### 2026-10-15 — Free data only, no Google Play services
**Context:** The goal was FlightRadar24-style data without decompiling or using paid/private APIs.
**Decision:** Only community/open sources (see the README table). Location uses the platform
`LocationManager` (`UserLocation.kt`), maps use MapLibre + OpenFreeMap, and alerts use WorkManager.
**Why:** Works on phones without Google services and needs no API keys. Planespotters requires a descriptive
User-Agent with a contact URL (`CONTACT` build field) and photographer attribution.

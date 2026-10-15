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

### 2026-10-15 — Detail panel: buttons only, no drag
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
request ("Refresh this area" instead); start on the user's location at zoom 8.5 (much smaller radius); the
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

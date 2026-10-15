# AGENTS.md

Guide for AI agents (and humans) contributing to **stupid sky**, a free Android flight tracker built only on
community and open data. Read this before changing code.

## Before you start and when you finish

1. **Read [`docs/implementation-notes.md`](docs/implementation-notes.md) before making changes.** It records
   why things are built the way they are: dead ends that were already tried, measured API limits, and choices
   the owner made deliberately. Skipping it is how the same mistakes get made twice.
2. **Update it when you make a change that involves a decision**: a trade-off, a workaround, a rejected
   alternative, a limit you measured, or a reason not to do the obvious thing. Add a dated entry at the top
   of the log using the template in that file. If you reverse an earlier decision, add a new entry and mark
   the old one superseded instead of deleting it. Pure refactors and small fixes don't need an entry.

## What the app is

- Live map of aircraft from community ADS-B networks, with military aircraft unfiltered.
- Per-aircraft details: airline, route, photo, logo, flown path and remaining route.
- Airports (with METAR), aircraft list, watchlist alerts.
- **No paid APIs, no API keys, no Google Play services.** Keep it that way.
- Package / applicationId: `net.stupidtech.sky`. App name: "stupid sky" (lowercase).

## Build, test, release

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35). Gradle 8.14.3 via the wrapper.
Put `sdk.dir=/path/to/android-sdk` in `local.properties` (gitignored) or set `ANDROID_HOME`.

```sh
./gradlew assembleDebug        # APKs in app/build/outputs/apk/debug/ (per-ABI + universal)
./gradlew testDebugUnitTest    # JVM unit tests; must pass before you commit
```

- There are no instrumented/UI tests and no emulator in CI. UI changes can only be verified on a device or
  by rendering previews; say so plainly when you couldn't run something.
- Releases: GitHub Actions (`.github/workflows/release.yml`) tests, builds and publishes on a `v*` tag push,
  or via **Actions → Release → Run workflow** with a version (it creates the tag from the selected branch).
  APK names are fixed (`stupid-sky-arm64.apk`, `stupid-sky-universal.apk`) so
  `releases/latest/download/stupid-sky-arm64.apk` always points at the newest build. Don't rename them.
- Release builds are debug-signed by the CI runner. Bump `versionCode`/`versionName` in
  `app/build.gradle.kts` for real version changes.

## Layout

```
app/src/main/java/net/stupidtech/sky/
  data/        networking, parsing, caches (no Android UI here)
    FlightData.kt   OkHttp client, per-host throttle, adsb.lol + adsb.fi aggregation/merge, search, METAR, radar
    OpenSky.kt      OpenSky gap-fill (free tier, credit budgeting, optional OAuth client credentials)
    Routes.kt       callsign → route/airline (adsbdb), cached, resolved one at a time
    Trace.kt        flown path: adsb.lol trace files (cut to current leg), OpenSky /tracks fallback
    Logos.kt        airline logo cards from pics.avs.io (runtime only, never bundled)
    Airlines.kt     callsign ICAO prefix → IATA code (bundled OpenFlights table) for logos
    Models.kt       @Serializable API models, Aircraft, AirportInfo, Enrichment
    Settings.kt     DataStore prefs (watchlist, OpenSky creds, toggles)
    UserLocation.kt platform LocationManager (no GMS)
  map/MapLayers.kt  every MapLibre source/layer: aircraft (SDF icons, shadow), labels, logos, trail,
                    route line, airports, METAR, radar
  ui/          Compose UI + MainViewModel (single ViewModel holds all screen state in UiState)
  alerts/AlertWorker.kt  WorkManager job (15 min) for watchlist notifications
app/src/main/assets/airports.geojson   generated (see tools/)
app/src/main/assets/airlines.tsv       generated (see tools/)
app/src/main/res/drawable/ic_launcher_*.xml, ic_stat_logo.xml   generated (see tools/)
app/src/test/   unit tests; fixtures in app/src/test/resources are real captured API responses
tools/          generators: build_airports.py, build_airlines.py, build_icon.py; loop-icon.svg is the icon source of truth
docs/implementation-notes.md   design decisions and their reasons (read first, keep updated)
```

## Architecture in one page

- **State**: `MainViewModel` exposes one `StateFlow<UiState>`. Composables are stateless apart from small
  UI toggles. Map updates happen in `MapHost` via `LaunchedEffect`s that push state into `MapLayers`.
- **Loaded area vs viewport**: polling refreshes `loadedArea` every 10 s. Panning/zooming never triggers a
  request. When the view moves away, the small refresh button highlights; the big "Refresh this area" banner
  appears only after 5 minutes. App-driven camera moves (my location, search) set `autoLoadNext` and load
  automatically. Polling pauses in `onStop` (`setActive(false)`).
- **Sources merged automatically**: `FlightData.area()` queries adsb.lol and adsb.fi in parallel and merges
  by ICAO hex, keeping the freshest position (`seen_pos`) and filling gaps from the other report. A network
  that returns 429 gets its own cooldown; the call only fails if every network failed.
- **OpenSky gap-fill**: separate, slower loop; only adds aircraft the main feeds lack (ground positions up
  to 15 min old, airborne up to 60 s). Boxes cropped to 5°×5° (1 credit). Poll interval spreads remaining
  daily credits until the UTC reset; minimum 300 s anonymous / 30 s with an account.
- **Background resolvers** in the ViewModel: routes (nearest-first, batches of 5) and logos. Both stop on
  transient failure and resume after the next refresh. Unknown answers (404 / unparseable) are cached;
  network errors are not.
- **Selection**: selecting an aircraft fetches enrichment (route, adsbdb registry, Planespotters photo) and
  the trace; the live trail is appended after the trace history.

## Data sources and etiquette (important)

All requests go through `FlightData.fetch`/`fetchBytes`, which apply the per-host throttle
(`FlightData.Throttle`: adsb.fi 1.1 s, adsb.lol 1 s, adsbdb/planespotters 0.5 s, avs.io 0.15 s).
**Never bypass it** and never add tighter polling.

| Data | Source | Notes |
|---|---|---|
| Positions | `api.adsb.lol/v2`, `opendata.adsb.fi/api/v2` | adsb.fi area responses use `aircraft`, others `ac`; `alt_baro` may be `"ground"` |
| Gap-fill / track fallback | OpenSky REST | non-commercial; daily credits; 429 header `X-Rate-Limit-Retry-After-Seconds` |
| Flown path | `adsb.lol/data/traces/<last2hex>/trace_full_<hex>.json` | undocumented web data; leg start = flags bit 2 or 30-min gap |
| Routes, registry, airlines | `api.adsbdb.com/v0` | 404 for unknown callsign; unknown airline returns a plain string |
| Photos | `api.planespotters.net/pub/photos` | **requires descriptive User-Agent** + photographer attribution in UI |
| Logos | `pics.avs.io/{w}/{h}/{IATA}.png` | undocumented; 404 if unknown; runtime only, never commit logo files |
| METAR | `aviationweather.gov/api/data/metar` | bbox queries rejected when huge |
| Radar | RainViewer `weather-maps.json` tiles | free tiles max zoom 7 |
| Airports | OurAirports (public domain), bundled | regenerate with `tools/build_airports.py` |
| Airline codes | OpenFlights `airlines.dat` (ODbL), bundled | regenerate with `tools/build_airlines.py`; data is old, so a resolved route's airline wins |
| Base map | OpenFreeMap `liberty` style | fonts: "Noto Sans Regular/Bold/Italic" only |

Known dead ends (don't retry): airplanes.live now requires emailing them; adsb.lol `/api/0/routeset` bulk
route endpoint returns empty; `globe.adsb.fi` traces are behind Cloudflare.

User-Agent is `StupidSky/<version> (+<CONTACT>)`; `CONTACT` is a `buildConfigField` in `app/build.gradle.kts`.

## Conventions

- **Kotlin + Jetpack Compose + Material 3**, MapLibre via `AndroidView`, OkHttp + kotlinx.serialization,
  coroutines. No DI framework, no Retrofit, no Room. Don't add heavy dependencies without a clear need.
- **Metric units in the UI**: format through `ui/Units.kt` (m, km/h, m/s, km). Source data is feet/knots/
  ft-min/nm; keep models in source units and convert only for display.
- Comments explain *why* (rate limits, API quirks, coverage gaps), not what. Match the existing density.
- Catch `CancellationException` and rethrow before generic `catch (e: Exception)`.
- Use `ConcurrentHashMap.containsKey()` explicitly (Kotlin `in` on it checks values, not keys).
- Map layer/source IDs are prefixed `sw-` and declared in `MapLayers.Companion`. Fonts must exist in the
  OpenFreeMap style.
- Attribution: if you add a data source, add it to the README table, the settings credits text, and show
  any attribution the source requires.
- Military/sensitive aircraft are shown unfiltered by design; don't add filtering that hides them by default.
- Don't store secrets in the repo. OpenSky credentials live only in on-device DataStore.

## Generated files — edit the generator, not the output

- `app/src/main/assets/airports.geojson` ← `python3 tools/build_airports.py airports.csv app/src/main/assets/airports.geojson`
  (download `airports.csv` from https://davidmegginson.github.io/ourairports-data/airports.csv). Military
  detection is name-based (e.g. Langebaanweg AFB is missed).
- `app/src/main/assets/airlines.tsv` ← `python3 tools/build_airlines.py airlines.dat app/src/main/assets/airlines.tsv`
  (download `airlines.dat` from https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat).
- `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_stat_logo.xml`, `tools/icon-preview.svg`
  ← `python3 tools/build_icon.py` from `tools/loop-icon.svg`. VectorDrawables can't do dash arrays or masks,
  so the script computes the solid half, dots and centre dot as geometry. Icon: black mark on white.
- Aircraft map icons are drawn in code (`MapLayers.planeBitmap/heliBitmap`) and converted to SDFs at
  runtime (`toSdf`, Tiny-SDF style) so outline (halo) and shadow work. Keep icons white-on-transparent
  before conversion.

## Testing guidance

- Add or extend unit tests in `app/src/test` for anything in `data/` or pure UI helpers (`Units`).
- Parsing tests use real captured responses in `app/src/test/resources`; capture a fresh one with `curl`
  when an API changes rather than hand-writing JSON.
- Android framework classes (Bitmap, Location) aren't available in JVM tests; keep logic you want tested
  free of them, or prototype visual output with a script (e.g. render SVG previews with headless Chromium).

## Commits and PRs

- Small, focused commits with a summary line and a short body explaining why.
- Run `./gradlew testDebugUnitTest assembleDebug` before committing.
- Update `README.md` (features, data sources) and this file when behaviour or architecture changes.
- Add an entry to `docs/implementation-notes.md` for any design decision, in the same commit as the change.

# stupid sky

An open-source Android flight tracker built only on free, public data. Military aircraft are not filtered out.

## Features
- **Metric units**: altitude in metres, speed in km/h, climb rate in m/s, distance in km.
- **Airline and origin → destination** for every flight with a known callsign, shown in the list, the detail panel
  and on map labels. Looked up in the background (nearest aircraft first) and cached.
- **Airline logos** above aircraft on the map (zoom 8+), fetched at runtime from pics.avs.io by IATA airline
  code and cached for the session. The airline comes from the callsign prefix via a bundled OpenFlights table,
  so logos don't wait for route lookups. Can be turned off in Settings.
- **Airports on the map** as pins from bundled OurAirports data (~11,600 airports): large from zoom 4, medium from 7,
  small coded airfields from 9; military fields in green. Tap one for its METAR and a live mini-board of
  arriving / departed flights and aircraft on the ground, built from tracked aircraft and their routes.
- **Live map** of every aircraft in the loaded area (up to 250 nm), refreshed every 10 s. Panning or zooming
  never triggers a request; tap **Refresh this area** (or the refresh button) to load where you're looking. Aircraft are shown with heading and colour:
  amber = civil, green = military, red = emergency squawk (7500/7600/7700), grey = on ground.
- **Aircraft details**: altitude, vertical rate, speed, squawk, TAS/Mach, selected altitude, wind/OAT
  (when the aircraft sends them), MLAT / PIA / LADD flags, registration, type, owner and country.
- **Route** (origin → destination) looked up by callsign.
- **Aircraft photo** with photographer credit.
- **Flight path** for the selected aircraft: the track flown since takeoff (adsb.lol trace history, OpenSky
  fallback), coloured by altitude and extended live, plus a dashed great-circle line to the destination with
  origin/destination markers when the route is known.
- **Search** by callsign, registration or ICAO hex, anywhere in the world.
- **Watchlist alerts**: get a notification when a registration, callsign, hex or military callsign prefix
  (e.g. `RCH`, `FORTE`) is airborne. Checked every 15 minutes in the background.
- **Aircraft list** of everything in view, sortable by distance, altitude, callsign or speed, and filterable by
  airborne / ground / military or text. Tap an aircraft to fly to it.
- **Sources combined automatically**: adsb.lol and adsb.fi are queried in parallel and merged, keeping the
  fresher position for each aircraft. If one network is rate-limited, the other keeps working.
- **OpenSky gap-fill** merges in aircraft the main feed misses, such as aircraft on the ground at airports with no
  nearby adsb.lol/adsb.fi receiver. Uses the free tier only: without an account it refreshes about every 5 min;
  with a free OpenSky API client (Settings) about every 30 s. The refresh rate adjusts so the daily credits last until they reset.

## Data sources
| What | Source | Terms |
|---|---|---|
| Positions | [adsb.lol](https://api.adsb.lol/docs) / [adsb.fi](https://github.com/adsbfi/opendata) | ODbL / open data, 1 req/s (adsb.fi) |
| Gap-fill positions | [OpenSky Network](https://openskynetwork.github.io/opensky-api/rest.html) | free, non-commercial, daily credit limit |
| Routes, aircraft registry | [adsbdb.com](https://www.adsbdb.com) | free |
| Photos | [Planespotters.net](https://www.planespotters.net/photo/api) | attribution + descriptive User-Agent required |
| Airline logos | pics.avs.io (Aviasales image CDN, fetched at runtime) | undocumented; logos are the airlines' trademarks |
| Airports | [OurAirports](https://ourairports.com/data/) (bundled, `tools/build_airports.py`) | public domain |
| Airline codes (ICAO → IATA) | [OpenFlights](https://openflights.org/data.php) (bundled, `tools/build_airlines.py`) | ODbL |
| METAR | [aviationweather.gov](https://aviationweather.gov/data/api/) | US gov, free |
| Radar | [RainViewer](https://www.rainviewer.com/api.html) | free tier, zoom ≤ 7 |
| Base map | [OpenFreeMap](https://openfreemap.org) + OpenStreetMap | free |

Set your own contact URL in `app/build.gradle.kts` (`CONTACT`). It goes into the User-Agent, and Planespotters rejects requests without one.

## Download
Latest APK: https://github.com/stephancill/stupid-sky/releases/latest/download/stupid-sky-arm64.apk
(or `stupid-sky-universal.apk` for any phone). All releases: https://github.com/stephancill/stupid-sky/releases

## Releasing
GitHub Actions tests, builds and publishes releases. Either open **Actions → Release → Run workflow** and
enter a version (e.g. `v0.1.1`), which tags the current `main`, or push a tag:
```
git tag v0.1.1 && git push origin v0.1.1
```

## Build
Requires JDK 17+ and the Android SDK (platform 35).
```
./gradlew assembleDebug        # APKs in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # parses captured API responses
```

## Limits
- Only aircraft that transmit ADS-B/Mode S (and are in range of a volunteer receiver) appear.
- The flown path uses adsb.lol's trace files (the data behind its web map), which aren't a documented API
  and could change; OpenSky is the fallback.
- Some countries restrict publishing military movements. Check before distributing the app publicly.

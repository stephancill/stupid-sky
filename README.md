# SkyWatch

An open-source Android flight tracker built only on free, public data. Military aircraft are not filtered out.

## Features
- **Live map** of every aircraft in the loaded area (up to 250 nm), refreshed every 10 s. Panning or zooming
  never triggers a request; tap **Refresh this area** (or the refresh button) to load where you're looking. Aircraft are shown with heading and colour:
  amber = civil, green = military, red = emergency squawk (7500/7600/7700), grey = on ground.
- **Military (global) mode** loads every aircraft the network flags as military, worldwide.
- **Aircraft details**: altitude, vertical rate, speed, squawk, TAS/Mach, selected altitude, wind/OAT
  (when the aircraft sends them), MLAT / PIA / LADD flags, registration, type, owner and country.
- **Route** (origin → destination) looked up by callsign.
- **Aircraft photo** with photographer credit.
- **Flight trail** for the selected aircraft, coloured by altitude. It is built from positions seen since the app opened.
- **Search** by callsign, registration or ICAO hex, anywhere in the world.
- **Weather**: RainViewer radar overlay, plus METAR stations coloured by flight category (VFR/MVFR/IFR/LIFR). Tap a station for the raw METAR.
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
| METAR | [aviationweather.gov](https://aviationweather.gov/data/api/) | US gov, free |
| Radar | [RainViewer](https://www.rainviewer.com/api.html) | free tier, zoom ≤ 7 |
| Base map | [OpenFreeMap](https://openfreemap.org) + OpenStreetMap | free |

Set your own contact URL in `app/build.gradle.kts` (`CONTACT`). It goes into the User-Agent, and Planespotters rejects requests without one.

## Build
Requires JDK 17+ and the Android SDK (platform 35).
```
./gradlew assembleDebug        # APKs in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # parses captured API responses
```

## Limits
- Only aircraft that transmit ADS-B/Mode S (and are in range of a volunteer receiver) appear.
- Trail history starts when you open the app. Long-term history isn't included yet; see the adsb.lol daily archives if you want to add it.
- Some countries restrict publishing military movements. Check before distributing the app publicly.

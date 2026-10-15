#!/usr/bin/env python3
"""
Builds app/src/main/assets/airports.geojson from OurAirports (public domain).

    curl -o airports.csv https://davidmegginson.github.io/ourairports-data/airports.csv
    python3 tools/build_airports.py airports.csv app/src/main/assets/airports.geojson

Keeps large and medium airports plus small airfields that have an ICAO or IATA code.
"""
import csv
import json
import re
import sys

RANK = {"large_airport": 3, "medium_airport": 2, "small_airport": 1}
MILITARY = re.compile(r"\b(air force|air base|airbase|afb|military|naval air|army air|raf |nas )", re.I)


def main(src, dst):
    features = []
    with open(src, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rank = RANK.get(r["type"])
            if not rank:
                continue
            icao = (r["icao_code"] or "").strip()
            iata = (r["iata_code"] or "").strip()
            if rank == 1 and not (len(icao) == 4 or iata):
                continue
            try:
                lat = round(float(r["latitude_deg"]), 5)
                lon = round(float(r["longitude_deg"]), 5)
            except ValueError:
                continue
            props = {
                "code": iata or icao or r["ident"],
                "icao": icao or r["gps_code"] or r["ident"],
                "name": r["name"],
                "rank": rank,
            }
            if iata:
                props["iata"] = iata
            if r["municipality"]:
                props["city"] = r["municipality"]
            if r["elevation_ft"]:
                props["elev"] = int(float(r["elevation_ft"]))
            if MILITARY.search(r["name"]):
                props["mil"] = True
            features.append({
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
                "properties": props,
            })
    # Biggest first so they win label placement.
    features.sort(key=lambda f: -f["properties"]["rank"])
    with open(dst, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": features}, f, separators=(",", ":"), ensure_ascii=False)
    print(f"{len(features)} airports -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

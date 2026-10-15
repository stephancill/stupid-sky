#!/usr/bin/env python3
"""
Builds app/src/main/assets/airlines.tsv (ICAO prefix -> IATA code) from OpenFlights (ODbL).

    curl -o airlines.dat https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat
    python3 tools/build_airlines.py airlines.dat app/src/main/assets/airlines.tsv

Lets the app pick an airline logo from the callsign prefix without waiting for a route lookup.
Where several airlines share an ICAO code, active ones win, then the lowest OpenFlights id.
"""
import csv
import re
import sys

ICAO = re.compile(r"^[A-Z]{3}$")
IATA = re.compile(r"^[A-Z0-9]{2}$")


def main(src, dst):
    best = {}
    with open(src, encoding="utf-8") as f:
        for row in csv.reader(f):
            # Some rows have stray quotes and extra columns; the id, IATA and ICAO columns come first.
            if len(row) < 5:
                continue
            iata, icao = row[3].strip(), row[4].strip()
            if not (ICAO.match(icao) and IATA.match(iata)):
                continue
            key = (row[-1] != "Y", int(row[0]))
            if icao not in best or key < best[icao][0]:
                best[icao] = (key, iata)
    with open(dst, "w", encoding="utf-8") as out:
        for icao in sorted(best):
            out.write(f"{icao}\t{best[icao][1]}\n")
    print(f"{len(best)} airlines -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

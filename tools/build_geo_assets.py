#!/usr/bin/env python3
"""Builds the offline map + IP-to-country assets bundled in the app.

Inputs (all downloadable from npm):
  @ip-location-db/dbip-country  -> dbip-country-ipv4-num.csv   (CC BY 4.0, DB-IP.com)
  world-atlas                   -> countries-110m.json, countries-50m.json (Natural Earth, public domain)
  i18n-iso-countries            -> codes.json (ISO numeric -> alpha-2)

Usage:
  python3 tools/build_geo_assets.py DBIP_CSV ATLAS_DIR ISO_CODES_JSON OUT_DIR

Outputs:
  OUT_DIR/geoip4.bin  magic "GIP1", u32 N, N x u32 range starts, N x u8 country index,
                      u8 C, C x 2-byte ASCII codes ("--" = unknown). Sorted; each range
                      runs until the next start.
  OUT_DIR/world.bin   magic "WLD1", u16 countries; per country: 2-byte code, f32 lat,
                      f32 lon (label point), u16 rings; per ring u16 points, then points
                      as i16 lon*100, i16 lat*100. Countries with no outline at 110m have 0 rings.
"""
import csv
import json
import struct
import sys


def build_geoip(csv_path, out_path):
    rows = []
    with open(csv_path, newline="") as f:
        for start, end, cc in csv.reader(f):
            rows.append((int(start), int(end), cc.strip() or "--"))
    rows.sort()
    merged = []  # (start, cc)
    next_expected = 0
    for start, end, cc in rows:
        if start > next_expected:
            if not merged or merged[-1][1] != "--":
                merged.append((next_expected, "--"))
        if not merged or merged[-1][1] != cc:
            merged.append((start, cc))
        next_expected = max(next_expected, end + 1)
    if next_expected <= 0xFFFFFFFF:
        merged.append((next_expected, "--"))
    codes = sorted({cc for _, cc in merged})
    assert len(codes) < 256
    idx = {c: i for i, c in enumerate(codes)}
    with open(out_path, "wb") as o:
        o.write(b"GIP1")
        o.write(struct.pack(">I", len(merged)))
        o.write(b"".join(struct.pack(">I", s) for s, _ in merged))
        o.write(bytes(idx[c] for _, c in merged))
        o.write(struct.pack(">B", len(codes)))
        for c in codes:
            o.write(c.encode("ascii")[:2].ljust(2, b"-"))
    print(f"geoip4.bin: {len(rows)} source ranges -> {len(merged)} merged, {len(codes)} codes")


def decode_topo(path):
    t = json.load(open(path))
    sx, sy = t["transform"]["scale"]
    tx, ty = t["transform"]["translate"]
    arcs = []
    for arc in t["arcs"]:
        x = y = 0
        pts = []
        for dx, dy in arc:
            x += dx
            y += dy
            pts.append((x * sx + tx, y * sy + ty))
        arcs.append(pts)

    def ring(indices):
        out = []
        for i in indices:
            pts = arcs[i] if i >= 0 else arcs[~i][::-1]
            out.extend(pts if not out else pts[1:])
        return out

    countries = {}
    for g in t["objects"]["countries"]["geometries"]:
        if "id" not in g or g.get("type") is None:
            continue
        polys = g["arcs"] if g["type"] == "MultiPolygon" else [g["arcs"]]
        countries[g["id"]] = [[ring(r) for r in poly] for poly in polys]
    return countries


def area_centroid(r):
    a = cx = cy = 0.0
    for (x0, y0), (x1, y1) in zip(r, r[1:] + r[:1]):
        c = x0 * y1 - x1 * y0
        a += c
        cx += (x0 + x1) * c
        cy += (y0 + y1) * c
    if abs(a) < 1e-12:
        xs, ys = zip(*r)
        return abs(a), sum(xs) / len(xs), sum(ys) / len(ys)
    return abs(a) / 2, cx / (3 * a), cy / (3 * a)


# Countries whose largest shape crosses the 180° line get a hand-picked label point.
LABEL_OVERRIDES = {"RU": (61.5, 99.0), "FJ": (-17.8, 178.0)}


def build_world(atlas_dir, codes_path, out_path):
    numeric_to_a2 = {row[2]: row[0] for row in json.load(open(codes_path))}
    coarse = decode_topo(f"{atlas_dir}/countries-110m.json")
    fine = decode_topo(f"{atlas_dir}/countries-50m.json")
    out = []
    for num, polys in fine.items():
        a2 = numeric_to_a2.get(num)
        if not a2:
            continue
        # Label point: centroid of the country's largest outer ring.
        best = max((area_centroid(p[0]) for p in polys), key=lambda v: v[0])
        _, lon, lat = best
        if a2 in LABEL_OVERRIDES:
            lat, lon = LABEL_OVERRIDES[a2]
        lon = (lon + 180) % 360 - 180
        rings = [r for poly in coarse.get(num, []) for r in poly]
        out.append((a2, lat, lon, rings))
    with open(out_path, "wb") as o:
        o.write(b"WLD1")
        o.write(struct.pack(">H", len(out)))
        for a2, lat, lon, rings in out:
            o.write(a2.encode("ascii"))
            o.write(struct.pack(">ffH", lat, lon, len(rings)))
            for r in rings:
                o.write(struct.pack(">H", len(r)))
                for x, y in r:
                    o.write(struct.pack(">hh", round(x * 100), round(y * 100)))
    print(f"world.bin: {len(out)} countries, {sum(1 for c in out if c[3])} with outlines")


if __name__ == "__main__":
    dbip_csv, atlas_dir, codes_json, out_dir = sys.argv[1:5]
    build_geoip(dbip_csv, f"{out_dir}/geoip4.bin")
    build_world(atlas_dir, codes_json, f"{out_dir}/world.bin")

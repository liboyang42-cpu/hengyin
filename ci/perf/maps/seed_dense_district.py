#!/usr/bin/env python3
"""Dense district seed for the map reads (T2 maps-backend, BE-30).

Prints SQL for one MySQL schema built from sql/base_schema_20260818.sql plus the migrations the map reads use.
It never connects to a database: pipe the output to `mysql` yourself, on a local or TEST database only.

    python3 ci/perf/maps/seed_dense_district.py --database cy_maps --places 5000 > /tmp/seed.sql
    mysql -uroot cy_maps < /tmp/seed.sql

Every seeded row has an id at or above --id-base and a name starting with "loadseed"; the script first deletes
the rows it seeded earlier (same id range), so it can run again. The production schema name is refused.

What it seeds (all inside one district box, default 0.06 x 0.07 degrees around West Lake, Hangzhou):
  places (roam_poi) with every visibility case of the player read (published / unpublished, merchant applications
  pending / approved / rejected, claims), places on 0.02-degree tile edges and places sharing a point (ties),
  merchants with shop avatars (npc_profile), found / reward rows for the load-test members, kept fog cells and
  30-day fog tiles for those members, clubs with a pinned point or only located events, deals and sponsored quests.
"""
from __future__ import annotations

import argparse
import random
import sys
from datetime import datetime, timedelta

FORBIDDEN_DATABASES = {"chengyinhubdb"}
BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def geohash(lat: float, lng: float, precision: int = 7) -> str:
    lat_lo, lat_hi, lng_lo, lng_hi = -90.0, 90.0, -180.0, 180.0
    bits, bit, ch, even, out = [16, 8, 4, 2, 1], 0, 0, True, []
    while len(out) < precision:
        if even:
            mid = (lng_lo + lng_hi) / 2
            if lng > mid:
                ch |= bits[bit]
                lng_lo = mid
            else:
                lng_hi = mid
        else:
            mid = (lat_lo + lat_hi) / 2
            if lat > mid:
                ch |= bits[bit]
                lat_lo = mid
            else:
                lat_hi = mid
        even = not even
        if bit < 4:
            bit += 1
        else:
            out.append(BASE32[ch])
            bit, ch = 0, 0
    return "".join(out)


def q(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, datetime):
        return "'" + value.strftime("%Y-%m-%d %H:%M:%S") + "'"
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def inserts(table: str, columns: list[str], rows: list[tuple], chunk: int = 500) -> list[str]:
    out = []
    for i in range(0, len(rows), chunk):
        values = ",\n".join("(" + ", ".join(q(v) for v in row) + ")" for row in rows[i:i + chunk])
        out.append(f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n{values};")
    return out


def build(args: argparse.Namespace) -> list[str]:
    rnd = random.Random(args.seed)
    base = args.id_base
    now = datetime(2026, 11, 15, 12, 0, 0)
    south, north = args.center_lat - args.span_lat / 2, args.center_lat + args.span_lat / 2
    west, east = args.center_lng - args.span_lng / 2, args.center_lng + args.span_lng / 2
    sql = [
        "-- T2 maps-backend dense district seed. Local or TEST databases only; never production.",
        f"-- places={args.places} merchants={args.places // 5} members={args.members} clubs={args.clubs} seed={args.seed}",
        f"USE `{args.database}`;",
        "SET NAMES utf8mb4;",
    ]
    cleanup = [
        ("sponsored_quest_place", "campaign_id"), ("sponsored_quest_campaign", "id"), ("marketplace_deal", "coupon_id"),
        ("sms_coupon", "id"), ("cms_activity", "id"), ("club", "id"), ("roam_member_fog_cell", "member_id"),
        ("roam_fog_tile", "member_id"), ("roam_session", "member_id"), ("roam_poi_reward_log", "member_id"),
        ("roam_poi_found", "member_id"), ("npc_profile", "id"), ("roam_poi", "id"), ("mms_merchant", "id"),
    ]
    for table, column in cleanup:
        sql.append(f"DELETE FROM {table} WHERE {column} >= {base};")

    merchants = max(1, args.places // 5)
    sql += inserts("mms_merchant", ["id", "member_id", "name", "category_id", "status", "account_status", "del_flag",
                                    "cover_image", "create_time"],
                   [(base + m, base + m, f"loadseed merchant {m}", 1 + m % 12, 1, 1, 0,
                     f"/images/loadseed/m{m % 40}.jpg", now) for m in range(merchants)])
    sql += inserts("npc_profile", ["id", "scope_type", "scope_id", "name", "avatar", "persona", "sort", "audit_status",
                                   "enabled", "del_flag", "create_time"],
                   [(base + m, 2, base + m, f"loadseed npc {m}", f"preset:npc_{m % 16}", "loadseed", m % 3,
                     1 if m % 7 else 0, 1 if m % 11 else 0, "0", now) for m in range(0, merchants, 2)])

    places, tile = [], 0.02
    for i in range(args.places):
        lat = round(rnd.uniform(south, north), 6)
        lng = round(rnd.uniform(west, east), 6)
        if i % 97 == 0:  # exactly on a 0.02-degree tile edge
            lat = round(round(lat / tile) * tile, 6)
        if i % 89 == 0:
            lng = round(round(lng / tile) * tile, 6)
        if i % 53 == 0 and places:  # same point as an earlier place: a distance tie broken by id
            lat, lng = places[-1][3], places[-1][4]
        kind = i % 20
        status = 0 if kind == 0 else 1
        application_type, audit_status = None, None
        if kind in (1, 2):
            application_type, audit_status = 1, (0 if kind == 1 else 2)  # pending / rejected: hidden
        elif kind in (3, 4):
            application_type, audit_status = (1 if kind == 3 else 3), 1  # approved: shown
        elif kind == 5:
            application_type, audit_status = 2, None  # node claim: shown
        is_shop = i % 3 == 0
        merchant = base + (i % merchants) if is_shop else None
        places.append((base + i, f"loadseed place {i}", 2 if is_shop else 1, lat, lng, 80, None,
                       "loadseed", 20, None, "330100", status, audit_status, application_type, None, None,
                       now - timedelta(minutes=i), merchant, None, 1 + i % 4, "coffee,green" if i % 4 == 0 else "art",
                       f"/images/loadseed/p{i % 50}.jpg", i % 10))
    sql += inserts("roam_poi", ["id", "name", "type", "lat", "lng", "radius_m", "address", "description", "xp",
                                "coupon_template_id", "city_code", "status", "audit_status", "application_type",
                                "applicant_merchant_id", "audit_reason", "create_time", "merchant_id", "template_id",
                                "node_level", "tags", "cover_img", "sort"], places)

    found, rewards, sessions, tiles, kept = [], [], [], [], []
    for m in range(args.members):
        member = base + m
        session = base + m
        sessions.append((session, member, now - timedelta(days=1), now - timedelta(hours=20), 1, now - timedelta(days=1)))
        for p in rnd.sample(range(args.places), min(args.places, 60)):
            found.append((member, base + p, session, now - timedelta(hours=p % 48)))
        for j, p in enumerate(rnd.sample(range(args.places), min(args.places, 20))):
            rewards.append((base + p, member, j % 3, base + j if j % 2 else 0, now))
        cells = set()
        for _ in range(args.cells_per_member):
            cells.add(geohash(rnd.uniform(south, north), rnd.uniform(west, east)))
        for c in sorted(cells):
            tiles.append((member, session, c, now - timedelta(days=2)))
            kept.append((member, c, c[:5], now - timedelta(days=40), now - timedelta(days=2)))
    sql += inserts("roam_session", ["id", "member_id", "started_at", "finished_at", "status", "create_time"], sessions)
    sql += inserts("roam_poi_found", ["member_id", "poi_id", "session_id", "found_at"], found)
    sql += inserts("roam_poi_reward_log", ["poi_id", "member_id", "status", "coupon_id", "create_time"], rewards)
    sql += inserts("roam_fog_tile", ["member_id", "session_id", "geohash7", "revealed_at"], tiles)
    sql += inserts("roam_member_fog_cell", ["member_id", "cell_key", "cell_prefix5", "first_revealed_at",
                                            "last_revealed_at"], kept)

    clubs, events = [], []
    for c in range(args.clubs):
        pinned = c % 3 == 0
        lat, lng = round(rnd.uniform(south, north), 7), round(rnd.uniform(west, east), 7)
        clubs.append((base + c, base + c, f"loadseed club {c}", 1, 0, 1 if c % 10 else 0,
                      lat if pinned else None, lng if pinned else None, 10 + c % 50, now))
        for e in range(args.events_per_club):
            elat, elng = round(rnd.uniform(south, north), 6), round(rnd.uniform(west, east), 6)
            events.append((base + c * args.events_per_club + e, base + c, f"loadseed event {c}-{e}", str(elng), str(elat),
                           1, 1, 0, now - timedelta(days=e * 3), now - timedelta(days=e * 3 - 1), base + c))
    sql += inserts("club", ["id", "member_id", "name", "status", "del_flag", "public_visible", "location_lat",
                            "location_lng", "member_count", "create_time"], clubs)
    sql += inserts("cms_activity", ["id", "member_id", "name", "longitude", "latitude", "status", "publish_status",
                                    "del_flag", "start_date", "end_date", "club_id"], events)

    coupons, deals = [], []
    for d in range(min(args.deals, merchants)):
        coupons.append((base + d, f"loadseed deal {d}", 1, 0, 10, 50, now - timedelta(days=1), now + timedelta(days=30),
                        100, 3, now - timedelta(days=1), 1, base + d, base + d, 1, 0, now))
        deals.append((base + d, 1, d % 5, now))
    sql += inserts("sms_coupon", ["id", "name", "coupon_type", "platform", "amount", "min_point", "start_time",
                                  "end_time", "publish_count", "receive_count", "enable_time", "scope_type",
                                  "member_id", "merchant_id", "status", "del_flag", "create_time"], coupons)
    sql += inserts("marketplace_deal", ["coupon_id", "enabled", "sort", "create_time"], deals)

    campaigns, quest_places = [], []
    for s in range(args.sponsored):
        campaigns.append((base + s, f"loadseed sponsor {s}", f"loadseed quest {s}", "loadseed", 0, 0,
                          now - timedelta(days=1), now + timedelta(days=30), 1, now))
        for k, p in enumerate(rnd.sample(range(args.places), 3)):
            quest_places.append((base + s, base + p, k))
    sql += inserts("sponsored_quest_campaign", ["id", "sponsor_name", "title", "content", "budget_cent", "spent_cent",
                                                "start_time", "end_time", "status", "create_time"], campaigns)
    sql += inserts("sponsored_quest_place", ["campaign_id", "poi_id", "sort"], quest_places)
    sql.append("ANALYZE TABLE roam_poi, roam_fog_tile, roam_member_fog_cell, club, cms_activity;")
    return sql


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--database", required=True, help="target schema name (local or TEST)")
    p.add_argument("--places", type=int, default=5000)
    p.add_argument("--members", type=int, default=50, help="load-test member ids are id-base .. id-base+members-1")
    p.add_argument("--clubs", type=int, default=300)
    p.add_argument("--events-per-club", type=int, default=4)
    p.add_argument("--deals", type=int, default=200)
    p.add_argument("--sponsored", type=int, default=20)
    p.add_argument("--cells-per-member", type=int, default=400)
    p.add_argument("--center-lat", type=float, default=30.2590)
    p.add_argument("--center-lng", type=float, default=120.1500)
    p.add_argument("--span-lat", type=float, default=0.06)
    p.add_argument("--span-lng", type=float, default=0.07)
    p.add_argument("--id-base", type=int, default=9_000_000)
    p.add_argument("--seed", type=int, default=20261115)
    args = p.parse_args(argv)
    if args.database.strip().lower() in FORBIDDEN_DATABASES:
        print(f"REFUSED: {args.database} is the production schema name", file=sys.stderr)
        return 2
    if args.places < 1 or args.id_base < 1_000_000:
        print("REFUSED: need --places >= 1 and --id-base >= 1000000 (seeded rows must not collide)", file=sys.stderr)
        return 2
    sys.stdout.write("\n".join(build(args)) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

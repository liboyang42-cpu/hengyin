"""F09 City Monopoly (one currency: 积分 / POINTS; spec docs/delivery/backend-app-r2/CITY-MONOPOLY-SPEC.md on
docs/city-monopoly-spec, owner ruling 2026-11-15): season -> map -> points -> claim quote/command -> rent check-in by a
visitor -> challenge quote/command on a defended property -> revenue.
Fixture (fixtures.py): QA LIVE board 'qa-monopoly-1' with POIs (E2E_MONOPOLY_POI), a territory of 9001 past its
protection (E2E_MONOPOLY_DEFENDED), the account gates met by 9004/9009. Errors use real HTTP status + errorCode.
Write bodies follow spec §6.2: requestId (UUID) + quoteId/quoteHash + evidence{lat,lng,hAcc,samples[{t,lat,lng,hAcc}],
photoAssetId, capturedAt, captureLat, captureLng, inAppCamera}. The native client has no territory client yet (app gap,
listed in REPORT); this flow is the backend contract."""
import random, time, uuid
from harness import *

LAT, LNG = 23.129112, 113.264385


def rq():
    return str(uuid.uuid4()).upper()


def evidence(lat, lng):
    now = int(time.time() * 1000)
    return {"lat": lat, "lng": lng, "hAcc": 8, "simulated": False, "inAppCamera": True,
            "samples": [{"t": now - 95000, "lat": lat, "lng": lng, "hAcc": 9},
                        {"t": now - 50000, "lat": lat + 0.00001, "lng": lng, "hAcc": 8},
                        {"t": now - 2000, "lat": lat, "lng": lng + 0.00001, "hAcc": 8}],
            "photoAssetId": "media-asset:%s" % os.environ.get("E2E_COVER_ASSET_ID", "qa"),
            "capturedAt": now - 4000, "captureLat": lat, "captureLng": lng,
            "phash": "%016x" % random.getrandbits(64)}


def flow(f):
    owner, visitor = PLAYER, PLAYER2
    poi, defended = os.environ.get("E2E_MONOPOLY_POI"), os.environ.get("E2E_MONOPOLY_DEFENDED")
    if not (poi and defended):
        raise Blocked("no monopoly board/POI fixture (run fixtures.py first)")
    bbox = os.environ.get("E2E_MONOPOLY_BBOX", "23.12,113.255,23.14,113.275").split(",")
    f.step("season/current", "GET", "/api/city-game/season/current", owner, check=ok_data)
    f.step("territory/map lists the QA board's properties", "GET", "/api/city-game/territory/map", owner,
           q=dict(zip(("minLat", "minLng", "maxLat", "maxLng"), bbox)),
           check=lambda j: None if "qa-d1" in str(data_of(j)) and (data_of(j) or {}).get("currency") == "POINTS"
           else "QA district not on the map or currency != POINTS")  # unlit tiles show only {districtId, tier} (§6.2)
    pts = f.step("territory/points (balance HUD, 积分)", "GET", "/api/city-game/territory/points", owner, check=ok_data)
    q = f.step("claim/quote", "POST", "/api/city-game/territory/claim/quote", owner, js={"poiId": int(poi)},
               check=lambda j: None if (data_of(j) or {}).get("quoteId") and (data_of(j) or {}).get("quoteHash")
               and (data_of(j) or {}).get("cost") else "quote lacks quoteId/quoteHash/cost")
    qd = data_of(q) or {}
    c = f.step("claim/command", "POST", "/api/city-game/territory/claim/command", owner,
               js={"requestId": rq(), "quoteId": qd.get("quoteId"), "quoteHash": qd.get("quoteHash"), "evidence": evidence(LAT, LNG)},
               check=lambda j: None if (data_of(j) or {}).get("outcome") == "CLAIMED" else
               "outcome=%s reasons=%s" % ((data_of(j) or {}).get("outcome"), (data_of(j) or {}).get("reasons")))
    prop = (data_of(c) or {}).get("property") or {}
    terr = prop.get("territoryId") or prop.get("propertyId")
    # As AccountComplianceService does it: read the latest roam_location row, then AGREE with its requestId as
    # expectedRequestId (a stale expectation answers 409 "定位授权状态已变化").
    subj = {"docType": "privacy_policy", "scene": "roam_location"}
    lt = f.step("visitor consents/latest roam_location", "POST", "/api/compliance/consents/latest", visitor, js=subj)
    latest = data_of(lt) if isinstance(data_of(lt), dict) else {}
    if latest.get("eventType") != "AGREE":
        f.step("visitor consent AGREE roam_location", "POST", "/api/compliance/consents", visitor,
               js=dict(subj, eventType="AGREE", requestId=rid("vc"), expectedRequestId=latest.get("requestId") or ""))
    f.step("rent: visitor check-in on the claimed property (POINTS)", "POST", "/api/roam/territory/checkin", visitor,
           js={"requestId": rq(), "territoryId": terr, "lat": LAT, "lng": LNG, "accuracyM": 8},
           check=lambda j: None if (data_of(j) or {}).get("revenueMode") == "POINTS" else "revenueMode != POINTS")
    f.step("challenge/quote on a freshly claimed property is refused (protection)", "POST",
           "/api/city-game/territory/challenge/quote", visitor, js={"territoryId": terr}, expect_http=409, expect_code=None,
           check=lambda j: None if "PROTECTED" in json.dumps(j) else "not refused as protected")
    dlat, dlng = 23.129600, 113.265100
    cq = f.step("challenge/quote on the defended property", "POST", "/api/city-game/territory/challenge/quote", visitor,
                js={"territoryId": defended}, check=lambda j: need(j, "quoteId", "fee"))
    cd = data_of(cq) or {}
    f.step("challenge/command (server d20)", "POST", "/api/city-game/territory/challenge/command", visitor,
           js={"requestId": rq(), "quoteId": cd.get("quoteId"), "quoteHash": cd.get("quoteHash"), "evidence": evidence(dlat, dlng)},
           check=lambda j: None if (data_of(j) or {}).get("outcome") in ("WIN", "LOSS", "TASK_FAILED") else
           "outcome=%s reasons=%s" % ((data_of(j) or {}).get("outcome"), (data_of(j) or {}).get("reasons")))
    f.step("territory/revenue (owner)", "GET", "/api/city-game/territory/revenue", owner, check=ok_data)


flow.FLOW = ("F09", "City Monopoly claim, rent, challenge")
if __name__ == "__main__":
    main(flow)

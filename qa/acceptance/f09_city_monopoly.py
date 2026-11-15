"""F09 City Monopoly (points only): map -> points -> claim quote/command -> rent checkin -> challenge quote/command.
Needs a monopoly board fixture on the TEST DB (not in the e2e seed): set E2E_MONOPOLY_POI (a roam_poi id),
and E2E_MONOPOLY_BBOX="minLat,minLng,maxLat,maxLng". Errors use real HTTP status + errorCode."""
import uuid
from harness import *


def rq():
    return "qa-%s-%s" % (RUN_ID, uuid.uuid4().hex[:12])


def flow(f):
    owner, visitor = PLAYER, PLAYER2
    bbox = os.environ.get("E2E_MONOPOLY_BBOX", "23.10,113.24,23.15,113.29").split(",")
    f.step("season/current", "GET", "/api/city-game/season/current", owner, check=ok_data)
    f.step("territory/map", "GET", "/api/city-game/territory/map", owner,
           q=dict(zip(("minLat", "minLng", "maxLat", "maxLng"), bbox)), check=ok_data)
    f.step("territory/points (balance HUD)", "GET", "/api/city-game/territory/points", owner, check=ok_data)
    poi = os.environ.get("E2E_MONOPOLY_POI")
    if not poi:
        f.block("no monopoly board/POI fixture on TEST (set E2E_MONOPOLY_POI) - data seeding needed")
    q = f.step("claim/quote", "POST", "/api/city-game/territory/claim/quote", owner, js={"poiId": poi},
               check=lambda j: None if (data_of(j) or {}).get("currency") == "POINTS" else "quote not in POINTS")
    qd = data_of(q) or {}
    ev = {"lat": 23.129112, "lng": 113.264385, "hAcc": 8,
          "samples": [{"t": 0, "lat": 23.129112, "lng": 113.264385, "hAcc": 8}],
          "photoAssetId": os.environ.get("E2E_PHOTO_ASSET_ID")}
    c = f.step("claim/command", "POST", "/api/city-game/territory/claim/command", owner,
               js={"requestId": rq(), "quoteId": qd.get("quoteId"), "quoteHash": qd.get("quoteHash"), "evidence": ev},
               check=lambda j: None if (data_of(j) or {}).get("outcome") == "CLAIMED" else
               "outcome=%s reasons=%s" % ((data_of(j) or {}).get("outcome"), (data_of(j) or {}).get("reasons")))
    terr = ((data_of(c) or {}).get("property") or {}).get("territoryId")
    f.step("rent: visitor checkin pays owner in points", "POST", "/api/roam/territory/checkin", visitor,
           js={"requestId": rq(), "territoryId": terr, "lat": 23.129112, "lng": 113.264385, "accuracyM": 8},
           check=lambda j: None if (data_of(j) or {}).get("revenueMode") == "POINTS" else "revenueMode != POINTS")
    cq = f.step("challenge/quote", "POST", "/api/city-game/territory/challenge/quote", visitor,
                js={"territoryId": terr}, check=lambda j: need(j, "quoteId", "fee"))
    cd = data_of(cq) or {}
    f.step("challenge/command", "POST", "/api/city-game/territory/challenge/command", visitor,
           js={"requestId": rq(), "quoteId": cd.get("quoteId"), "quoteHash": cd.get("quoteHash"), "evidence": ev},
           check=lambda j: None if (data_of(j) or {}).get("outcome") in ("WIN", "LOSS", "TASK_FAILED") else
           "outcome=%s" % (data_of(j) or {}).get("outcome"))
    f.step("territory/revenue (owner)", "GET", "/api/city-game/territory/revenue", owner, check=ok_data)


flow.FLOW = ("F09", "City Monopoly claim, rent, challenge")
if __name__ == "__main__":
    main(flow)

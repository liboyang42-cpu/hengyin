"""F08 Roam walk and fog reveal: consent -> reveal (starts session) -> presence -> session -> finish -> records -> tiles."""
import json, time, uuid
from harness import *

TILES = ["ws0e9cb", "ws0e9cc"]  # geohash7 around Guangzhou old town (TEST coordinates)


def flow(f):
    m = PLAYER
    rid = ("qa%s%s" % (RUN_ID, uuid.uuid4().hex))[:64]
    # The app never records the AGREE consent (account_api.dart:200 only revokes) -> roam/reveal would 4xx in-app.
    f.step("compliance consent AGREE roam_location", "POST", "/api/compliance/consents", m,
           js={"docType": "privacy_policy", "scene": "roam_location", "eventType": "AGREE", "requestId": rid,
               "expectedRequestId": os.environ.get("E2E_ROAM_CONSENT_PREV", "")},
           expect_code=(200, 409))
    t0 = int(time.time() * 1000)
    r = f.step("roam/reveal (new session)", "POST", "/api/roam/reveal", m,
               form={"sessionId": 0, "tiles": ",".join(TILES)}, check=lambda j: need(j, "sessionId"))
    sid = (data_of(r) or {}).get("sessionId")
    f.step("roam/reveal replay reveals nothing new", "POST", "/api/roam/reveal", m,
           form={"sessionId": sid, "tiles": ",".join(TILES)},
           check=lambda j: None if (data_of(j) or {}).get("newlyRevealed") == 0 else "replay newlyRevealed != 0")
    f.step("roam/presence heartbeat", "POST", "/api/roam/presence", m,
           form={"sessionId": sid, "lat": "23.129112", "lng": "113.264385", "explorePct": "1"})
    f.step("roam/session", "GET", "/api/roam/session", m, q={"sessionId": sid}, check=ok_data)
    fin = f.step("roam/finish", "POST", "/api/roam/finish", m,
                 form={"sessionId": sid, "poiIds": "", "distanceM": "350"}, check=lambda j: need(j, "finishedAt"))
    f.step("roam/finish twice is refused/settled", "POST", "/api/roam/finish", m,
           form={"sessionId": sid, "poiIds": "", "distanceM": "350"}, expect_code=None,
           check=lambda j: None if j.get("code") != 200 or "已结算" in (j.get("msg") or "") else "double settle accepted")
    # collectedAt must lie inside [startedAt, finishedAt] on the SERVER clock (RoamSessionWriteService.validateArchive).
    # The TEST VPS clock runs ~6 s ahead of this machine, so anchor on the server's finishedAt, then also send the
    # client-clock value (what a phone whose clock is a few seconds behind would send) as a separate, labelled step.
    fin_ms = (data_of(fin) or {}).get("finishedAt") or (t0 + 500)
    f.step("roam/session/records", "POST", "/api/roam/session/records", m,
           form={"sessionId": sid, "records": json.dumps([{"tile": TILES[0], "collectedAt": fin_ms - 1000}])})
    f.step("roam/session/records with client-clock timestamp (clock skew)", "POST", "/api/roam/session/records", m,
           form={"sessionId": sid, "records": json.dumps([{"tile": TILES[1], "collectedAt": t0 + 500}])}, owner="env")
    f.step("roam/tiles contains revealed tiles", "GET", "/api/roam/tiles", m, q={"limit": 500},
           check=lambda j: None if TILES[0] in json.dumps(data_of(j)) else "revealed tile not listed")


flow.FLOW = ("F08", "Roam walk and fog reveal")
if __name__ == "__main__":
    main(flow)

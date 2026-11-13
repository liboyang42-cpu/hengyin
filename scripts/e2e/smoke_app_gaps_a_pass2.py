#!/usr/bin/env python3
"""HTTP smoke for app gaps A pass 2 on the TEST copy (port 8131, schema cy_gaps_a, redis db 11).
TEST only; run after seed_app_gaps_a.py and seed_app_gaps_a_pass2.py. Prints PASS/FAIL per check."""
import base64, hashlib, json, struct, subprocess, sys, urllib.error, urllib.request, uuid, zlib

B = "http://127.0.0.1:8131"
REGION, BOARD = "gaps-a-r491", "gapsa-r491-1"
results = []

def login(phone):
    subprocess.run(["docker", "exec", "be-int-redis", "redis-cli", "-n", "11", "SET", f"sms_{phone}_code", '"123456"'], capture_output=True)
    req = urllib.request.Request(B + "/api/login/phone", data=f"phone={phone}&code=123456".encode(), method="POST")
    return json.loads(urllib.request.urlopen(req).read())["token"]

def call(tok, method, path, body=None, raw=None, headers=None):
    h = {"Accept": "application/json"}
    if tok: h["Authorization"] = "Bearer " + tok
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode(); h["Content-Type"] = "application/json"
    if raw is not None:
        data, ctype = raw; h["Content-Type"] = ctype
    h.update(headers or {})
    req = urllib.request.Request(B + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            payload = r.read()
            return r.status, (json.loads(payload) if r.headers.get_content_type() == "application/json" else payload)
    except urllib.error.HTTPError as e:
        payload = e.read() or b"{}"
        try: return e.code, json.loads(payload)
        except ValueError: return e.code, {"raw": payload[:200]}

def check(name, ok, detail=""):
    results.append((name, bool(ok))); print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  " + str(detail)[:500]))

def upper(): return str(uuid.uuid4()).upper()

t1, t2, t4 = login("13900000001"), login("13900000002"), login("13900000004")

# ---------- CITY r491 ----------
st, cur = call(t1, "GET", f"/api/city-game/current?regionId={REGION}")
board = cur["data"]["board"]
check("current carries mapRead descriptor", cur["data"].get("mapRead", {}).get("contract") == "CITY_BOUNDED_MAP_V1", cur)
common = {"regionId": REGION, "releaseId": 910002, "contentHash": board["rulesHash"]}
def team_cmd(kind, version, team=None):
    body = dict(common, kind=kind, requestId=str(uuid.uuid4()), expectedVersion=version)
    if team: body["teamId"] = team
    return call(t1, "POST", "/api/city-game/team-participation/command", body)
st, r = team_cmd("CREATE", 0); team = r["data"]["teamId"]
st, r = team_cmd("LOCK", r["data"]["teamVersion"], team)
st, r = team_cmd("CONFIRM", r["data"]["teamVersion"], team); check("r491 team ready", st == 200, r)
st, s = call(t1, "GET", f"/api/city-game/action-budget/state?regionId={REGION}")
d = s["data"]; check("budget AVAILABLE V2 version 0", d["status"] == "AVAILABLE" and d["contract"] == "CITY_ACTION_BUDGET_V2" and d["version"] == 0, d)
roll = {"kind": "ROLL", "requestId": "smoke-r491-roll-1", "regionId": REGION, "teamId": team, "contentHash": board["rulesHash"],
        "expectedTeamVersion": d["teamVersion"], "releaseId": 910002, "expectedVersion": 0}
st, r1 = call(t1, "POST", "/api/city-game/action-budget/command", roll)
check("budget ROLL recorded, no effects", st == 200 and r1["data"]["result"]["effectsStatus"] == "NOT_EXECUTED" and r1["data"]["result"]["afterUnits"] == 5, r1)
st, r2 = call(t1, "POST", "/api/city-game/action-budget/command", roll); check("ROLL replay identical", r2["data"] == r1["data"], r2)
st, rr = call(t1, "GET", "/api/city-game/action-budget/request/smoke-r491-roll-1"); check("budget receipt read", st == 200 and rr["data"] == r1["data"], rr)
st, rx = call(t1, "POST", "/api/city-game/action-budget/command", dict(roll, requestId="smoke-r491-roll-x")); check("stale budget version → 409 receipt", st == 409 and rx.get("errorCode") == "VERSION_CONFLICT", rx)

q = f"regionId={REGION}&boardId={BOARD}&seasonId={board['seasonId']}&rulesReleaseId={board['rulesReleaseId']}&revision={board['revision']}"
vp = "&south=31.2&west=121.4&north=31.3&east=121.5&datum=GCJ02&zoom=14&layers=MERCHANT,POI&limit=50"
st, om = call(t1, "GET", "/api/city-game/state/snapshot?" + q + vp + "&sync=TRANSACTIONAL_EVENTS_V1")
check("ordered map snapshot on an OPEN board", st == 200 and om["data"]["kind"] == "SNAPSHOT" and om["data"]["snapshot"]["board"]["lifecycle"] == "OPEN", om)
cursor = om["data"]["cursor"]
st, pr = call(t1, "GET", "/api/city-game/player-rules?" + q + f"&gameId={board['gameId']}&rulesHash={board['rulesHash']}&membership=JOINED"
              f"&participationId={om['data']['snapshot']['participation']['participationId']}&membershipVersion={om['data']['snapshot']['participation']['membershipVersion']}")
check("player rules readable while OPEN (lifecycle rule)", st == 200 and len(pr["data"]["sections"]) == 14, pr)

st, a = call(t1, "GET", f"/api/city-game/arrival-evidence/state?regionId={REGION}")
check("arrival READY, one merchant point", a["data"]["status"] == "READY" and len(a["data"]["points"]) == 1 and a["data"]["points"][0]["canIssue"], a)
issue = {"requestId": upper(), "kind": "ISSUE", "regionId": REGION, "teamId": team, "pointId": "rp-b", "releaseId": 910002,
         "contentHash": board["rulesHash"], "expectedTeamVersion": a["data"]["teamVersion"], "expectedVersion": 0}
st, iss = call(t1, "POST", "/api/city-game/arrival-evidence/command", issue)
code, ch = iss["data"]["displayCode"], iss["data"]["challenge"]
check("challenge ISSUED with display code", st == 200 and len(code) == 32 and ch["state"] == "ISSUED", iss)
st, r = call(t1, "POST", "/api/city-game/arrival-evidence/command", {"requestId": upper(), "kind": "SUBMIT", "challengeId": ch["challengeId"], "expectedVersion": 1, "displayCode": code})
check("member SUBMIT", st == 200 and r["data"]["challenge"]["state"] == "SUBMITTED", r)
st, m = call(t2, "GET", "/api/city-game/arrival-evidence/merchant"); check("store sees pending", st == 200 and len(m["data"]["pending"]) >= 1, m)
st, r = call(t1, "GET", "/api/city-game/arrival-evidence/merchant"); check("player is not store staff → 403", st == 403, r)
st, cf = call(t2, "POST", "/api/city-game/arrival-evidence/command", {"requestId": upper(), "kind": "CONFIRM", "challengeId": ch["challengeId"],
              "expectedVersion": 2, "displayCode": code, "reason": "店员现场核对到店码"})
check("store CONFIRM → evidence", st == 200 and cf["data"]["evidence"]["assurance"] == "MERCHANT_ATTESTED", cf)
evidence = cf["data"]["evidence"]["evidenceId"]
st, ev = call(t1, "GET", f"/api/city-game/arrival-evidence/evidence/{evidence}"); check("evidence read by its member", st == 200, ev)

def task(domain, kind, task_id, version, instance=None, option=None, with_evidence=False):
    body = {"requestId": upper(), "regionId": REGION, "kind": kind, "releaseId": 910002, "contentHash": board["rulesHash"], "teamId": team,
            "expectedTeamVersion": a["data"]["teamVersion"], "taskId": task_id, "expectedVersion": version}
    if with_evidence: body["evidenceId"] = evidence
    if instance: body.update(instanceId=instance, optionId=option)
    return call(t1, "POST", f"/api/city-game/{domain}/command", body)
st, r = task("onsite-tasks", "ACCEPT", "ot-1", 0, with_evidence=True); check("onsite ACCEPT with evidence", st == 200, r)
st, os_ = call(t1, "GET", f"/api/city-game/onsite-tasks/state?regionId={REGION}")
inst = next(t for t in os_["data"]["tasks"] if t["definition"]["taskId"] == "ot-1")["instanceId"]
st, r = task("onsite-tasks", "SUBMIT", "ot-1", 1, inst, "b", True)
check("onsite SUBMIT right answer → PASSED result", st == 200 and r["data"]["result"]["outcome"] == "PASSED", r)
st, res = call(t1, "GET", f"/api/city-game/onsite-tasks/result/{r['data']['result']['resultId']}"); check("onsite result read", st == 200, res)
st, h = call(t1, "GET", f"/api/city-game/task-history?regionId={REGION}"); check("task history own onsite", st == 200 and len(h["data"]["items"]) == 1, h)
st, hd = call(t1, "GET", f"/api/city-game/task-history/instance/{inst}?regionId={REGION}"); check("task history detail", st == 200 and len(hd["data"]["attempts"]) == 1, hd)
st, r = task("neutral-tasks", "ACCEPT", "nt-1", 0); check("neutral ACCEPT", st == 200 and r["data"]["task"]["status"] == "ACTIVE", r)
ninst = r["data"]["task"]["instanceId"]
st, r = task("neutral-tasks", "SUBMIT", "nt-1", 1, ninst, "b"); check("neutral PASSED", st == 200 and r["data"]["task"]["status"] == "PASSED", r)

st, aq = call(t1, "GET", f"/api/city-game/acquisition/state?regionId={REGION}")
cand = aq["data"]["candidates"]; check("acquisition candidate QUOTE_AVAILABLE", cand and cand[0]["eligibility"] == "QUOTE_AVAILABLE", aq)
st, qt = call(t1, "POST", "/api/city-game/acquisition/command", {"requestId": upper(), "kind": "QUOTE", "regionId": REGION, "instanceId": inst})
quote = qt["data"]["quote"]; check("quote has no effect", st == 200 and qt["data"]["effectsStatus"] == "NOT_EXECUTED", qt)
st, qr = call(t1, "GET", f"/api/city-game/acquisition/quote/{quote['quoteId']}"); check("quote read", st == 200 and qr["data"]["quote"]["quoteHash"] == quote["quoteHash"], qr)
confirm = {"requestId": quote["confirmRequestId"], "kind": "CONFIRM", "regionId": REGION, "quoteId": quote["quoteId"], "quoteHash": quote["quoteHash"], "confirmed": True}
st, cfm = call(t1, "POST", "/api/city-game/acquisition/command", confirm)
check("CONFIRM: title, points 100 → 70", st == 200 and cfm["data"]["title"]["pointsBefore"] == 100 and cfm["data"]["title"]["pointsAfter"] == 70, cfm)
st, again = call(t1, "POST", "/api/city-game/acquisition/command", confirm); check("CONFIRM replay, charged once", again["data"] == cfm["data"], again)
st, ti = call(t1, "GET", f"/api/city-game/acquisition/titles?regionId={REGION}"); check("own titles", len(ti["data"]["titles"]) == 1, ti)
st, dl = call(t1, "GET", "/api/city-game/state/deltas?" + q + vp + f"&sync=TRANSACTIONAL_EVENTS_V1&cursor={cursor}")
ev0 = dl["data"]["batches"][0]["events"][0] if dl.get("data", {}).get("batches") else {}
check("ordered map delta: SLOT → OUR_TEAM", st == 200 and ev0.get("component") == "SLOT" and ev0["point"]["ownership"] == "OUR_TEAM", dl)

st, av = call(t2, "GET", f"/api/city-game/merchant/operations/availability?regionId={REGION}")
check("merchant relation AVAILABLE", st == 200 and av["data"]["availability"] == "AVAILABLE", av)
intent = {"requestId": upper(), "expectedVersion": av["data"]["version"], "regionId": REGION, "kind": "PAUSE_MERCHANT", "reason": "测试：店内装修", "approvalHash": av["data"]["approvalHash"]}
st, iv = call(t2, "POST", "/api/city-game/merchant/operations/intent", intent)
check("merchant intent → IN_REVIEW, runtime untouched", st == 200 and iv["data"]["status"] == "IN_REVIEW" and iv["data"]["runtimeApplied"] is False, iv)
st, ir = call(t2, "GET", f"/api/city-game/merchant/operations/request/{intent['requestId']}"); check("merchant receipt", st == 200 and ir["data"]["originalRequestId"] == intent["requestId"], ir)

st, page = call(t1, "GET", "/api/city-game/points?" + q + "&south=31.2&west=121.4&north=31.3&east=121.5&datum=GCJ02&zoom=14&layers=MERCHANT,PLATFORM_VIRTUAL,POI")
check("bounded points page", st == 200 and page["data"]["mapContract"] == "CITY_BOUNDED_MAP_V1" and page["data"]["complete"], page)
pq = q + "&south=31.2&west=121.4&north=31.3&east=121.5&datum=GCJ02&zoom=14&layers=MERCHANT,PLATFORM_VIRTUAL,POI" + f"&mapWatermark={page['data']['watermark']}&mapPage=1"
st, sv = call(t1, "GET", "/api/city-value/visits/services?" + pq)
check("value services: the public POI point", st == 200 and [s["poiId"] for s in sv["data"]["services"]] == [990102], sv)
pagefields = dict(x.split("=", 1) for x in pq.split("&"))
st, vb = call(t1, "POST", "/api/city-value/visits", {"requestId": "smoke-visit-1", "page": pagefields, "pointId": "rp-a", "poiId": 990102})
check("value visit begins", st == 200 and vb["data"]["status"] == "BOUND", vb)
st, fb = call(t1, "POST", "/api/city-value/visits/feedback", {"visitId": vb["data"]["visitId"], "kind": "SERVICE_RATING", "rating": 80})
check("value feedback closes, no points", st == 200 and fb["data"]["closed"] and fb["data"]["netPoints"] == "UNAVAILABLE", fb)
st, wt = call(t1, "GET", "/api/city/walking/target?poi_id=990101")
check("walking target WGS84, bare JSON", st == 200 and wt.get("datum") == "wgs84" and wt["authorityRevision"].startswith("city:990101:"), wt)

# ---------- profile / play ----------
st, dr = call(None, "GET", "/api/user/deregister/result?resultToken=" + "0" * 64); check("deregister result: unknown token → 404", st == 404 and dr.get("errorCode") == "DEREGISTER_RESULT_NOT_FOUND", dr)
st, dr = call(None, "GET", "/api/user/deregister/result"); check("deregister result: no token, no session → 401", st == 401, dr)
st, dr = call(t4, "GET", "/api/user/deregister/result"); check("deregister result: own session → NORMAL", st == 200 and dr["data"]["status"] == "NORMAL", dr)
rid = str(uuid.uuid4()).upper()
st, co = call(t1, "GET", f"/api/play/city-orientation/receipt?sessionId=1&nodeId=1&requestId={rid}"); check("orientation receipt PENDING", st == 200 and co["data"]["outcome"] == "PENDING", co)
st, co = call(t1, "POST", "/api/play/city-orientation/answer", {"answer": "外滩", "expectedVersion": 0, "nodeId": 1, "requestId": rid, "sessionId": 999999999})
check("orientation answer on a run not mine → 404", st == 404 and co.get("errorCode") == "CITY_ORIENTATION_SESSION_NOT_FOUND", co)

# ---------- location share ----------
st, cx = call(t1, "GET", "/api/location-share/personal/context?recipientId=9004")
check("share context eligible (mutual follow, published policy)", st == 200 and cx["data"]["eligible"], cx)
st, cx2 = call(t1, "GET", "/api/location-share/personal/context?recipientId=9002"); check("not mutual → ineligible", cx2["data"]["unavailableReason"] == "NOT_MUTUAL_FOLLOW", cx2)
pol = cx["data"]["policy"]; key = str(uuid.uuid4())
create = {"requestKey": key, "recipientId": 9004, "policyId": pol["policyId"], "policyVersion": pol["version"], "policyDigest": pol["digest"],
          "modeChoiceId": "coarse", "durationChoiceId": "m15", "foregroundOnly": True,
          "acknowledgedDisclosures": ["audience", "precision", "duration", "foregroundOnly", "stopAnytime", "alreadySeenCannotBeRecalled"]}
st, g = call(t1, "POST", "/api/location-share/personal/grants", create); check("grant ACTIVE", st == 200 and g["data"]["state"] == "ACTIVE", g)
gid = g["data"]["grant"]["grantId"]
import time
st, pb = call(t1, "POST", f"/api/location-share/personal/grants/{gid}/observations",
              {"sequence": 1, "observedAtMs": int(time.time() * 1000), "datum": "WGS84", "latitude": 31.23456, "longitude": 121.47891, "foreground": True})
check("publish fix", st == 200 and pb["data"]["sequence"] == 1, pb)
st, rc = call(t4, "GET", "/api/location-share/personal/received")
obs = rc["data"]["entries"][0]["observation"] if rc["data"]["entries"] else {}
check("recipient sees coarse cell only", st == 200 and abs(obs.get("latitude", 0) - 31.23) < 1e-9, rc)
st, rv = call(t1, "POST", f"/api/location-share/personal/requests/{key}/revoke", {}); check("owner revokes", rv["data"]["state"] == "REVOKED", rv)
st, ev = call(t4, "GET", "/api/location-share/personal/received/events?cursor=0")
check("recipient feed ends with INVALIDATED", st == 200 and ev["data"]["events"][-1]["kind"] == "INVALIDATED", ev)

# ---------- bundle commerce ----------
st, sh = call(t1, "GET", "/api/free-explore/bundle/shelf"); check("bundle shelf", sh.get("code") == 200 and any(i["topicId"] == "990101" for i in sh["data"]["items"]), sh)
st, wl = call(t1, "GET", "/api/free-explore/bundle/wallet"); check("my bundles (wallet)", wl.get("code") == 200 and len(wl["data"]["orders"]) >= 1, wl)
st, vo = call(t1, "GET", "/api/free-explore/bundle/voucher?registrationId=990201"); check("voucher", vo.get("code") == 200 and vo["data"]["mainCode"] == "TESTVC990201", vo)
st, vo4 = call(t4, "GET", "/api/free-explore/bundle/voucher?registrationId=990201"); check("voucher of others → BUNDLE_NOT_FOUND", vo4.get("businessCode") == "BUNDLE_NOT_FOUND", vo4)
st, vv = call(t1, "GET", "/api/free-explore/bundle/voucher/version?registrationId=990201"); check("voucher version", len(vv["data"]["voucherVersion"]) == 16, vv)
st, rq = call(t1, "GET", "/api/free-explore/bundle/order/refund-quote?registrationId=990201"); check("refund QUOTE read", rq.get("code") == 200, rq)
st, rp = call(t1, "GET", "/api/free-explore/bundle/order/refund?registrationId=990201"); check("refund progress NONE", rp["data"]["state"] == "NONE", rp)
st, rf = call(t1, "POST", "/api/free-explore/bundle/order/refund", {"registrationId": 990201, "unitIds": [990501], "idempotencyKey": "k1", "expectedRoute": "ORIGINAL", "expectedAmount": "1"})
check("refund write refused", rf.get("businessCode") == "BUNDLE_MONEY_WRITE_DISABLED", rf)
st, tf = call(t1, "POST", "/api/free-explore/bundle/transfer", {"registrationId": 990201, "idempotencyKey": "t1"}); check("transfer write refused", tf.get("businessCode") == "BUNDLE_MONEY_WRITE_DISABLED", tf)
st, rvw = call(t1, "POST", "/api/free-explore/bundle/review", {"registrationId": "990201", "rating": 5, "content": "好", "idempotencyKey": "rv-smoke"})
check("review needs a redeemed unit", rvw.get("businessCode") == "BUNDLE_REVIEW_NOT_ELIGIBLE", rvw)
st, rn = call(t1, "POST", "/api/free-explore/bundle/renewal-intent", {"registrationId": "990201", "intent": "MAYBE", "note": "看看", "idempotencyKey": "rn-smoke"})
check("renewal intent recorded", rn.get("code") == 200 and rn["data"]["intent"] == "MAYBE", rn)
st, ap = call(t1, "POST", "/api/free-explore/bundle/voucher/appeal", {"registrationId": "990201", "unitId": "990501", "reasonCode": "OTHER", "note": "测试", "evidenceRefs": [], "idempotencyKey": "ap-smoke"})
check("appeal recorded OPEN, NOT_APPLIED", ap.get("code") == 200 and ap["data"]["applyState"] == "NOT_APPLIED", ap)

# ---------- topics ----------
def png():
    raw = b"".join(b"\x00" + b"\xff\x80\x00" * 4 for _ in range(4))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 4, 4, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
boundary = "smoke" + uuid.uuid4().hex
mp = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"c.png\"\r\nContent-Type: image/png\r\n\r\n").encode() + png() + f"\r\n--{boundary}--\r\n".encode()
st, up = call(t1, "POST", "/api/topic/cover/upload", raw=(mp, f"multipart/form-data; boundary={boundary}"))
check("cover upload sanitised", st == 200 and up["data"]["kind"] == "OWNED_TOPIC_COVER_V1", up)
st, cc = call(t1, "GET", "/api/topic/cover/selection/current?topicId=990101"); check("cover current NONE", st == 200 and cc["data"]["selectionState"] == "NONE", cc)
st, c4 = call(t4, "GET", "/api/topic/cover/selection/current?topicId=990101"); check("not the author → 403", st == 403, c4)
sel = {"topicId": 990101, "expectedConfigVersion": cc["data"]["topicConfigVersion"], "expectedSelectionVersion": 0, "expectedContentSlotId": cc["data"]["contentSlotId"],
       "requestId": upper(), "assetId": up["data"]["assetId"], "sourceVersion": up["data"]["sourceVersion"], "contentHash": up["data"]["contentHash"]}
st, sr = call(t1, "POST", "/api/topic/cover/selection", sel); check("cover selected, config +1", st == 200 and sr["data"]["topicConfigVersion"] == sel["expectedConfigVersion"] + 1, sr)
st, ss = call(t1, "POST", "/api/topic/cover/selection/status", sel); check("cover status replays", ss["data"] == sr["data"], ss)
st, ct = call(t1, "GET", f"/api/topic/cover/{up['data']['assetId']}/content?sourceVersion={up['data']['sourceVersion']}&contentHash={up['data']['contentHash']}")
check("author-only content bytes match hash", st == 200 and hashlib.sha256(ct).hexdigest() == up["data"]["contentHash"], st)
st, al = call(t1, "GET", "/api/topic/attribute-author?topicId=990101&scope=PERSONAL")
check("attribute-author list with root configVersion", st == 200 and al.get("topicId") == 990101 and "configVersion" in al, al)
cv = al["configVersion"]
body = json.dumps({"topicId": 990101, "expectedConfigVersion": cv, "scope": "PERSONAL",
                   "keys": [{"stateKey": "counter.courage", "keyType": "counter", "scope": "run", "label": "勇气", "initialValue": 1, "minValue": 0, "maxValue": 10}]},
                  ensure_ascii=False, separators=(",", ":"))
st, va = call(t1, "POST", "/api/topic/attribute-author/validate", raw=(body.encode(), "application/json"))
check("attribute validate (no write)", st == 200 and va["configVersion"] == cv, va)
reqid = str(uuid.uuid4())
sha = hashlib.sha256(("POST\napi/topic/attribute-author\n" + body).encode()).hexdigest()
hdr = {"X-Attribute-Request-Protocol": "original-attribute-author-request-v1", "X-Attribute-Request-Id": reqid, "X-Attribute-Request-SHA256": sha, "X-Attribute-Session-Epoch": "1"}
st, sv2 = call(t1, "POST", "/api/topic/attribute-author", raw=(body.encode(), "application/json"), headers=hdr)
check("attribute save: configVersion +1, receipt COMMITTED", st == 200 and sv2["configVersion"] == cv + 1 and sv2["requestReceipt"]["state"] == "COMMITTED", sv2)
st, rq2 = call(t1, "GET", f"/api/topic/attribute-author/request?topicId=990101&expectedConfigVersion={cv}&scope=PERSONAL&requestId={reqid}"
               f"&protocolVersion=original-attribute-author-request-v1&sessionEpoch=1&method=POST&payloadSHA256={sha}")
check("attribute original receipt", st == 200 and rq2["requestReceipt"]["state"] == "COMMITTED", rq2)
st, mpf = call(t1, "GET", f"/api/topic/module-preflight?topicId=990101&scope=PERSONAL&expectedConfigVersion={cv + 1}")
check("module preflight report", st == 200 and mpf["data"]["ready"] == (not any(i["level"] == "error" for i in mpf["data"]["issues"])), mpf)
payload = json.dumps({"id": 990101}, separators=(",", ":"), sort_keys=True).encode()
er = {"protocolVersion": "original-project-edit-request-v1", "requestId": str(uuid.uuid4()), "originalPath": "api/topic/update",
      "payloadSHA256": hashlib.sha256(payload).hexdigest(), "payloadBase64Chunks": [base64.b64encode(payload).decode()]}
st, es = call(t1, "POST", "/api/topic/edit-requests/submit", er)
check("edit request recorded (original writer decided)", st == 200 and es["data"]["state"] in ("COMMITTED", "REJECTED"), es)
st, erd = call(t1, "POST", "/api/topic/edit-requests/read", {k: er[k] for k in ("protocolVersion", "requestId", "originalPath", "payloadSHA256")} | {"scope": "PERSONAL"})
check("edit request read returns the same receipt", st == 200 and erd["data"]["state"] == es["data"]["state"], erd)

passed = sum(ok for _, ok in results)
print(f"{passed}/{len(results)} passed")
sys.exit(0 if passed == len(results) else 1)

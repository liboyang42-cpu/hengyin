"""HTTP smoke for app gaps A on the TEST copy (port 8131, schema cy_gaps_a, redis db 11). TEST only; run after seed_app_gaps_a.py."""
import json, subprocess, urllib.request, urllib.error, uuid

B = "http://127.0.0.1:8131"
BOARD, REGION, HASH_SRC = "gapsa-board-1", "gaps-a-test", None
results = []

def login(phone):
    subprocess.run(["docker", "exec", "be-int-redis", "redis-cli", "-n", "11", "SET", f"sms_{phone}_code", '"123456"'], capture_output=True)
    req = urllib.request.Request(B + "/api/login/phone", data=f"phone={phone}&code=123456".encode(), method="POST")
    return json.loads(urllib.request.urlopen(req).read())["token"]

def call(tok, method, path, body=None, form=None):
    data, headers = None, {"Authorization": "Bearer " + tok, "Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode(); headers["Content-Type"] = "application/json"
    if form is not None:
        data = form.encode(); headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(B + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r: return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b"{}")

def check(name, ok, detail=""):
    results.append((name, bool(ok))); print(("PASS " if bool(ok) else "FAIL ") + name + ("" if ok else "  " + str(detail)[:400]))

t1, t2, t3 = login("13900000001"), login("13900000002"), login("13900000003")
st, cur = call(t1, "GET", f"/api/city-game/current?regionId={REGION}")
board = cur["data"]["board"]
check("current AVAILABLE NOT_JOINED", st == 200 and cur["data"]["participationStatus"] == "NOT_JOINED", cur)
st, r = call(t1, "GET", "/api/city-game/current?regionId=nowhere")
check("current NO_CURRENT_BOARD", r["data"]["status"] == "NO_CURRENT_BOARD", r)
common = {"regionId": REGION, "releaseId": 910001, "contentHash": board["rulesHash"]}

def cmd(tok, kind, version, team=None, invite=None, rid=None):
    body = dict(common, kind=kind, requestId=rid or str(uuid.uuid4()), expectedVersion=version)
    if team: body["teamId"] = team
    if invite: body["inviteCode"] = invite
    return call(tok, "POST", "/api/city-game/team-participation/command", body)

st, r = cmd(t1, "CREATE", 0, rid="smoke-create-1"); check("CREATE 200", st == 200 and r["data"]["requestStatus"] == "COMPLETE", r)
team = r["data"]["teamId"]
st, r2 = cmd(t1, "CREATE", 0, rid="smoke-create-1"); check("CREATE replay same receipt", st == 200 and r2["data"]["requestId"] == "smoke-create-1" and r2["data"]["teamVersion"] == 1, r2)
st, rc = call(t1, "GET", "/api/city-game/team-participation/request/smoke-create-1"); check("original receipt read", st == 200 and rc["data"]["requestId"] == "smoke-create-1", rc)
st, s = call(t1, "GET", f"/api/city-game/team-participation/state?regionId={REGION}")
invite = s["data"]["team"]["inviteCode"]; check("team state PENDING + invite", s["data"]["status"] == "PENDING" and invite, s)
st, iv = call(t2, "GET", f"/api/city-game/team-participation/invite?regionId={REGION}&code={invite}"); check("invite preview", st == 200 and iv["data"]["teamId"] == team, iv)
st, r = cmd(t2, "JOIN", s["data"]["team"]["version"], team, invite); check("JOIN", st == 200, r)
st, r = cmd(t1, "LOCK", r["data"]["teamVersion"], team); check("LOCK", st == 200, r)
v = r["data"]["teamVersion"]
st, r = cmd(t1, "CONFIRM", v, team); v = r["data"]["teamVersion"]
st, r = cmd(t2, "CONFIRM", v, team); check("both CONFIRM", st == 200, r)
st, r = cmd(t1, "CONFIRM", r["data"]["teamVersion"], team); check("double confirm → 409", st == 409 and r.get("errorCode") == "CITY_ALREADY_CONFIRMED", r)
# second team
st, r = cmd(t3, "CREATE", 0); team3 = r["data"]["teamId"]
st, r = cmd(t3, "LOCK", 1, team3); st, r = cmd(t3, "CONFIRM", r["data"]["teamVersion"], team3); check("team 3 ready", st == 200, r)

st, cur = call(t1, "GET", f"/api/city-game/current?regionId={REGION}")
check("current JOINED", cur["data"]["participationStatus"] == "JOINED", cur)
q = f"regionId={REGION}&boardId={BOARD}&seasonId={board['seasonId']}&rulesReleaseId={board['rulesReleaseId']}&revision={board['revision']}"
st, part = call(t1, "GET", "/api/city-game/participation?" + q); participation = part["data"]["participation"]
check("participation read", participation and participation["participationId"], part)
st, pts = call(t1, "GET", "/api/city-game/points?" + q); check("points read complete", pts["data"]["complete"] is True and len(pts["data"]["points"]) == 6, pts)
st, r = call(t1, "GET", "/api/city-game/points?" + q.replace("revision=1", "revision=2")); check("stale tuple → 409 CITY_SNAPSHOT_CHANGED", st == 409 and r.get("errorCode") == "CITY_SNAPSHOT_CHANGED", r)

def snap(tok):
    return call(tok, "GET", f"/api/city-game/game/snapshot?boardId={BOARD}")[1]["data"]

def prop(sn, pid):
    return next(p for p in sn["properties"] if p["propertyId"] == pid)

def act(tok, action, target, version, quote=None, rid=None):
    sn = snap(tok)
    body = {"action": action, "boardId": BOARD, "participationId": participation["participationId"] if tok != t3 else p3,
            "targetId": target, "roundSeq": sn["roundSeq"], "expectedVersion": version(sn), "requestId": rid or str(uuid.uuid4())}
    if quote: body.update(quoteId=quote["quoteId"], quoteVersion=quote["quoteVersion"], payerMemberId=quote["payerMemberId"])
    return call(tok, "POST", "/api/city-game/game/action", body)

st, part3 = call(t3, "GET", "/api/city-game/participation?" + q); p3 = part3["data"]["participation"]["participationId"]
sn = snap(t1); check("game snapshot round 1, budget 6", sn["roundSeq"] == 1 and sn["rules"]["actionsRemaining"] == 6 and sn["memberPoints"] == 100, sn)
landed = None
for i in range(6):
    st, rr = act(t1, "ROLL_ROUND", BOARD, lambda s: s["boardVersion"], rid=f"smoke-roll-{i}")
    landed = snap(t1)["team"]["landedPropertyId"]
    if landed and prop(snap(t1), landed)["role"] == "OPERABLE": break
check("server roll APPLIED with diceFace", rr["data"]["decision"] == "APPLIED" and 1 <= rr["data"]["diceFace"] <= 6, rr)
st, again = call(t1, "GET", f"/api/city-game/game/action/smoke-roll-0"); check("receipt readback", again["data"]["requestId"] == "smoke-roll-0", again)
sn = snap(t1)
if sn["roundSeq"] and landed and prop(sn, landed)["role"] == "OPERABLE":
    st, rr = act(t1, "ACCEPT_TASK", landed, lambda s: prop(s, landed)["stateVersion"]); check("accept task", rr["data"]["decision"] == "APPLIED", rr)
    st, quote = call(t1, "POST", "/api/city-game/game/quote", {"action": "ACQUIRE_PROPERTY", "boardId": BOARD, "targetId": landed})
    check("quote 30 points, payer = me", quote["data"]["memberPointsCost"] == 30, quote)
    st, rr = act(t1, "ACQUIRE_PROPERTY", landed, lambda s: prop(s, landed)["stateVersion"], quote["data"])
    check("occupy APPLIED", rr["data"]["decision"] == "APPLIED", rr)
    check("points 100 → 70", snap(t1)["memberPoints"] == 70, snap(t1)["memberPoints"])
else:
    check("landed on an operable property within budget", False, landed)
st, d = call(t1, "GET", f"/api/city-game/game/deltas?boardId={BOARD}&seasonId={board['seasonId']}&after=0")
seqs = [x["sequence"] for x in d["data"]]; check("deltas contiguous", seqs == list(range(1, len(seqs) + 1)) and seqs, seqs)
import time
fq = f"boardId={BOARD}&regionId={REGION}&seasonId={board['seasonId']}&rulesVersion=v1&queryId=q1&asOf={int(time.time()*1000)}"
st, fr = call(t1, "GET", "/api/city-game/fairness/report?" + fq)
check("fairness report candidate envelope", st == 200 and fr.get("candidate") is True and fr["data"]["formulaId"] == "city-fairness-cohort-v1", fr)
st, fo = call(t1, "GET", "/api/city-game/fairness/outing?" + fq); check("fairness outing own rows", st == 200 and len(fo["data"]["outings"]) >= 1, fo)
st, fx = call(t1, "GET", "/api/city-game/fairness/report?" + fq.replace("rulesVersion=v1", "rulesVersion=v9"))
check("fairness scope → 403 scope-rejected", st == 403 and fx["data"]["errorCode"] == "scope-rejected", fx)
# other areas
st, fl = call(t1, "GET", "/api/user/follow-list?kind=following&cursor=0&limit=50"); check("follow-list", st == 200 and fl["data"]["owner_id"] > 0, fl)
st, ra = call(t1, "GET", "/api/play/route-action?sessionId=1&memberId=1&actionId=x"); check("route-action not mine → 403", ra.get("code") == 403, ra)
st, eg = call(t1, "POST", "/api/play/egg/collect", form="eggId=990001&topicId=1"); check("egg route closed → 410 ROUTE_EGG_OFFLINE", eg.get("code") == 410 and eg.get("errorCode") == "ROUTE_EGG_OFFLINE", eg)
st, tw = call(t1, "GET", "/api/play/advanced/time-window?activityId=0&topicId=1&nodeId=1"); check("time-window answers (no such node → 400)", tw.get("code") in (400, 409), tw)
st, un = call("bad-token", "GET", f"/api/city-game/game/snapshot?boardId={BOARD}"); check("no token → 401", st == 401 or un.get("code") == 401, un)
print(f"{sum(ok for _, ok in results)}/{len(results)} passed")

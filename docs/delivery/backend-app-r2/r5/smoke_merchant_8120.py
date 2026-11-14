#!/usr/bin/env python3
"""be-gap-merchant curl checks on 8127 / cy_e2e_r2 (TEST only). Prints one line per check."""
import hashlib, json, subprocess, sys, urllib.error, urllib.parse, urllib.request, uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8120"
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]


def call(method, path, token=None, body=None, form=None, query=None):
    url = BASE + path + ("?" + urllib.parse.urlencode(query) if query else "")
    data, headers = None, {}
    if token:
        headers["Authorization"] = token
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    try:
        return status, json.loads(raw)
    except Exception:
        return status, {"raw": raw[:200].decode(errors="replace")}


def show(name, status, d, extra=""):
    print("%-58s HTTP %s code=%s %s %s" % (name, status, d.get("code"), d.get("errorCode") or "", extra))


def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone, "sms_verify_ip_window_127.0.0.1"], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    show("login %s" % phone, st, d, "role=%s" % (d.get("data") or {}).get("role"))
    return d["token"]


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def apple(fields):
    return json.dumps(fields, sort_keys=True, ensure_ascii=False, separators=(",", ":")).replace("/", "\\/")


m, p, c = login("13900000002"), login("13900000004"), login("13900000003")
data = lambda d: d.get("data") or {}

print("== authoring (W05) ==")
st, d = call("GET", "/api/merchant/authoring/drafts", m); show("GET drafts (merchant)", st, d, "n=%d" % len(data(d).get("drafts", [])))
st, d = call("GET", "/api/merchant/authoring/drafts", p); show("GET drafts (player)", st, d)
rid = str(uuid.uuid4()).upper()
create = {"requestId": rid, "templateId": "store-intro", "productId": 992951, "scene": "PRODUCT"}
st, d = call("POST", "/api/merchant/authoring/drafts", m, create); draft = data(d).get("draftId"); show("POST drafts (create)", st, d, "draftId=%s v=%s" % (draft, data(d).get("currentVersion")))
st, d = call("POST", "/api/merchant/authoring/drafts", m, create); show("POST drafts (same requestId replay)", st, d, "same=%s" % (data(d).get("draftId") == draft))
body = {"business_segment": "MORNING", "duration_minutes": 30, "headcount": 4, "noise_limit": "低声交谈", "photo_limit": "可拍照", "accessibility_alternative": "门口有坡道"}
st, d = call("POST", "/api/merchant/authoring/drafts/" + draft, m, {"expectedVersion": 0, "body": body}); show("POST drafts/{id} save v0->1", st, d, "v=%s bodyHash=%s" % (data(d).get("currentVersion"), str(data(d).get("bodyHash"))[:12]))
st, d = call("POST", "/api/merchant/authoring/drafts/" + draft, m, {"expectedVersion": 0, "body": body}); show("POST drafts/{id} stale expectedVersion", st, d)
st, d = call("GET", "/api/merchant/authoring/drafts/" + draft, m); show("GET drafts/{id}", st, d, "v=%s outcome=%s" % (data(d).get("currentVersion"), data(d).get("publishOutcome")))
st, d = call("POST", "/api/merchant/authoring/drafts/%s/facts" % draft, m, {"version": 1}); show("POST drafts/{id}/facts (store confirms facts)", st, d, data(d).get("outcome"))
st, d = call("GET", "/api/merchant/authoring/drafts/%s/review" % draft, m); show("GET drafts/{id}/review", st, d, "merchantFact=%s" % (data(d).get("merchantFact") or {}).get("outcome"))
prid = str(uuid.uuid4()).upper()
st, d = call("POST", "/api/merchant/authoring/releases", m, {"requestId": prid, "draftId": draft, "version": 1}); release = data(d).get("releaseId"); show("POST releases (publish)", st, d, "outcome=%s releaseId=%s facts=%s" % (data(d).get("outcome"), release, sorted(data(d).get("facts", {}))))
st, d = call("GET", "/api/merchant/authoring/releases", m, query={"requestId": prid}); show("GET releases?requestId", st, d, data(d).get("outcome"))
st, d = call("GET", "/api/merchant/authoring/public", p, query={"merchantId": 8001, "binding": "STORE"}); show("GET public (player)", st, d, "outcome=%s" % data(d).get("outcome"))
st, d = call("GET", "/api/merchant/authoring/main-line", p, query={"merchantId": 8001}); show("GET main-line (no THEME release)", st, d, "outcome=%s" % data(d).get("outcome"))
st, d = call("GET", "/api/merchant/authoring/environment", m, query={"releaseId": release}); show("GET environment", st, d, "branch=%s action=%s" % (data(d).get("branch"), data(d).get("action")))
st, d = call("GET", "/api/merchant/authoring/player/8001/game", p); show("GET player/{merchantId}/game", st, d, "outcome=%s facts=%s" % (data(d).get("outcome"), sorted(data(d).get("facts", {}))))
st, d = call("GET", "/api/merchant/authoring/player/8001/requests/" + str(uuid.uuid4()).upper(), p); show("GET player/{merchantId}/requests/{unknown}", st, d)
jrid = str(uuid.uuid4()).upper()
st, d = call("POST", "/api/merchant/authoring/jobs", m, {"requestId": jrid, "templateId": "store-intro", "draftId": draft, "scene": "PRODUCT"}); show("POST jobs (generate; no model on TEST)", st, d, "outcome=%s err=%s" % (data(d).get("outcome"), data(d).get("errorCode")))
st, d = call("GET", "/api/merchant/authoring/jobs", m, query={"requestId": jrid}); show("GET jobs?requestId", st, d, "outcome=%s" % data(d).get("outcome"))
st, d = call("POST", "/api/merchant/authoring/jobs/cancel", m, {"requestId": jrid}); show("POST jobs/cancel", st, d, "outcome=%s publish=%s" % (data(d).get("outcome"), data(d).get("publishOutcome")))
st, d = call("POST", "/api/merchant/authoring/releases/%s/withdraw" % release, m, {"requestId": str(uuid.uuid4())}); show("POST releases/{id}/withdraw", st, d, data(d).get("outcome"))
st, d = call("GET", "/api/merchant/authoring/player/8001/game", p); show("GET player/{merchantId}/game after withdraw", st, d, "outcome=%s" % data(d).get("outcome"))

print("== draft selection ==")
st, d = call("POST", "/api/merchant/authoring/draft-selection/list", m, {"beforeSourceId": None}); rows = data(d).get("rows", []); show("POST draft-selection/list (owner)", st, d, "rows=%s next=%s" % ([(r["sourceId"], r["state"]) for r in rows], data(d).get("nextBeforeSourceId")))
st, d = call("POST", "/api/merchant/authoring/draft-selection/list", p, {"beforeSourceId": None}); show("POST draft-selection/list (player)", st, d)
if rows and rows[0]["state"] == "READY":
    r = rows[0]
    fields = {"source": r["source"], "memberTemplateId": r["memberTemplateId"], "templateContentHash": r["templateContentHash"]}
    st, d = call("POST", "/api/merchant/authoring/draft-selection/resolve", m, fields); show("POST draft-selection/resolve (current)", st, d, "title=%s" % (data(d).get("row") or {}).get("templateTitle"))
    fields["templateContentHash"] = "f" * 64
    st, d = call("POST", "/api/merchant/authoring/draft-selection/resolve", m, fields); show("POST draft-selection/resolve (changed hash)", st, d)

print("== city value ==")
scope = {"regionId": "gaps-a-test", "boardId": "gapsa-board-1", "seasonId": "s2026w46", "rulesReleaseId": "rel-gapsa-1", "from": "2026-11-13T00:00:00Z", "to": "2026-11-14T00:00:00Z"}
st, d = call("GET", "/api/merchant/city-value/report", m, query=dict(scope, requestId="mgap-report-1")); rep = data(d); show("GET city-value/report", st, d, "status=%s entry=%s success=%s mean=%s" % (rep.get("status"), (rep.get("funnel") or {}).get("entry"), (rep.get("funnel") or {}).get("serviceSuccess"), (rep.get("serviceRatings") or {}).get("mean")))
st, d = call("GET", "/api/merchant/city-value/report", m, query=dict(scope, requestId="mgap-report-1", to="2026-11-15T00:00:00Z")); show("GET city-value/report (requestId reused)", st, d)
st, d = call("GET", "/api/merchant/city-value/result/%s" % rep.get("resultId"), m, query={"regionId": "gaps-a-test", "rulesReleaseId": "rel-gapsa-1"}); show("GET city-value/result/{id}", st, d, "same=%s" % (data(d) == rep))
st, d = call("GET", "/api/merchant/city-value/events", m, query=dict(scope, cursor="")); ev = data(d); show("GET city-value/events", st, d, "snapshot=%s kinds=%s next=%s" % (ev.get("snapshotId"), [e["kind"] for e in ev.get("events", [])], ev.get("nextCursor")))
succ = [e for e in ev.get("events", []) if e["kind"] == "SERVICE_SUCCESS"]
if succ:
    st, d = call("GET", "/api/merchant/city-value/event/%s/benefit" % succ[0]["id"], m, query=dict(scope, snapshotId=ev["snapshotId"])); show("GET city-value/event/{id}/benefit", st, d, "evidence=%s" % data(d).get("evidenceStatus"))
st, d = call("GET", "/api/merchant/city-value/report", p, query=dict(scope, requestId="mgap-report-2")); show("GET city-value/report (player)", st, d)

print("== marketing consent result ==")
st, d = call("GET", "/api/merchant/crm/marketing-consents", p); rows = d.get("data") or []; show("GET crm/marketing-consents (player)", st, d, "rows=%d" % len(rows))
if rows:
    row = rows[0]; crid = "mgap-" + uuid.uuid4().hex[:12]
    st, d = call("POST", "/api/merchant/crm/marketing-consents", p, {"merchantRowId": row["merchantRowId"], "merchantOwnerMemberId": row["merchantOwnerMemberId"], "channel": "IN_APP", "optedIn": True, "requestId": crid}); show("POST crm/marketing-consents (opt in)", st, d)
    q = {"merchantRowId": row["merchantRowId"], "merchantOwnerMemberId": row["merchantOwnerMemberId"], "channel": "IN_APP", "requestId": crid}
    st, d = call("GET", "/api/merchant/crm/marketing-consents/result", p, query=q); show("GET crm/marketing-consents/result", st, d, "outcome=%s event=%s" % (data(d).get("outcome"), data(d).get("eventType")))
st, d = call("GET", "/api/merchant/crm/marketing-consents/result", p, query={"merchantRowId": 8001, "merchantOwnerMemberId": 9002, "channel": "IN_APP", "requestId": "never-sent-1"}); show("GET crm/marketing-consents/result (unseen)", st, d, "outcome=%s" % data(d).get("outcome"))

print("== store NPC ==")
st, d = call("POST", "/api/merchant/npc/voice/script", m, {}); show("POST npc/voice/script", st, d, "lines=%d consentIndex=%s" % (len(data(d).get("script", [])), data(d).get("consentIndex")))
st, d = call("POST", "/api/merchant/npc/voice/script", p, {}); show("POST npc/voice/script (player)", st, d)
st, d = call("POST", "/api/merchant/npc/voice/revoke", m, {"requestId": str(uuid.uuid4()).upper()}); show("POST npc/voice/revoke", st, d, "changed=%s" % data(d).get("changed"))
st, d = call("POST", "/api/merchant/npc/avatar/status", m, {}); show("POST npc/avatar/status", st, d, "available=%s styles=%s" % (data(d).get("available"), data(d).get("styles")))
st, d = call("POST", "/api/merchant/npc/save", m, {"name": "阿猫", "avatar": "https://img.example.test/a.png", "greeting": "欢迎光临"}); show("POST npc/save (existing; store NPC for the avatar)", st, d, str(d.get("msg"))[:30])
st, d = call("POST", "/api/merchant/npc/avatar/generate", m, {"imageUrl": "https://img.example.test/store.png", "style": "CARTOON"}); show("POST npc/avatar/generate", st, d, "status=%s" % data(d).get("status"))
krid = str(uuid.uuid4()).upper()
kbody = {"requestId": krid, "name": "阿猫", "greeting": "欢迎光临", "persona": "热情的店长", "knowledge": "营业到晚上九点，周一休息。"}
st, d = call("POST", "/api/merchant/npc/knowledge-review", m, kbody); show("POST npc/knowledge-review", st, d, "status=%s memberId=%s audit=%s" % (data(d).get("status"), data(d).get("memberId"), data(d).get("auditTaskId")))
st, d = call("POST", "/api/merchant/npc/knowledge-review", m, dict(kbody, persona="冷淡")); show("POST npc/knowledge-review (same id, other body)", st, d)
st, d = call("GET", "/api/merchant/npc/knowledge-review/request", m, query={"requestId": krid}); show("GET npc/knowledge-review/request", st, d, "same4=%s" % all(data(d).get(k) == kbody[k] for k in ("name", "greeting", "persona", "knowledge")))

print("== offer withdrawal ==")
st, d = call("GET", "/api/merchant/offer-withdrawal/request/current", m, query={"merchantId": 8001, "offerId": 1}); show("GET offer-withdrawal/request/current (CITY offer)", st, d, "canWithdraw=%s isOrdinary=%s" % (data(d).get("canWithdraw"), data(d).get("isOrdinary")))
st, d = call("GET", "/api/merchant/offer-withdrawal/request/current", m, query={"merchantId": 8001, "offerId": 992991}); cur = data(d); show("GET offer-withdrawal/request/current (ordinary)", st, d, "canWithdraw=%s" % cur.get("canWithdraw"))
reason = "店面装修/暂停一个月"
intent = {"offerId": 992991, "reason": reason, "reasonSHA256": sha(reason), "expectedSHA256": cur.get("snapshotSHA256")}
orid = str(uuid.uuid4())
wbody = dict(intent, merchantId=8001, requestId=orid, intentSHA256=sha(apple(intent)))
st, d = call("POST", "/api/merchant/offer-withdrawal/request/withdraw", m, wbody); show("POST offer-withdrawal/request/withdraw", st, d, "outcome=%s statusAtWrite=%s" % (data(d).get("outcome"), data(d).get("statusAtWrite")))
st, d = call("GET", "/api/merchant/offer-withdrawal/request/result", m, query={"merchantId": 8001, "offerId": 992991, "requestId": orid}); show("GET offer-withdrawal/request/result", st, d, "outcome=%s" % data(d).get("outcome"))
st, d = call("GET", "/api/merchant/offer-withdrawal/request/current", m, query={"merchantId": 8001, "offerId": 992991}); show("GET offer-withdrawal/request/current (after)", st, d, "status=%s canWithdraw=%s" % (data(d).get("status"), data(d).get("canWithdraw")))
st, d = call("GET", "/api/merchant/offer-withdrawal/request/current", c, query={"merchantId": 8001, "offerId": 992991}); show("GET offer-withdrawal/request/current (club user)", st, d)

print("== legacy aliases ==")
st, d = call("POST", "/api/merchant/insight", m); show("POST merchant/insight -> ai/merchant/insight", st, d, "keys=%s" % sorted(data(d))[:6] if isinstance(d.get("data"), dict) else "")
st, d = call("POST", "/api/merchant/poi/detail", None, {"id": 8001}); show("POST merchant/poi/detail -> public-detail (anon)", st, d, "id=%s" % (data(d).get("id") if isinstance(d.get("data"), dict) else None))
st, d = call("POST", "/api/merchant/shop/apply", p, {}); show("POST merchant/shop/apply -> merchant_registration", st, d, "msg=%s" % str(d.get("msg"))[:40])
st, d = call("POST", "/api/merchant/npc/voice/script", None, {}); show("POST npc/voice/script (signed out)", st, d)

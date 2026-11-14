#!/usr/bin/env python3
"""be-gap-merchant R3 curl checks on 8120 / cy_e2e_r2 (TEST only): W05 source producer -> draft-selection,
CITY value goals / cost / first choice / benefit, avatar provider status. One line per check."""
import json, subprocess, sys, urllib.error, urllib.parse, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8120"
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]
MYSQL = ["docker", "exec", "-i", "be-int-mysql", "mysql", "-uroot", "--default-character-set=utf8mb4", "-N", "cy_e2e_r2"]


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
        with urllib.request.urlopen(req, timeout=60) as r:
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
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone, "sms_verify_ip_window_127.0.0.1", "sms_send_ip_window_127.0.0.1"], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    show("login %s" % phone, st, d, "role=%s" % (d.get("data") or {}).get("role"))
    return d["token"]


def sql(text):
    return subprocess.run(MYSQL, input=text, capture_output=True, text=True).stdout.strip()


m, p = login("13900000002"), login("13900000004")
data = lambda d: d.get("data") or {}

print("== W05 source producer -> draft-selection ==")
st, d = call("POST", "/api/merchant/authoring/catalog", m); show("POST authoring/catalog", st, d, "rows=%s" % [r.get("productId") for r in data(d).get("rows", [])])
st, d = call("POST", "/api/merchant/authoring/product", m, body={"productId": 992951}); show("POST authoring/product", st, d, "skus=%s" % [s.get("skuId") for s in data(d).get("skus", [])])
st, d = call("POST", "/api/merchant/authoring/facts", m, body={"productId": 992951, "skuId": 992952}); facts = data(d)
show("POST authoring/facts", st, d, "factsHash=%s.." % str(facts.get("factsHash"))[:12])
st, d = call("POST", "/api/merchant/authoring/facts", p, body={"productId": 992951, "skuId": 992952}); show("POST authoring/facts (player)", st, d)
constraints = {"durationMinutes": 20, "participantLimit": 4, "openingHours": "10:00-20:00", "staffRequired": False, "filmingAllowed": False,
               "noisePolicy": "安静", "accessibleAlternative": "前台口述", "offlineAlternative": "纸质卡片", "failureExit": "直接结束"}
st, d = call("POST", "/api/ai/template/fill", m, body={"merchantFacts": {"productId": 992951, "skuId": 992952, "factsHash": facts.get("factsHash")},
             "merchantConstraints": constraints, "validationMethod": 1, "extraNote": "店内寻宝"})
show("POST ai/template/fill (store-bound)", st, d, "msg=%s candidate=%s" % (d.get("msg"), data(d).get("candidateId") if isinstance(d.get("data"), dict) else None))
candidate = data(d).get("candidateId") if isinstance(d.get("data"), dict) else None
if not candidate:
    # TEST has no model key, so the store-bound fill cannot reach record(); insert the candidate that record() would write
    # for this model result (validationMethod 1) with the server facts just read, then confirm it through the real route.
    cj = dict(constraints, rewardAuthority="UNCONFIGURED")
    canon = lambda o: json.dumps(dict(sorted(o.items())), ensure_ascii=False, separators=(",", ":"))
    import hashlib
    candidate = sql("INSERT INTO merchant_authoring_candidate(principal_member_id, merchant_row_id, owner_member_id, facts_json, facts_hash, constraints_json, constraints_hash, ai_trace_id, template_json, created_at) "
                    "VALUES (9002, 8001, 9002, '%s', '%s', '%s', '%s', 'test-fixture-trace', '{\"validationMethod\":1}', NOW(3)); SELECT LAST_INSERT_ID();"
                    % (canon(facts).replace("'", "''"), facts["factsHash"], canon(cj), hashlib.sha256(canon(cj).encode()).hexdigest()))
    print("%-58s candidateId=%s (TEST fixture row: no model key on TEST)" % ("candidate", candidate))
fields = {"title": "店内寻宝R3", "description": "找到隐藏菜单", "ruleInstructions": "问店员要线索卡"}
st, d = call("POST", "/api/merchant/authoring/draft", m, body={"candidateId": int(candidate), "acceptedFields": fields}); receipt = data(d)
show("POST authoring/draft (confirm)", st, d, "templateId=%s source=%s draftOnly=%s" % (receipt.get("templateId"), receipt.get("source"), receipt.get("draftOnly")))
st, d = call("POST", "/api/merchant/authoring/draft", m, body={"candidateId": int(candidate), "acceptedFields": fields}); show("POST authoring/draft (replay)", st, d, "same=%s" % (data(d) == receipt))
st, d = call("POST", "/api/merchant/authoring/draft", m, body={"candidateId": int(candidate), "acceptedFields": dict(fields, title="别的")}); show("POST authoring/draft (other text)", st, d)
st, d = call("POST", "/api/merchant/authoring/draft", p, body={"candidateId": int(candidate), "acceptedFields": fields}); show("POST authoring/draft (player)", st, d)
st, d = call("POST", "/api/merchant/authoring/draft-selection/list", m, body={"beforeSourceId": None}); rows = data(d).get("rows", [])
mine = [r for r in rows if receipt.get("source") and r.get("sourceId") == receipt["source"]["sourceId"]]
show("POST draft-selection/list", st, d, "rows=%s produced=%s" % ([(r["sourceId"], r["state"]) for r in rows], mine[0] if mine else None))
if mine:
    st, d = call("POST", "/api/merchant/authoring/draft-selection/resolve", m, body={"source": mine[0]["source"], "memberTemplateId": mine[0]["memberTemplateId"],
                 "templateContentHash": mine[0]["templateContentHash"]})
    show("POST draft-selection/resolve (produced row)", st, d, "title=%s hashMatchesReceipt=%s" % ((data(d).get("row") or {}).get("templateTitle"), mine[0]["templateContentHash"] == receipt.get("templateContentHash")))

print("== CITY value (goals / cost / first choice / benefit) ==")
scope = {"regionId": "gaps-a-test", "boardId": "gapsa-board-1", "seasonId": "s2026w46", "rulesReleaseId": "rel-gapsa-1",
         "from": "2026-11-13T00:00:00Z", "to": "2026-11-14T00:00:00Z"}
st, d = call("GET", "/api/merchant/city-value/report", m, query=dict(scope, requestId="mgap-report-r3")); rep = data(d)
f = rep.get("funnel") or {}
show("GET city-value/report", st, d, "publicGoal=%s cooperativeGoal=%s" % (f.get("publicGoal"), f.get("cooperativeGoal")))
print("%-58s firstChoice=%s cohort=%s" % ("  report (cont.)", rep.get("timeToFirstEffectiveChoice"), rep.get("byCohort")))
print("%-58s visitCost=%s" % ("  report (cont.)", rep.get("visitCost")))
st, d = call("GET", "/api/merchant/city-value/events", m, query=dict(scope, cursor="")); ev = data(d)
show("GET city-value/events", st, d, "snapshot=%s n=%d" % (ev.get("snapshotId"), len(ev.get("events", []))))
for e in [e for e in ev.get("events", []) if e["kind"] == "SERVICE_SUCCESS"]:
    st, d = call("GET", "/api/merchant/city-value/event/%s/benefit" % e["id"], m, query=dict(scope, snapshotId=ev["snapshotId"])); b = data(d)
    show("GET city-value/event/{id}/benefit (%s)" % e["visitId"][-4:], st, d, "evidence=%s reason=%s source=%s record=%s coupon=%s" % (
        b.get("evidenceStatus"), b.get("evidenceReason"), b.get("sourceKind"), b.get("sourceRecordId"), b.get("couponId")))

print("== avatar provider ==")
st, d = call("POST", "/api/merchant/npc/avatar/status", m, body={}); show("POST npc/avatar/status (TEST stub kept)", st, d, "available=%s styles=%s" % (data(d).get("available"), data(d).get("styles")))

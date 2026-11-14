#!/usr/bin/env python3
"""TEST-only curl checks for feature/be-gap-rewards-workshop on 8125 (DB cy_e2e_r2, Redis db 13). Loopback only.
Load fixture_8125.sql.txt first (reset_8125.sql.txt undoes it). Players: 9009 (completed topic 992001), 9004 (pledge reviewer), merchant 9002 (store 8001),
workshop license owner 9003 (owns topics 990013 and 992001)."""
import json, subprocess, sys, urllib.error, urllib.parse, urllib.request, uuid

B = "http://127.0.0.1:8120"
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]
SQL = ["docker", "exec", "be-int-mysql", "mysql", "-uroot", "-N", "cy_e2e_r2", "-e"]
results = []


def call(method, path, tok=None, form=None, js=None, raw=None, q=None):
    url = B + path + ("?" + urllib.parse.urlencode(q) if q else "")
    h = {"Accept": "application/json"}
    data = None
    if tok:
        h["Authorization"] = "Bearer " + tok
    if form is not None:
        data = urllib.parse.urlencode(form).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    if js is not None:
        data = json.dumps(js, sort_keys=True, separators=(",", ":")).encode(); h["Content-Type"] = "application/json; charset=utf-8"
    if raw is not None:
        data = raw.encode(); h["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            st, body = r.status, r.read()
    except urllib.error.HTTPError as e:
        st, body = e.code, e.read()
    try:
        return st, json.loads(body)
    except Exception:
        return st, {"raw": body[:160].decode("utf-8", "replace")}


def check(label, ok, st, d, note=""):
    results.append(ok)
    tail = "" if ok else json.dumps(d, ensure_ascii=False)[:260]
    print("%-58s %s http=%s code=%s %s %s" % (label, "OK " if ok else "ERR", st, d.get("code"), note, tail))
    return d.get("data") if isinstance(d.get("data"), (dict, list)) else {}


def sql(q):
    return subprocess.run(SQL + [q], capture_output=True, text=True).stdout.strip()


def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone, "sms_verify_ip_window_127.0.0.1"], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    check("login %s" % phone, st == 200 and bool(d.get("token")), st, d, "role=%s" % (d.get("data") or {}).get("role"))
    return d.get("token")


player, owner = login("13900000009"), login("13900000003")

# ---------------- workshop professional ----------------
license_id = "w18-paid-970201"
order_no = sql("SELECT order_no FROM workshop_order WHERE id=970201")
install = sql("SELECT request_id FROM workshop_install WHERE draft_id=970301")
st, d = call("POST", "/api/workshop/purchased/professional-targets/list", js={"licenseId": license_id})
check("targets signed out -> 401", st == 401, st, d)
st, d = call("POST", "/api/workshop/purchased/professional-targets/list", owner, js={"licenseId": license_id})
targets = check("targets list (owner's topics)", (d.get("data") or {}).get("schema") == "w18-owned-professional-topic-targets-v1", st, d,
                "items=%d" % len((d.get("data") or {}).get("items") or []))
topic = next((t for t in targets.get("items", []) if t.get("topicId") == 992001), {})
st, d = call("POST", "/api/workshop/purchased/professional-targets/detail", owner, js={"licenseId": license_id, "topicId": 992001})
check("target detail", (d.get("data") or {}).get("ownerModeFingerprint") == topic.get("ownerModeFingerprint"), st, d, "mode=%s" % topic.get("mode"))
st, d = call("POST", "/api/workshop/purchased/professional-targets/list", player, js={"licenseId": license_id})
check("targets with someone else's license -> 404", st == 404, st, d)
st, d = call("POST", "/api/workshop/v2/purchased/install/targets", owner, form={"license_id": order_no})
draft_row = next((t for t in ((d.get("data") or {}).get("items") or []) if t.get("targetDraftId") == 970301), {})
version = sql("SELECT version_id, module_id, content_hash, terms_hash FROM workshop_order o JOIN workshop_version v USING (version_id) WHERE o.id=970201").split()
cmd = {"schema": "w18-paid-professional-text-materialization-command-v1", "requestId": str(uuid.uuid4()), "installationRequestId": install,
       "licenseId": license_id, "moduleId": version[1], "purchasedVersionId": version[0], "contentHash": version[2], "termsHash": version[3],
       "componentHash": "c" * 64, "ownedDraftId": 970301, "currentDraftRevision": draft_row.get("targetRevision", 1),
       "currentDraftPayloadHash": draft_row.get("targetPayloadHash", "0" * 64), "targetTopicId": 992001,
       "targetOwnerModeFingerprint": topic.get("ownerModeFingerprint", "0" * 64), "confirmedMode": topic.get("mode", "CITY_ORIENTATION")}
st, d = call("POST", "/api/workshop/purchased/professional-operations/submit", owner, js=cmd)
op = check("submit -> CREATED (SYSTEM review, guarded private template)", (d.get("data") or {}).get("state") == "CREATED", st, d,
           "templateId=%s" % ((d.get("data") or {}).get("creation") or {}).get("templateId"))
st, d = call("POST", "/api/workshop/purchased/professional-operations/submit", owner, js=cmd)
check("submit replay -> same operation", (d.get("data") or {}).get("commandHash") == op.get("commandHash"), st, d)
st, d = call("POST", "/api/workshop/purchased/professional-operations/status", owner, js={"requestId": cmd["requestId"]})
check("status -> CREATED", (d.get("data") or {}).get("state") == "CREATED", st, d)
st, d = call("POST", "/api/workshop/purchased/professional-operations/status", player, js={"requestId": cmd["requestId"]})
check("status from someone else -> NOT_FOUND", (d.get("data") or {}).get("state") == "NOT_FOUND", st, d)
st, d = call("POST", "/api/workshop/purchased/professional-operations/cancel", owner, js=cmd)
check("cancel a CREATED one -> stays CREATED", (d.get("data") or {}).get("state") == "CREATED", st, d)
bad = dict(cmd, requestId=str(uuid.uuid4()), contentHash="9" * 64)
st, d = call("POST", "/api/workshop/purchased/professional-operations/submit", owner, js=bad)
check("submit with a stale hash -> REJECTED + app code", (d.get("data") or {}).get("rejectionCode") == "CURRENT_SOURCE_OR_TARGET_UNAVAILABLE", st, d)
early = dict(cmd, requestId=str(uuid.uuid4()))
st, d = call("POST", "/api/workshop/purchased/professional-operations/cancel", owner, js=early)
check("cancel before submit -> CANCELLED", (d.get("data") or {}).get("state") == "CANCELLED", st, d)
st, d = call("POST", "/api/workshop/purchased/professional-operations/submit", owner, js=early)
check("late submit of a cancelled id -> CANCELLED", (d.get("data") or {}).get("state") == "CANCELLED", st, d)
st, d = call("POST", "/api/workshop/purchased/professional-operations/history", owner, js={"licenseId": license_id})
check("history (3 intents on this license)", len((d.get("data") or {}).get("items") or []) == 3, st, d, "scanned=%s" % (d.get("data") or {}).get("scannedCount"))
st, d = call("POST", "/api/workshop/purchased/professional-operations/submit", owner, raw='{"schema":"x"}')
check("malformed command -> 400", st == 400, st, d)
print("template row:", sql("SELECT id, member_id, del_flag, draft_status, status, publish_status FROM cms_member_template WHERE id = (SELECT template_id FROM workshop_professional_operation WHERE request_id='%s')" % cmd["requestId"]))
print("events:", sql("SELECT GROUP_CONCAT(CONCAT(actor_type,':',to_status) ORDER BY id) FROM workshop_professional_event"))

print("SUMMARY %d/%d OK" % (sum(results), len(results)))

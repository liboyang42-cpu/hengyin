#!/usr/bin/env python3
"""be-gap-misc curl checks on 8126 / cy_e2e_r2 (a copy of the seeded TEST DB cy_e2e_r2 plus migration 20261119, applied twice),
Redis db 15. Loopback only. Tokens and codes are never printed. Players: 9009 (registration 74), 9004 (registration 75)."""
import base64, json, os, subprocess, sys, urllib.error, urllib.parse, urllib.request, uuid

B = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8120"
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]
SQL = ["docker", "exec", "be-int-mysql", "mysql", "-uroot", "-N", "cy_e2e_r2", "-e"]
results = []


def call(method, path, tok=None, form=None, js=None, raw=None, multipart=None, q=None, bearer=False):
    url = B + path + ("?" + urllib.parse.urlencode(q) if q else "")
    h = {"Accept": "application/json"}
    data = None
    if tok:
        h["Authorization"] = ("Bearer " if bearer else "") + tok
    if form is not None:
        data = urllib.parse.urlencode(form).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    if js is not None:
        data = json.dumps(js, separators=(",", ":"), ensure_ascii=False).encode(); h["Content-Type"] = "application/json"
    if raw is not None:
        data = raw.encode(); h["Content-Type"] = "application/json"
    if multipart is not None:
        boundary = "misc" + uuid.uuid4().hex[:16]
        parts = "".join("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, k, v) for k, v in multipart.items())
        data = (parts + "--%s--\r\n" % boundary).encode(); h["Content-Type"] = "multipart/form-data; boundary=" + boundary
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


def redact(d):
    text = json.dumps(d, ensure_ascii=False)
    for key in ("token", "challengeBase64", "rawNonce", "state", "challengeId", "proofId"):
        if isinstance(d, dict):
            for scope in (d, d.get("data") if isinstance(d.get("data"), dict) else {}):
                if isinstance(scope, dict) and isinstance(scope.get(key), str):
                    text = text.replace(scope[key], "<%s>" % key)
    return text[:300]


def check(label, ok, st, d, note=""):
    results.append(ok)
    print("%-52s %s http=%s code=%s %s | %s" % (label, "OK " if ok else "ERR", st, d.get("code"), note, redact(d)))
    return d.get("data")


def sql(q):
    return subprocess.run(SQL + [q], capture_output=True, text=True).stdout.strip()


def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone, "sms_verify_ip_window_127.0.0.1"], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    print("login %s http=%s code=%s token=%s" % (phone, st, d.get("code"), "yes" if d.get("token") else "no"))
    return d.get("token")


# ---------- profile: /api/user/sms/send (anonymous alias of /api/sms/send) ----------
subprocess.run(REDIS + ["DEL", "sms_limit_phone_13900000001", "sms_send_phone_day_13900000001"], capture_output=True)
st, d = call("POST", "/api/user/sms/send", form={"phone": "13900000001"})
check("user/sms/send (form, signed out)", st == 200 and d.get("code") == 200, st, d)
st, d = call("POST", "/api/user/sms/send", form={"phone": "123"})
check("user/sms/send bad phone (long-path refusal)", d.get("code") != 200, st, d)

p9, p4 = login("13900000009"), login("13900000004")

# ---------- npc/ai: DELETE /api/npc/chat/delete ----------
st, d = call("DELETE", "/api/npc/chat/delete")
check("npc/chat/delete signed out", st == 401 or d.get("code") == 401, st, d)
st, d = call("DELETE", "/api/npc/chat/delete", p9, js={"requestId": "misc-npc-" + uuid.uuid4().hex[:8]})
check("npc/chat/delete (requestId)", st == 200 and d.get("code") == 200 and "deleted" in (d.get("data") or {}), st, d)

# ---------- community square: /api/ugc/block ----------
st, d = call("POST", "/api/ugc/block", p9, form={"target_member_id": "9004"})
check("ugc/block form target_member_id", st == 200 and d.get("code") == 200, st, d)
st, d = call("POST", "/api/ugc/block", p9, form={"target_member_id": "9009"})
check("ugc/block self (long-path refusal)", d.get("code") != 200, st, d)

# ---------- registration/tickets: /api/registration/original-event ----------
sql("INSERT INTO business_audit_event (domain_type, domain_id, owner_member_id, actor_type, actor_member_id, actor_role_code, action,"
    " target_type, target_id, after_json, request_id, create_time) SELECT 'PLATFORM', 0, 9009, 'MEMBER', 9009, 'player', 'TEST_FIXTURE_ORDER_EVENT',"
    " 'ORDER', 74, JSON_OBJECT('note', 'be-gap-misc TEST fixture'), 'misc-test-order-74', NOW() FROM DUAL WHERE NOT EXISTS"
    " (SELECT 1 FROM business_audit_event WHERE request_id = 'misc-test-order-74')")
st, d = call("POST", "/api/registration/original-event", p9, multipart={"id": "74"})
rows = d.get("data") or []
check("original-event own order 74", st == 200 and d.get("code") == 200 and rows and rows[0].get("targetId") == 74, st, d, "rows=%d" % len(rows))
st, d = call("POST", "/api/registration/original-event", p4, multipart={"id": "74"})
check("original-event someone else's order", d.get("code") == 404, st, d)
st, d = call("POST", "/api/registration/original-event", p4, multipart={"id": "75"})
check("original-event own order without audit rows", d.get("code") == 200 and d.get("data") == [], st, d)

# ---------- orders/payment and wallet/funds (switch ON on this TEST run) ----------
st, d = call("POST", "/api/pay/create", form={"id": "74"})
check("pay/create signed out", st == 401 or d.get("code") == 401, st, d)
st, d = call("POST", "/api/pay/create", p9, form={"id": "74"})
check("pay/create (switch on) -> registration pay/app", d.get("errorCode") != "FEATURE_CLOSED", st, d)
st, d = call("POST", "/api/asset/withdraw", p9, js={})
check("asset/withdraw (switch on) empty body -> 400", d.get("code") == 400, st, d)
st, d = call("POST", "/api/asset/withdraw", p9, js={"realname": "测试", "bankName": "TEST", "bankAccount": "6222000000000000",
                                                   "mobilephone": "13900000009", "withdrawalAmount": 1, "requestId": "misc-wd-1", "challengeId": 1})
check("asset/withdraw (switch on) -> bank withdrawal preflight", d.get("errorCode") != "FEATURE_CLOSED" and d.get("code") != 200, st, d)

# ---------- native-only: /api/native/device/* ----------
key = base64.b64encode(os.urandom(32)).decode()
st, d = call("GET", "/api/native/device/status", p9)
dd = check("device/status (no key)", d.get("code") == 200 and (d.get("data") or {}).get("status") == "UNENROLLED", st, d) or {}
check("device/status flags", dd.get("rewardEnabled") is False and dd.get("hardwareVerified") is False and dd.get("protocolVersion") == 1, st, d)
st, d = call("POST", "/api/native/device/enrollment-challenge", p9, js={"provider": "IOS_CMPEDOMETER_V1", "deviceKeyId": key})
ch = check("device/enrollment-challenge", d.get("code") == 200 and (d.get("data") or {}).get("status") == "CHALLENGE", st, d) or {}
st, d = call("POST", "/api/native/device/enroll", p9, js={"provider": "IOS_CMPEDOMETER_V1", "deviceKeyId": key,
                                                          "challengeId": ch.get("challengeId", "0" * 64), "attestationObject": "AQID"})
check("device/enroll bogus attestation -> 409 APP_ATTEST_*", d.get("code") == 409 and str((d.get("data") or {}).get("reasonCode", "")).startswith("APP_ATTEST_"), st, d)
st, d = call("POST", "/api/native/device/enrollment-challenge", p9, js={"provider": "IOS_CMPEDOMETER_V1", "deviceKeyId": key, "accountId": 1})
check("device/enrollment-challenge extra field", d.get("code") == 409 and (d.get("data") or {}).get("reasonCode") == "APP_ATTEST_REQUEST_INVALID", st, d)
st, d = call("POST", "/api/native/device/revoke", p9, js={"provider": "IOS_CMPEDOMETER_V1", "deviceKeyId": key})
check("device/revoke unknown key -> 409", d.get("code") == 409 and (d.get("data") or {}).get("reasonCode") == "APP_ATTEST_KEY_UNAVAILABLE", st, d)

# ---------- native-only: /api/native/home ----------
st, d = call("GET", "/api/native/home", p9)
home = check("home GET", d.get("code") == 200, st, d) or {}
v0 = home.get("version", 0)
put = {"requestId": "misc-home-" + uuid.uuid4().hex[:8], "expectedVersion": v0, "label": "TEST 家", "latitude": 31.230416, "longitude": 121.473701, "datum": "WGS84"}
st, d = call("PUT", "/api/native/home", p9, js=put)
check("home PUT -> SAVED", (d.get("data") or {}).get("decision") == "SAVED" and (d.get("data") or {}).get("version") == v0 + 1, st, d)
st, d = call("PUT", "/api/native/home", p9, js=put)
check("home PUT same requestId -> replay", (d.get("data") or {}).get("decision") == "SAVED", st, d)
st, d = call("PUT", "/api/native/home", p9, js=dict(put, requestId="misc-home-stale-" + uuid.uuid4().hex[:6]))
check("home PUT stale version -> VERSION_CONFLICT", (d.get("data") or {}).get("decision") == "VERSION_CONFLICT", st, d)
st, d = call("PUT", "/api/native/home", p9, raw='{"requestId":"x1","expectedVersion":0,"ownerId":9004}')
check("home PUT unknown key -> 400", d.get("code") == 400 and d.get("msg") == "HOME_REQUEST_INVALID", st, d)
st, d = call("GET", "/api/native/home", p9)
check("home GET ACTIVE", (d.get("data") or {}).get("status") == "ACTIVE", st, {"code": d.get("code"), "data": {"status": (d.get("data") or {}).get("status")}})
st, d = call("GET", "/api/native/home", p4)
check("home GET other member -> own (none)", (d.get("data") or {}).get("status") == "DELETED", st, d)

# ---------- native-only: /api/native/home/return/* ----------
st, d = call("GET", "/api/native/home/return/policy", p9)
pol = check("return/policy", d.get("code") == 200 and (d.get("data") or {}).get("policyVersion") == "home-return-test-1", st, d) or {}
st, d = call("GET", "/api/native/home/return/runs", p9)
check("return/runs", d.get("code") == 200 and "runs" in (d.get("data") or {}), st, d)
st, d = call("GET", "/api/native/home/return/account", p9, q={"runId": "1"})
check("return/account completed run -> HOME_RUN_UNAVAILABLE", d.get("msg") == "HOME_RUN_UNAVAILABLE", st, d)
import time
hv = v0 + 1
ver = {"requestId": "misc-v-" + uuid.uuid4().hex[:8], "homeVersion": hv, "expectedAccountVersion": 0, "scopeType": "RUN", "scopeId": "1",
       "resourceType": "sys.hp", "policyVersion": pol.get("policyVersion"), "latitude": 31.2305, "longitude": 121.4737, "datum": "WGS84",
       "accuracyMeters": 10, "capturedAtMillis": int(time.time() * 1000)}
st, d = call("POST", "/api/native/home/return/verify", p9, js=ver)
check("return/verify on a completed run -> RUN_UNAVAILABLE", (d.get("data") or {}).get("decision") == "RUN_UNAVAILABLE", st, d)
st, d = call("POST", "/api/native/home/return/restore", p9, js={"requestId": "misc-r-" + uuid.uuid4().hex[:8], "proofId": "nope", "homeVersion": hv,
                                                               "expectedAccountVersion": 0, "scopeType": "RUN", "scopeId": "1", "resourceType": "sys.hp"})
check("return/restore unknown proof -> PROOF_UNAVAILABLE", (d.get("data") or {}).get("decision") == "PROOF_UNAVAILABLE", st, d)
st, d = call("GET", "/api/native/home/return/receipt", p9, q={"requestId": "misc-never"})
check("return/receipt unknown -> success without data", d.get("code") == 200 and "data" not in d, st, d)
st, d = call("GET", "/api/native/home/return/policy")
check("return/policy signed out", st == 401 or d.get("code") == 401, st, d)
st, d = call("DELETE", "/api/native/home", p9, js={"requestId": "misc-del-" + uuid.uuid4().hex[:6], "expectedVersion": hv})
check("home DELETE -> DELETED", (d.get("data") or {}).get("decision") == "DELETED", st, d)

# ---------- US market: /api/us/auth/* ----------
st, d = call("POST", "/api/us/auth/apple/challenges", js={})
c = d.get("data") or {}
check("us/apple/challenges", st == 200 and d.get("code") == 200 and c.get("market") == "US" and c.get("provider") == "apple"
      and c.get("expiresIn") == 300 and "errorCode" not in d, st, d)
st, d = call("POST", "/api/us/auth/apple/challenges", js={"x": 1})
check("us/apple/challenges non-empty body -> 400", st == 400 and d.get("code") == 400 and d.get("errorCode") == "US_APPLE_INVALID_REQUEST", st, d)
st, d = call("POST", "/api/us/auth/apple/exchange", js={"challengeId": c.get("challengeId"), "state": c.get("state"),
                                                       "identityToken": "eyJraWQiOiJub3QtYXBwbGUiLCJhbGciOiJSUzI1NiJ9.e30.c2ln"})
check("us/apple/exchange unknown key -> 401 INVALID_IDENTITY", st == d.get("code") and d.get("errorCode") in ("US_APPLE_INVALID_IDENTITY", "US_APPLE_UNAVAILABLE"), st, d)
st, d = call("POST", "/api/us/auth/apple/exchange", js={"challengeId": c.get("challengeId"), "state": c.get("state"), "identityToken": "a.b.c"})
check("us/apple/exchange reused challenge -> 401 INVALID_CHALLENGE", st == 401 and d.get("errorCode") == "US_APPLE_INVALID_CHALLENGE", st, d)
st, d = call("GET", "/api/us/auth/session", p9, bearer=True)
check("us/auth/session with a CN session -> 401 US_SESSION_INVALID", st == 401 and d.get("errorCode") == "US_SESSION_INVALID", st, d)
st, d = call("GET", "/api/us/auth/session")
check("us/auth/session signed out -> 401", st == 401 or d.get("code") == 401, st, d)

# 8120 is the shared TEST backend: undo the ugc/block check so later IM checks between 9009 and 9004 still work.
st, d = call("POST", "/api/im/unblock", p9, form={"target_member_id": "9004"})
check("cleanup: im/unblock 9009 -> 9004", d.get("code") == 200, st, d)

print("SUMMARY %d/%d OK" % (sum(results), len(results)))

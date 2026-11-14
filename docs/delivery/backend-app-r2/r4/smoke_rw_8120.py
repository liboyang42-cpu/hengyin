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
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    check("login %s" % phone, st == 200 and bool(d.get("token")), st, d, "role=%s" % (d.get("data") or {}).get("role"))
    return d.get("token")


player, reviewer, merchant, owner = login("13900000009"), login("13900000004"), login("13900000002"), login("13900000003")

# ---------------- rewards: signed out, switches ----------------
st, d = call("GET", "/api/rewards/noncash")
check("noncash list signed out -> 401", d.get("code") == 401, st, d)
st, d = call("GET", "/api/reward-pledge/pledges")
check("pledges signed out -> 401", d.get("code") == 401, st, d)
st, d = call("GET", "/api/reward-pledge/pledges", player)
check("pledges as a player (no merchant access) -> 403", d.get("code") == 403, st, d)

# ---------------- merchant draft -> submit -> confirm ----------------
start_ms, end_ms = (int(x) * 1000 for x in sql("SELECT UNIX_TIMESTAMP(start_time), UNIX_TIMESTAMP(end_time) FROM sms_coupon WHERE id=970001").split())
pledge = "rw-" + uuid.uuid4().hex[:20]
draft = {"requestId": pledge, "couponId": 970001, "supplyRef": "RW TEST free drink", "terms": "One drink at the counter",
         "quantity": 2, "serviceStart": start_ms, "serviceEnd": end_ms, "redeemStart": start_ms, "redeemEnd": end_ms, "rewardKind": "NON_CASH"}
st, d = call("POST", "/api/reward-pledge/drafts", merchant, js=draft)
data = check("draft -> receipt APPLIED", (d.get("data") or {}).get("receipt", {}).get("state") == "APPLIED", st, d)
draft_version = data.get("receipt", {}).get("versionId")
digest = data.get("receipt", {}).get("payloadHash")
st, d = call("POST", "/api/reward-pledge/drafts", merchant, js=draft)
check("draft replay -> same receipt", (d.get("data") or {}).get("receipt", {}).get("payloadHash") == digest, st, d)
st, d = call("POST", "/api/reward-pledge/drafts", merchant, js=dict(draft, quantity=3))
check("draft same id, other payload -> 409 CONFLICT", d.get("code") == 409 and d.get("msg") == "CONFLICT", st, d)
st, d = call("POST", "/api/reward-pledge/drafts", merchant, js=dict(draft, requestId=pledge + "c", rewardKind="CASH"))
check("draft CASH (switch ON) -> 409 CASH_REWARD_UNSUPPORTED", d.get("msg") == "CASH_REWARD_UNSUPPORTED", st, d)
st, d = call("GET", "/api/reward-pledge/ops/draft/" + pledge, merchant)
check("ops read draft", (d.get("data") or {}).get("receipt", {}).get("payloadHash") == digest, st, d)
st, d = call("POST", "/api/reward-pledge/%s/submit" % pledge, merchant)
data = check("submit -> receipt key = draft version", (d.get("data") or {}).get("receipt", {}).get("key") == draft_version, st, d)
submitted_version = (data.get("version") or {}).get("versionId")
st, d = call("POST", "/api/reward-pledge/%s/confirm" % pledge, merchant)
check("confirm -> CONFIRMED", ((d.get("data") or {}).get("version") or {}).get("state") == "CONFIRMED", st, d)
st, d = call("GET", "/api/reward-pledge/pledges", merchant)
check("owned pledges", st == 200 and len((d.get("data") or {}).get("pledges") or []) >= 1, st, d, "store=%s" % (d.get("data") or {}).get("store"))

# ---------------- reviewer ----------------
st, d = call("GET", "/api/reward-pledge/review", player)
check("review queue as non-reviewer -> 409 POLICY_CLOSED", d.get("msg") == "POLICY_CLOSED", st, d)
st, d = call("GET", "/api/reward-pledge/review", reviewer)
items = (d.get("data") or {}).get("items") or []
check("review queue (REVIEWER policy actor)", any(i.get("versionId") == submitted_version for i in items), st, d, "items=%d" % len(items))
st, d = call("POST", "/api/reward-pledge/%s/freeze" % pledge, reviewer, js={"ruleVersion": "rw-test-1"})
check("freeze -> FROZEN", ((d.get("data") or {}).get("version") or {}).get("state") == "FROZEN", st, d)
st, d = call("GET", "/api/reward-pledge/review/" + submitted_version, reviewer)
check("review version read-back FROZEN", (d.get("data") or {}).get("state") == "FROZEN", st, d)

# ---------------- player catalog -> offer -> claim ----------------
st, d = call("GET", "/api/rewards/noncash/subjects/992001", player)
data = check("catalog subject 992001", (d.get("data") or {}).get("versionId") == submitted_version and not (d.get("data") or {}).get("errorCode"), st, d)
st, d = call("GET", "/api/rewards/noncash/versions/" + submitted_version, player, q={"branch": "THEME", "subjectId": "992001"})
offer = check("offer (completed THEME fact)", (d.get("data") or {}).get("factRef", "").startswith("THEME:992001:session:") and not (d.get("data") or {}).get("errorCode"), st, d)
st, d = call("GET", "/api/rewards/noncash/versions/" + submitted_version, reviewer, q={"branch": "THEME", "subjectId": "992001"})
check("offer without a completion -> errorCode NO_AUTHORITATIVE_FACT", (d.get("data") or {}).get("errorCode") == "NO_AUTHORITATIVE_FACT", st, d)
request = uuid.uuid4().hex
claim = {"requestId": request, "versionId": submitted_version, "branch": "THEME", "subjectId": "992001"}
st, d = call("POST", "/api/rewards/noncash/claims", player, js=claim)
award = check("claim -> AWARDED", (d.get("data") or {}).get("state") == "AWARDED", st, d)
award_id = award.get("awardId")
st, d = call("POST", "/api/rewards/noncash/claims", player, js=claim)
check("claim replay -> same award", (d.get("data") or {}).get("awardId") == award_id, st, d)
st, d = call("POST", "/api/rewards/noncash/claims/recover", player, js={"requestId": request})
check("recover by original requestId", (d.get("data") or {}).get("awardId") == award_id, st, d)
st, d = call("GET", "/api/rewards/noncash", player)
check("list (RewardPledgeAwardRow array)", isinstance(d.get("data"), list) and any(r.get("awardId") == award_id for r in d["data"]), st, d)
st, d = call("GET", "/api/rewards/noncash", player, q={"limit": "10"})
page = check("page (NonCashRewardService)", "nextCursor" in (d.get("data") or {}) and len((d.get("data") or {}).get("items") or []) >= 1, st, d)
item = next((i for i in page.get("items", []) if i.get("awardId") == award_id), {})
st, d = call("GET", "/api/rewards/noncash/" + award_id, player)
check("detail (award view)", (d.get("data") or {}).get("useStatus") == 0, st, d)
ref = {k: item.get(k) for k in ("contextType", "contextId", "releaseId", "instanceId")}
st, d = call("GET", "/api/rewards/noncash/" + award_id, player, q=ref)
check("detail with frozen reference (NonCash wire)", (d.get("data") or {}).get("rewardKind") == "PHYSICAL", st, d)
st, d = call("GET", "/api/rewards/noncash/" + award_id, player, q=dict(ref, releaseId="other"))
check("detail with a different reference -> 404", d.get("code") == 404, st, d)
st, d = call("GET", "/api/rewards/noncash/" + award_id, reviewer)
check("someone else's award -> 404", d.get("code") == 404, st, d)

# ---------------- store fulfilment ----------------
st, d = call("GET", "/api/reward-pledge/fulfilment/awards", merchant)
check("store awards", isinstance(d.get("data"), list) and any(r.get("awardId") == award_id for r in d["data"]), st, d)
st, d = call("POST", "/api/reward-pledge/fulfilment/preview", merchant, js={"awardId": award_id})
check("preview (state AWARDED, useStatus 0)", (d.get("data") or {}).get("state") == "AWARDED" and (d.get("data") or {}).get("useStatus") == 0, st, d)
history_id = award.get("historyId")
st, d = call("POST", "/api/coupon/qr-token", player, form={"couponHistoryId": history_id})
token = (d.get("data") or {}).get("token") if isinstance(d.get("data"), dict) else d.get("token")
st, d = call("POST", "/api/reward-pledge/fulfilment/resolve", merchant, js={"code": token or "missing"})
check("resolve coupon dynamic code -> award", (d.get("data") or {}).get("awardId") == award_id, st, d, "token=%s" % bool(token))
st, d = call("POST", "/api/reward-pledge/fulfilment/redeem", merchant, js={"awardId": award_id})
check("redeem without confirm -> 400", d.get("code") == 400, st, d)
st, d = call("POST", "/api/reward-pledge/fulfilment/redeem", merchant, js={"awardId": award_id, "confirm": "true"})
check("redeem -> REDEEMED", (d.get("data") or {}).get("redemption") == "REDEEMED", st, d)
st, d = call("POST", "/api/reward-pledge/fulfilment/redeem", merchant, js={"awardId": award_id, "confirm": "true"})
check("redeem again -> ALREADY_USED", (d.get("data") or {}).get("redemption") == "ALREADY_USED", st, d)
st, d = call("POST", "/api/reward-pledge/fulfilment/expire", merchant, js={"awardId": award_id})
check("expire after redeem -> receipt, expiredNow false", (d.get("data") or {}).get("expiredNow") is False, st, d)
st, d = call("GET", "/api/reward-pledge/ops/expire/" + award_id, merchant)
check("ops read expire", (d.get("data") or {}).get("receipt", {}).get("op") == "expire", st, d)
st, d = call("POST", "/api/reward-pledge/awards/%s/return-to-pool" % award_id, merchant)
check("return-to-pool without policy -> POLICY_CLOSED", d.get("msg") == "POLICY_CLOSED", st, d)
st, d = call("GET", "/api/reward-pledge/%s/stats" % pledge, merchant)
check("stats (asOf, awardCount, conserved)", (d.get("data") or {}).get("awardCount") == 1 and (d.get("data") or {}).get("conserved") is True, st, d)
st, d = call("POST", "/api/reward-pledge/versions/%s/hold" % submitted_version, merchant)
check("hold (STORE_HOLD open) -> receipt", (d.get("data") or {}).get("receipt", {}).get("op") == "hold", st, d)
st, d = call("POST", "/api/reward-pledge/versions/%s/withdraw" % submitted_version, merchant)
check("withdraw -> WITHDRAWN", ((d.get("data") or {}).get("version") or {}).get("state") == "WITHDRAWN", st, d)
st, d = call("GET", "/api/reward-pledge/ops/withdraw/" + submitted_version, merchant)
check("ops read withdraw", (d.get("data") or {}).get("receipt", {}).get("state") == "APPLIED", st, d)
st, d = call("GET", "/api/reward-pledge/ops/withdraw/" + submitted_version, reviewer)
check("ops read from another identity -> 403", d.get("code") == 403, st, d)

# ---------------- cases ----------------
st, d = call("POST", "/api/rewards/noncash/cases", player, js={"awardId": award_id, "versionId": submitted_version, "reason": "STORE_REFUSED"})
case = check("open case", bool((d.get("data") or {}).get("caseId")), st, d)
st, d = call("GET", "/api/rewards/noncash/cases/" + case.get("caseId", "x"), player)
check("read case", (d.get("data") or {}).get("reason") == "STORE_REFUSED", st, d)
st, d = call("GET", "/api/rewards/noncash/cases/" + case.get("caseId", "x"), reviewer)
check("someone else's case -> 404", d.get("code") == 404, st, d)
st, d = call("POST", "/api/rewards/noncash/cases", player, js={"awardId": award_id, "versionId": submitted_version, "reason": "CASH_REFUND"})
check("CASH_REFUND case with the TEST switch ON -> OPEN", (d.get("data") or {}).get("state") == "OPEN", st, d)

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

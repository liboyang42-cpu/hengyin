"""offer-withdrawal request protocol, TEST 8120 only: the app's exact verbs, query items and 7-key JSON body."""
import sys, json, uuid, hashlib; sys.path.insert(0, "/data/workspace/be-int/final-gaps")
from probe_lib import *
sha = lambda t: hashlib.sha256(t.encode()).hexdigest()
apple = lambda f: json.dumps(f, sort_keys=True, ensure_ascii=False, separators=(",", ":")).replace("/", "\\/")
def show(n, st, d, extra=""): print("%-52s HTTP %s code=%s %s %s" % (n, st, d.get("code"), d.get("errorCode") or "", extra))
m, c = login("13900000002"), login("13900000003")
B = "/api/merchant/offer-withdrawal/request"
for v in ("GET", "POST", "PUT"):
    st, d = call(v, B, m, body={} if v != "GET" else None); show("%s %s (bare prefix, not an app call)" % (v, B), st, d, d.get("msg"))
q = {"merchantId": 8001, "offerId": 992991}
st, d = call("GET", B + "/current", m, query=q); cur = d.get("data") or {}
show("GET current", st, d, "status=%s canWithdraw=%s isOrdinary=%s keys=%d" % (cur.get("status"), cur.get("canWithdraw"), cur.get("isOrdinary"), len(cur)))
reason = "店面装修/暂停一个月"
intent = {"expectedSHA256": cur.get("snapshotSHA256"), "offerId": 992991, "reason": reason, "reasonSHA256": sha(reason)}
rid = str(uuid.uuid4())
body = dict(intent, merchantId=8001, requestId=rid, intentSHA256=sha(apple(intent)))
st, d = call("POST", B + "/withdraw", m, body=body); r = d.get("data") or {}
show("POST withdraw (7 keys)", st, d, "outcome=%s statusAtWrite=%s completedAt>0=%s" % (r.get("outcome"), r.get("statusAtWrite"), (r.get("completedAt") or 0) > 0))
st, d = call("POST", B + "/withdraw", m, body=body); show("POST withdraw replay (same requestId)", st, d, "outcome=%s" % (d.get("data") or {}).get("outcome"))
st, d = call("GET", B + "/result", m, query=dict(q, requestId=rid)); show("GET result", st, d, "outcome=%s" % (d.get("data") or {}).get("outcome"))
st, d = call("GET", B + "/result", m, query=dict(q, requestId=str(uuid.uuid4()))); show("GET result (unseen requestId)", st, d, "outcome=%s" % (d.get("data") or {}).get("outcome"))
st, d = call("GET", B + "/current", m, query=q); show("GET current (after)", st, d, "status=%s canWithdraw=%s" % ((d.get("data") or {}).get("status"), (d.get("data") or {}).get("canWithdraw")))
st, d = call("GET", B + "/current", c, query=q); show("GET current (club user 9003)", st, d)
# The app's accept() rules for a WITHDRAWN receipt (MerchantOfferWithdrawalOriginalFlow.accept).
st, d = call("GET", B + "/result", m, query=dict(q, requestId=rid)); r = d.get("data") or {}
H = lambda v: isinstance(v, str) and len(v) == 64
checks = {"receiptVersion==1": r.get("receiptVersion") == 1, "actor/merchant/owner/offer": (r.get("actorId"), r.get("merchantId"), r.get("ownerId"), r.get("offerId")) == (9002, 8001, 9002, 992991),
  "requestId": r.get("requestId") == rid, "intent/expected/reason hashes": (r.get("intentSHA256"), r.get("expectedSHA256"), r.get("reasonSHA256")) == (body["intentSHA256"], body["expectedSHA256"], body["reasonSHA256"]),
  "received hashes": H(r.get("receivedSHA256")) and r.get("receivedReasonSHA256") == body["reasonSHA256"], "completedAt>0": (r.get("completedAt") or 0) > 0,
  "topic/chapter>0": (r.get("topicId") or 0) > 0 and (r.get("chapterId") or 0) > 0, "statusAtWrite==0": r.get("statusAtWrite") == 0,
  "requiredAtWrite in 0,1": r.get("requiredAtWrite") in (0, 1), "offerUpdatedAt>0": (r.get("offerUpdatedAt") or 0) > 0}
print("app accept() on WITHDRAWN receipt:", "PASS" if all(checks.values()) else "FAIL", {k: v for k, v in checks.items() if not v})

#!/usr/bin/env python3
"""R3 curl check of the be-gap-im merge on TEST 8120 / cy_e2e_r2 (dispatcher switch on, TEST only).
Secrets are never printed (redacted in the transcript). The outbound target is the TEST app's own public URL, so the
signed request leaves the dispatcher and comes back as an unknown route (a 4xx => DEAD at once); no third party is called."""
import hashlib, hmac, json, os, subprocess, sys, time, urllib.error, urllib.request, uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8120"
PUBLIC = "https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "smoke-im-%s.txt" % ("public" if "instabox" in BASE else "loopback"))
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]
CLUB, CHANNEL = 7001, 5
lines = []


def call(method, path, tok=None, form=None, js=None, q=None, raw=None, headers=None):
    url = BASE + path + ("?" + "&".join("%s=%s" % kv for kv in q.items()) if q else "")
    h = dict(headers or {})
    data = None
    if tok:
        h["Authorization"] = "Bearer " + tok
    if form is not None:
        data = "&".join("%s=%s" % (k, urllib.request.quote(str(v))) for k, v in form.items()).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    if js is not None:
        data = json.dumps(js).encode()
        h["Content-Type"] = "application/json"
    if raw is not None:
        data = raw
        h.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def redact(d):
    if isinstance(d, dict):
        return {k: ("<redacted>" if k in ("secret", "signing_secret", "token") else redact(v)) for k, v in d.items()}
    if isinstance(d, list):
        return [redact(x) for x in d]
    return d


def show(label, method, path, st, d, note=""):
    body = json.dumps(redact(d), ensure_ascii=False)
    line = "%-40s %-6s %-52s HTTP %s code=%s %s %s" % (label, method, path, st, d.get("code"), note, body[:300])
    lines.append(line)
    print(line[:260])


def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    return d["token"]


def rid():
    return "r3i-" + uuid.uuid4().hex[:20]


def sign(secret, ts, nonce, body):
    return "v1=" + hmac.new(secret.encode(), ("%s.%s." % (ts, nonce)).encode() + body, hashlib.sha256).hexdigest()


owner, member = login("13900000003"), login("13900000004")

st, d = call("GET", "/api/channels", owner, q={"guild_kind": 1, "guild_id": CLUB})
show("channels (owner)", "GET", "/api/channels", st, d)
st, d = call("GET", "/api/im/audit-log", owner, q={"guild_kind": 1, "guild_id": CLUB, "limit": 5, "verify": 1})
show("audit-log verify=1 (owner)", "GET", "/api/im/audit-log", st, d, "verify=%s" % json.dumps((d.get("data") or {}).get("verify")))
st, d = call("GET", "/api/im/audit-log", member, q={"guild_kind": 1, "guild_id": CLUB, "limit": 5})
show("audit-log (member)", "GET", "/api/im/audit-log", st, d)
st, d = call("GET", "/api/im/webhook/context", owner, q={"owner_id": CLUB, "channel_id": CHANNEL})
show("webhook context (owner)", "GET", "/api/im/webhook/context", st, d)
st, d = call("GET", "/api/im/chain/info", member, q={"conversation_id": CHANNEL, "limit": 50})
show("chain info by conversation", "GET", "/api/im/chain/info", st, d)

# outbound: dispatcher on (TEST); target = this app's public URL, unknown route => 4xx => DEAD at once
base = {"owner_type": "CLUB", "owner_id": CLUB, "channel_id": CHANNEL, "events": ["message.created"]}
st, d = call("POST", "/api/im/webhook/subscriptions", owner, js=dict(base, request_id=rid(), name="R3 8120 probe", url=PUBLIC + "/api/r3-webhook-probe"))
show("subscription create", "POST", "/api/im/webhook/subscriptions", st, d)
sub = d["data"]["id"]
st, d = call("POST", "/api/im/webhook/subscriptions/%s/test" % sub, owner, js={"request_id": rid(), "revision": 1, "event_type": "message.created"})
show("subscription test", "POST", "/api/im/webhook/subscriptions/%s/test" % sub, st, d)
st, d = call("POST", "/api/im/send", member, form={"conversation_id": CHANNEL, "msg_type": 1, "content": "R3 8120 webhook ping", "client_message_id": rid()})
show("im/send in channel 5", "POST", "/api/im/send", st, d)
rows = []
for _ in range(20):
    time.sleep(2)
    st, d = call("GET", "/api/im/webhook/subscriptions/%s/deliveries" % sub, owner, q={"limit": 20, "include_payload": 0})
    rows = (d.get("data") or {}).get("list") or []
    if len(rows) >= 2 and all(r["state"] not in ("PENDING", "INFLIGHT") for r in rows):
        break
show("deliveries (dispatched)", "GET", "/api/im/webhook/subscriptions/%s/deliveries" % sub, st, d,
     " | ".join("#%s %s a%s %s http=%s" % (r["delivery_id"], r["state"], r["attempt"], r["reason"], r["http_status"]) for r in rows))
st, d = call("DELETE", "/api/im/webhook/subscriptions/%s" % sub, owner, js={"request_id": rid(), "revision": 1})
show("subscription delete", "DELETE", "/api/im/webhook/subscriptions/%s" % sub, st, d)

# inbound
ep_body = {"request_id": rid(), "owner_type": "CLUB", "owner_id": CLUB, "channel_id": CHANNEL, "name": "R3 8120 CI", "allowed_ips": [], "rate_limit_per_min": 30}
st, d = call("POST", "/api/im/webhook/endpoints", owner, js=ep_body)
show("endpoint create", "POST", "/api/im/webhook/endpoints", st, d, "url=%s" % (d.get("data") or {}).get("url"))
ep, secret = d["data"]["id"], d["data"]["signing_secret"]
st, d = call("POST", "/api/im/webhook/endpoints", member, js=dict(ep_body, request_id=rid()))
show("endpoint create (member)", "POST", "/api/im/webhook/endpoints", st, d)
path = "/api/im/webhook/inbound/%s" % ep
body = json.dumps({"content": "R3 8120 构建通过"}, ensure_ascii=False).encode()
ts, nonce = int(time.time()), uuid.uuid4().hex
hdr = {"X-Chengyin-Timestamp": str(ts), "X-Chengyin-Nonce": nonce, "X-Chengyin-Signature": sign(secret, ts, nonce, body)}
st, d = call("POST", path, raw=body, headers=hdr)
show("inbound signed", "POST", path, st, d)
st, d = call("POST", path, raw=body, headers=hdr)
show("inbound replay", "POST", path, st, d)
hdr2 = dict(hdr, **{"X-Chengyin-Nonce": uuid.uuid4().hex, "X-Chengyin-Signature": "v1=" + "0" * 64})
st, d = call("POST", path, raw=body, headers=hdr2)
show("inbound bad signature", "POST", path, st, d)
st, d = call("DELETE", "/api/im/webhook/endpoints/%s" % ep, owner, js={"request_id": rid(), "revision": 1})
show("endpoint delete", "DELETE", "/api/im/webhook/endpoints/%s" % ep, st, d)

open(OUT, "w").write("\n".join(lines) + "\n")

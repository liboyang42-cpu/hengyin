#!/usr/bin/env python3
"""TEST-only r2 smoke for the be-int backend (8120, cy_e2e_r2). Loopback or instabox TEST URL only."""
import hashlib, json, os, struct, subprocess, sys, urllib.error, urllib.parse, urllib.request, uuid, zlib

B = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8120"
if not (B.startswith("http://127.0.0.1:8120") or ".instabox.tech" in B):
    sys.exit("refusing non-TEST base url")
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]


def call(method, path, tok=None, form=None, js=None, q=None, headers=None, raw=None, ctype=None):
    url = B + path + ("?" + urllib.parse.urlencode(q) if q else "")
    h = {"Accept": "application/json"}
    data = None
    if tok:
        h["Authorization"] = "Bearer " + tok
    if form is not None:
        data = urllib.parse.urlencode(form).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    if js is not None:
        data = json.dumps(js, sort_keys=True).encode(); h["Content-Type"] = "application/json"
    if raw is not None:
        data = raw; h["Content-Type"] = ctype
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            st, body = r.status, r.read()
    except urllib.error.HTTPError as e:
        st, body = e.code, e.read()
    try:
        return st, json.loads(body)
    except Exception:
        return st, {"raw": body[:200].decode("utf-8", "replace")}


def show(label, st, d, extra=""):
    good = st == 200 and d.get("code") in (200, None)
    print("%-34s %s http=%s code=%s %s %s" % (label, "OK " if good else "ERR", st, d.get("code"), extra,
                                           "" if good else json.dumps(d, ensure_ascii=False)[:220]))
    return good


def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    show("login %s" % phone, st, d, "role=%s" % (d.get("data") or {}).get("role"))
    return d["token"]


def png_bytes():
    raw = b"\x00\xff\x80\x00"
    def chunk(t, b): return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def multipart(fields, fname, content, mime):
    bnd = uuid.uuid4().hex
    out = b""
    for k, v in fields.items():
        out += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (bnd, k, v)).encode()
    out += ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (bnd, fname, mime)).encode()
    out += content + ("\r\n--%s--\r\n" % bnd).encode()
    return out, "multipart/form-data; boundary=" + bnd


t9, t4, t3 = login("13900000009"), login("13900000004"), login("13900000003")

st, d = call("POST", "/api/userInfo", t9)
a = d.get("appUser") or {}
show("userInfo 9009", st, d, "role=%s nickName=%s" % (a.get("role"), a.get("nickName")))
st, d = call("POST", "/api/topic/info-to-user", t4, form={"id": 992001})
show("topic/info-to-user 992001", st, d, "name=%s" % (d.get("data") or {}).get("name"))
st, d = call("GET", "/api/play/nodes", t9, q={"activityId": 992031, "topicId": 992001})
x = d.get("data") or {}
show("play/nodes 9009", st, d, "registered=%s total=%s" % (x.get("registered"), x.get("total")))

# registration/create (free): quote then create (idempotent replay for 9009)
body = {"ownerType": 2, "ownerId": 992031, "ticketId": 992041, "tickets": [{"ticketId": 992041, "count": 1}],
        "realName": "E2E 玩家二号", "phone": "13900000009", "count": 1}
st, q = call("POST", "/api/registration/quote", t9, js=body)
show("registration/quote 9009", st, q, "pay=%s" % (q.get("data") or {}).get("payAmount"))
body["quoteSign"] = (q.get("data") or {}).get("quoteSign")
body["requestId"] = "e2e-seed-reg-9009-992031"
st, d = call("POST", "/api/registration/create", t9, js=body)
show("registration/create (free) 9009", st, d, "regId=%s pay=%s" % ((d.get("data") or {}).get("registrationId"), (d.get("data") or {}).get("payableAmount")))

# IM text
cid = "r2smoke-text-" + uuid.uuid4().hex[:12]
st, d = call("POST", "/api/im/send", t4, form={"target_member_id": 9009, "msg_type": 1, "content": "r2 smoke ping", "client_message_id": cid})
conv = (d.get("data") or {}).get("conversationId")
show("im/send text 9004->9009", st, d, "conv=%s checkStatus=%s" % (conv, (d.get("data") or {}).get("checkStatus")))

# IM image. Upload needs WeChat imgSecCheck + OSS (absent on TEST): record the refusal, then send a pre-hosted https image
# (msg_type 2) and read it back through the new attachment / message endpoints.
png = png_bytes()
mp, ct = multipart({}, "r2.png", png, "image/png")
st, up = call("POST", "/api/common/upload", t4, raw=mp, ctype=ct)
print("%-34s %s http=%s code=%s %s" % ("common/upload image", "PROVIDER-BLOCKED" if st == 503 else ("OK " if up.get("code") == 200 else "ERR"), st, up.get("code"), up.get("msg")))
url = "https://www.chengyinhub.com/static/e2e/r2-smoke.png"
cid = "r2smoke-image-" + uuid.uuid4().hex[:12]
st, d = call("POST", "/api/im/send", t4, form={"conversation_id": conv, "msg_type": 2, "content": url, "client_message_id": cid})
mid = (d.get("data") or {}).get("id")
show("im/send image (msg_type 2)", st, d, "msgId=%s checkStatus=%s" % (mid, (d.get("data") or {}).get("checkStatus")))
if mid:
    st, d = call("GET", "/api/im/message/%s/attachment" % mid, t4)
    show("im/message/{id}/attachment (sender)", st, d, "kind=%s state=%s source=%s" % ((d.get("data") or {}).get("kind"), (d.get("data") or {}).get("state"), (d.get("data") or {}).get("source")))
    st, d = call("GET", "/api/im/message", t4, q={"message_id": mid})
    show("im/message (sender)", st, d, "source=%s" % (d.get("data") or {}).get("source"))
    st, d = call("GET", "/api/im/message", t9, q={"message_id": mid})
    print("%-34s http=%s code=%s errorCode=%s (another member's not-yet-approved image is 404 by contract)" % ("im/message (recipient)", st, d.get("code"), d.get("errorCode")))
# IM file through the new media pipeline (upload -> receipt)
txt = "r2 smoke attachment\n".encode()
rid = str(uuid.uuid4())
mp, ct = multipart({"conversation_id": conv, "upload_request_id": rid, "source_sha256": hashlib.sha256(txt).hexdigest()}, "r2.txt", txt, "text/plain")
st, d = call("POST", "/api/im/file/upload", t4, raw=mp, ctype=ct)
print("%-34s %s" % ("", "(OSS absent on TEST: CHENGYIN_OSS_ENDPOINT)")) if d.get("errorCode") == "OUTCOME_UNKNOWN" else None
show("im/file/upload (media pipeline)", st, d, "state=%s review=%s" % ((d.get("data") or {}).get("state"), (d.get("data") or {}).get("review_state")))
st, d = call("GET", "/api/im/file/receipt", t4, q={"conversation_id": conv, "upload_request_id": rid, "source_sha256": hashlib.sha256(txt).hexdigest()})
show("im/file/receipt", st, d, "state=%s" % (d.get("data") or {}).get("state"))

# Channels: directory + create in club 7001 (leader 9003)
st, d = call("GET", "/api/guilds/1/7001/channels", t3, q={"with_unread": 1})
dd = d.get("data") or {}
n = "groups=%s ungrouped=%s" % (len(dd.get("groups") or []), len(dd.get("ungrouped") or []))
show("channels list club 7001", st, d, "channels=%s" % n)
reason = "r2 smoke"
st, d = call("POST", "/api/guilds/1/7001/channels", t3, headers={"X-Audit-Log-Reason": reason},
             js={"name": "r2-smoke-%s" % uuid.uuid4().hex[:6], "topic": None, "kind": 1, "parent_id": "0", "visibility": 1,
                 "slowmode_sec": 0, "request_id": str(uuid.uuid4()), "reason": reason})
show("channels create club 7001", st, d, "result=%s" % json.dumps(d.get("data"), ensure_ascii=False)[:120])

# Club shared notification default (/api/club/settings/notify-level, CLUB_DEFAULT receipts)
st, d = call("GET", "/api/club/settings/notify-level", t9, q={"clubId": "7001"})
cur = d.get("data") or {}
show("notify-level GET 9009 (member)", st, d, "level=%s version=%s configured=%s" % (cur.get("level"), cur.get("version"), cur.get("configured")))
rid = "r2smoke-clubdef-" + uuid.uuid4().hex[:16]
st, d = call("POST", "/api/club/settings/notify-level", t3, js={"request_id": rid, "clubId": "7001", "level": 1 - int(cur.get("level", 1)), "version": cur.get("version", 0)})
show("notify-level POST 9003 (owner)", st, d, "status=%s result=%s" % ((d.get("data") or {}).get("status"), (d.get("data") or {}).get("result")))
rid2 = "r2smoke-clubdef-" + uuid.uuid4().hex[:16]
st, d = call("POST", "/api/club/settings/notify-level", t3, js={"request_id": rid2, "clubId": "7001", "level": 0, "version": cur.get("version", 0)})
x = d.get("data") or {}
print("%-34s %s http=%s status=%s code=%s" % ("notify-level stale version", "OK " if x.get("status") == "REJECTED" else "ERR", st, x.get("status"), (x.get("result") or {}).get("rejection_code")))
st, d = call("GET", "/api/im/notification-requests/" + rid2, t3)
show("notification-requests/{id} (REJECTED)", st, d, "status=%s op=%s" % ((d.get("data") or {}).get("status"), (d.get("data") or {}).get("operation")))
st, d = call("POST", "/api/club/settings/notify-level", t9, js={"request_id": "r2smoke-clubdef-" + uuid.uuid4().hex[:16], "clubId": "7001", "level": 0, "version": 0})
print("%-34s %s http=%s errorCode=%s" % ("notify-level POST 9009 (member)", "OK " if st == 403 else "ERR", st, d.get("errorCode")))

# R2 follow-up: closed egg route, mod-commerce OFF stub, U08-03/U08-04 creator reads, U06 turn block
st, d = call("POST", "/api/play/egg/collect", t9, form={"eggId": 1, "topicId": 992001})
print("%-34s %s http=%s code=%s errorCode=%s" % ("egg/collect (closed)", "OK " if d.get("code") == 410 and d.get("errorCode") == "ROUTE_EGG_OFFLINE" else "ERR", st, d.get("code"), d.get("errorCode")))
st, d = call("POST", "/api/mod-commerce/orders", None, js={})
print("%-34s %s http=%s code=%s" % ("mod-commerce signed out", "OK " if d.get("code") == 401 else "ERR", st, d.get("code")))
st, d = call("POST", "/api/mod-commerce/orders", t9, js={})
print("%-34s %s http=%s code=%s errorCode=%s" % ("mod-commerce signed in (OFF)", "OK " if d.get("code") == 503 and d.get("errorCode") == "WORKSHOP_COMMERCE_DISABLED" else "ERR", st, d.get("code"), d.get("errorCode")))
fb_rid = "r2smoke-fb-" + uuid.uuid4().hex[:12]
runs = subprocess.run(["docker", "exec", "be-int-mysql", "mysql", "-uroot", "-N", "cy_e2e_r2", "-e",
                       "SELECT id FROM topic_route_session WHERE member_id=9009 AND topic_id=992001 ORDER BY id DESC LIMIT 1"],
                      capture_output=True, text=True).stdout.strip()
if runs:
    st, d = call("POST", "/api/play/feedback/submit", t9, js={"runId": int(runs), "category": "PLACE", "content": "r2 smoke：入口标识不清楚", "requestId": fb_rid})
    show("play/feedback/submit (9009)", st, d, "feedbackId=%s" % (d.get("data") or {}).get("feedbackId"))
st, d = call("GET", "/api/creator/feedback", t3, q={"topicId": 992001})
items = (d.get("data") or {}).get("items") or []
show("U08-03 creator/feedback (9003)", st, d, "items=%s reporterHidden=%s" % (len(items), all("memberId" not in i for i in items)))
st, d = call("GET", "/api/creator/feedback", t9, q={"topicId": 992001})
print("%-34s %s http=%s errorCode=%s" % ("U08-03 creator/feedback (9009)", "OK " if st == 404 else "ERR", st, d.get("errorCode")))
open_ids = [i["feedbackId"] for i in items if i.get("ticketId") is None][:1]
if open_ids:
    st, d = call("POST", "/api/creator/maintenance/tickets", t3, js={"requestId": "r2smoke-tk-" + uuid.uuid4().hex[:12], "topicId": 992001,
                                                                       "title": "入口标识", "feedbackIds": open_ids})
    tk = d.get("data") or {}
    show("U08-03 open ticket", st, d, "ticketId=%s stage=%s baseline=%s" % (tk.get("ticketId"), tk.get("stage"), (tk.get("baseline") or {}).get("configVersion")))
    if tk.get("ticketId"):
        st, d = call("POST", "/api/creator/maintenance/tickets/%s/close" % tk["ticketId"], t3,
                     js={"requestId": "r2smoke-cl-" + uuid.uuid4().hex[:12], "expectedVersion": tk.get("version"), "resolution": "FIXED"})
        print("%-34s %s http=%s errorCode=%s" % ("U08-03 close FIXED w/o new version", "OK " if d.get("errorCode") == "FIX_NOT_PUBLISHED" else "ERR", st, d.get("errorCode")))
        st, d = call("POST", "/api/creator/maintenance/tickets/%s/close" % tk["ticketId"], t3,
                     js={"requestId": "r2smoke-cl-" + uuid.uuid4().hex[:12], "expectedVersion": tk.get("version"), "resolution": "NO_CHANGE", "reason": "r2 冒烟：已人工核实"})
        show("U08-03 close NO_CHANGE", st, d, "status=%s" % (d.get("data") or {}).get("status"))
st, d = call("GET", "/api/creator/analytics/post-game-outcome", t3, q={"cohortDate": "2026-11-13", "windowDays": 30})
x = d.get("data") or {}
rep = x.get("report") or {}
show("U08-04 creator post-game-outcome", st, d, "topics=%s mature=%s eligible=%s metrics=%s" % (
    (x.get("scope") or {}).get("topicIds"), rep.get("mature"), (rep.get("cohort") or {}).get("eligibleMembers"), sorted((rep.get("metrics") or {}).keys())))
st, d = call("GET", "/api/creator/analytics/post-game-outcome", t9, q={"cohortDate": "2026-11-13"})
print("%-34s %s http=%s errorCode=%s" % ("U08-04 non-creator", "OK " if st == 404 else "ERR", st, d.get("errorCode")))

# Friends
st, d = call("POST", "/api/friend/request", t4, js={"peer_id": 9003, "request_id": str(uuid.uuid4()), "msg": "r2 smoke"})
show("friend/request 9004->9003", st, d, "outcome=%s" % (d.get("data") or {}).get("outcome"))
st, d = call("GET", "/api/friend/requests", t3, q={"cursor": 0, "limit": 20})
show("friend/requests 9003", st, d, "incoming=%s" % len((d.get("data") or {}).get("incoming") or []))

# Club home
st, d = call("POST", "/api/club/home", t9, js={})
show("club/home 9009", st, d, "joined=%s" % [c.get("id") for c in ((d.get("data") or {}).get("joined") or [])])

# City game (gaps A, region gaps-a-test)
st, d = call("GET", "/api/city-game/current", t9, q={"regionId": "gaps-a-test"})
show("city-game/current gaps-a-test", st, d, json.dumps(d.get("data"), ensure_ascii=False)[:120])
st, d = call("GET", "/api/city-game/game/snapshot", t9, q={"boardId": "gapsa-board-1"})
x = d.get("data") or {}
show("city-game/game/snapshot", st, d, "keys=%s" % sorted(x.keys())[:8])

# Team: create (activity 992031, 9009 signed up), pool, rank scope=team. TEST-only: open team_mode=2 on topic 992001.
subprocess.run(["docker", "exec", "be-int-mysql", "mysql", "-uroot", "cy_e2e_r2", "-e", "UPDATE cms_topic SET team_mode=2 WHERE id=992001 AND team_mode=0"], check=True)
st, d = call("POST", "/api/team/create", t9, js={"ownerType": 2, "ownerId": 992031, "title": "r2 smoke team", "maxMembers": 4})
team = (d.get("data") or {}).get("teamId")
show("team/create 9009", st, d, "teamId=%s" % team)
if team:
    st, d = call("GET", "/api/team/pool", t9, q={"teamId": team})
    x = d.get("data") or {}
    show("team/pool", st, d, "enabled=%s reason=%s" % (x.get("enabled"), x.get("unavailableReason")))
    st, d = call("GET", "/api/rank/list", t9, q={"scope": "team", "scopeId": team, "period": "all", "limit": 10})
    x = d.get("data") or {}
    show("rank/list scope=team", st, d, "scope=%s entries=%s" % (x.get("scope"), len(x.get("entries") or [])))

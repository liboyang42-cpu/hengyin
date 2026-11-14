#!/usr/bin/env python3
"""TEST-only curl checks for feature/game-import-review on 8121 (DB cy_import_test, Redis db 10). Loopback only.

Admin: the TEST-only sys_user named by IMPORT_ADMIN_USER / IMPORT_ADMIN_PASSWORD (env, never printed)."""
import base64, hashlib, json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid

B = "http://127.0.0.1:8121"
REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "10"]
results = []


def call(method, path, tok=None, form=None, js=None, raw=None, ctype=None, q=None):
    url = B + path + ("?" + urllib.parse.urlencode(q) if q else "")
    h = {"Accept": "application/json"}
    data = None
    if tok:
        h["Authorization"] = "Bearer " + tok
    if form is not None:
        data = urllib.parse.urlencode(form).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    if js is not None:
        data = json.dumps(js).encode(); h["Content-Type"] = "application/json"
    if raw is not None:
        data = raw; h["Content-Type"] = ctype
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            st, body = r.status, r.read()
    except urllib.error.HTTPError as e:
        st, body = e.code, e.read()
    try:
        return st, json.loads(body)
    except Exception:
        return st, {"raw": body[:120].decode("utf-8", "replace"), "bytes": len(body)}


def check(label, ok, st, d, note=""):
    results.append(ok)
    print("%-46s %s http=%s code=%s errorCode=%s %s" % (label, "OK " if ok else "ERR", st, d.get("code"), d.get("errorCode"), note))


def login(phone):
    subprocess.run(REDIS + ["DEL", "sms_limit_phone_" + phone, "sms_send_phone_day_" + phone], capture_output=True)
    call("POST", "/api/sms/send", form={"phone": phone})
    code = subprocess.run(REDIS + ["GET", "sms_%s_code" % phone], capture_output=True, text=True).stdout.strip().strip('"')
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    check("player login %s" % phone, st == 200 and bool(d.get("token")), st, d)
    return d.get("token")


def multipart(parts):
    bnd = uuid.uuid4().hex
    out = b""
    for name, filename, content, mime in parts:
        disp = 'form-data; name="%s"' % name + ('; filename="%s"' % filename if filename else "")
        out += ("--%s\r\nContent-Disposition: %s\r\n" % (bnd, disp)).encode()
        if mime:
            out += ("Content-Type: %s\r\n" % mime).encode()
        out += b"\r\n" + content + b"\r\n"
    return out + ("--%s--\r\n" % bnd).encode(), "multipart/form-data; boundary=" + bnd


def package_sha(files):
    h = hashlib.sha256()
    for path in sorted(files):
        h.update(path.encode() + b"\0" + str(len(files[path])).encode() + b"\0" + files[path] + b"\0")
    return h.hexdigest()


def archive(game, version, html):
    files = {"index.html": ("<!doctype html>" + html).encode(), "app.js": b"console.log('ok')"}
    manifest = {"id": game, "name": "Game " + game, "version": version, "entry": "index.html", "permissions": ["storage"],
                "contentRating": "4+", "allowedDomains": [], "sha256": package_sha(files), "signature": "client", "author": "E2E"}
    doc = {"manifest": manifest, "files": {k: base64.b64encode(v).decode() for k, v in files.items()}}
    return multipart([("archive", "game.json", json.dumps(doc).encode(), "application/json")])


def submit(tok, game, version, html, request_id=None):
    body, ctype = archive(game, version, html)
    path = "/api/external-game/submissions" + ("?requestId=" + request_id if request_id else "")
    return call("POST", path, tok, raw=body, ctype=ctype)


st, d = call("GET", "/api/external-game/signing-key")
check("signing-key (anonymous)", st == 200 and (d.get("data") or {}).get("configured") is True, st, d)
st, d = submit(None, "rw.signed.out", "1.0.0", "<p>x</p>")
check("submit signed out -> 401", st == 401, st, d)

player = login("13900000004")
other = login("13900000005")
stamp = str(int(time.time()))
game = "rw.quiz" + stamp
st, d = submit(player, game, "1.0.0", "<p>v1</p>", "rw-" + stamp)
sub = (d.get("data") or {})
check("submit -> PENDING (server signature)", st == 200 and sub.get("status") == "PENDING" and sub.get("signature") not in (None, "client"), st, d)
st, d2 = submit(player, game, "1.0.0", "<p>v1</p>", "rw-" + stamp)
check("same requestId replays", st == 200 and (d2.get("data") or {}).get("submissionId") == sub.get("submissionId"), st, d2)
st, d = submit(other, game, "9.0.0", "<p>x</p>")
check("other author same game -> GAME_ID_TAKEN", d.get("errorCode") == "GAME_ID_TAKEN", st, d)
st, d = call("GET", "/api/external-game/submissions/mine", player)
check("mine lists the submission", st == 200, st, d, "count=%s" % len((d.get("data") or {}).get("items") or []))

admin_user, admin_password = os.environ.get("IMPORT_ADMIN_USER"), os.environ.get("IMPORT_ADMIN_PASSWORD")
st, d = call("POST", "/login", js={"username": admin_user, "password": admin_password})
admin = d.get("token")
check("admin login (TEST-only sys_user)", st == 200 and bool(admin), st, d)
st, d = call("GET", "/business/external-game/review/list", admin)
check("admin queue", st == 200 and d.get("code") == 200, st, d, "counts=%s" % (d.get("data") or {}).get("counts"))
sid = str(sub.get("submissionId"))
st, d = call("GET", "/business/external-game/review/" + sid, admin)
check("admin detail", st == 200 and d.get("code") == 200, st, d)
st, d = call("POST", "/business/external-game/review/%s/approve" % sid, admin, js={"note": "TEST ok"})
check("admin approve -> APPROVED", st == 200 and (d.get("data") or {}).get("status") == "APPROVED", st, d)

st, d = call("GET", "/api/external-game/catalog")
items = (d.get("data") or {}).get("items") or []
check("catalog (the Druid WHERE 1 = 1 fix)", st == 200 and any(i.get("gameId") == game for i in items), st, d, "items=%d" % len(items))
st, d = call("GET", "/api/external-game/catalog/" + game)
download = (d.get("data") or {}).get("downloadPath")
check("catalog detail has a signed downloadPath", st == 200 and bool(download), st, d)
st, d = call("GET", download)
check("signed download", st == 200, st, d, "bytes=%s" % d.get("bytes", "json"))
st, d = call("GET", download.replace("sig=", "sig=00"))
check("tampered signature -> 403", st == 403, st, d)

st, d = submit(player, game, "1.1.0", "<p>v2</p>")
sid2 = str((d.get("data") or {}).get("submissionId"))
st, d = call("POST", "/business/external-game/review/%s/reject" % sid2, admin, js={})
check("reject without reason -> 400", st == 400, st, d)
st, d = call("POST", "/business/external-game/review/%s/reject" % sid2, admin, js={"reason": "TEST blank page"})
check("reject with reason -> REJECTED", (d.get("data") or {}).get("status") == "REJECTED", st, d)
st, d = call("GET", "/api/external-game/submissions/" + sid2, player)
check("author sees rejectReason", (d.get("data") or {}).get("rejectReason") == "TEST blank page", st, d)
st, d = submit(player, game, "1.2.0", "<p>v3</p>")
sid3 = str((d.get("data") or {}).get("submissionId"))
st, d = call("POST", "/business/external-game/review/%s/escalate" % sid3, admin, js={"reason": "TEST second look"})
check("escalate -> ESCALATED", (d.get("data") or {}).get("status") == "ESCALATED", st, d)
st, d = call("POST", "/api/external-game/submissions/%s/cancel" % sid3, player, js={})
check("author cancels escalated -> CANCELLED", (d.get("data") or {}).get("status") == "CANCELLED", st, d)

rep = {"requestId": "rep-" + stamp, "gameId": game, "reasonCode": "BROKEN", "detail": "TEST"}
st, d = call("POST", "/api/external-game/reports", other, js=rep)
check("report", st == 200 and d.get("code") == 200, st, d)
st, d = call("POST", "/api/external-game/reports", other, js=dict(rep, requestId="rep2-" + stamp))
check("second open report -> REPORT_ALREADY_OPEN", d.get("errorCode") == "REPORT_ALREADY_OPEN", st, d)
st, d = call("GET", "/business/external-game/reports", admin)
check("admin reports (the Druid WHERE id > 0 fix)", st == 200 and d.get("code") == 200, st, d)

print("SUMMARY %d/%d OK" % (sum(results), len(results)))

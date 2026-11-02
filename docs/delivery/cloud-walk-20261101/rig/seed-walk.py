#!/usr/bin/env python3
"""Dummy-data helpers for the walkers (run with $RIG_HOME/venv/bin/python).

  seed-walk.py list                         identities in the rig DB (fixture + walk-*), with role / merchant / club
  seed-walk.py add-players [N]              create N extra players through the REAL /api/login/code (rig fake
                                            jscode2session), openid walk-player-<k>; prints member ids
  seed-walk.py token <openid>               real app login (POST /api/login/code), prints {token, member}
  seed-walk.py phone-token <phone>          real /api/login/phone with the fixture SMS code seeded in Redis (as chains do)
  seed-walk.py admin-token                  RuoYi admin token (/login, fixture password from chain_lib)
  seed-walk.py shift-session <activityId> [--start-in-min M]
                                            FIXTURE TIME SHIFT: move cms_activity.start_date to now+M minutes (default -1,
                                            i.e. started a minute ago), keeping its duration. Use after sign-up + payment
                                            so play can begin without waiting. Direct test-DB write; report it as a fixture.

Login for the browser runtime: pass identity=<openid> to walk.mjs (e.g. e2e-openid-9004 or walk-player-1).
Fixture identities (seed_fixtures.py): 9001 平台运营, 9002/9005/9006/9007 merchants (mms_merchant 8001-8004),
9003 俱乐部主理人 (club 7001 + merchant 8005), 9008 执行俱乐部主理人, 9004/9009 players. openid = e2e-openid-<id>.
"""
import json, os, sys, urllib.request, urllib.parse, datetime, subprocess
from pathlib import Path
RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
os.environ["PATH"] = "%s:%s" % (RIG_HOME / "work" / "bin", os.environ.get("PATH", ""))  # redis-cli shim
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
import chain_lib as cl  # noqa: E402

BASE = "http://127.0.0.1:8099"


def login_code(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request(BASE + "/api/login/code", data=data, method="POST",
                                 headers={"content-type": "application/x-www-form-urlencoded"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return json.loads(opener.open(req, timeout=20).read().decode())


def cmd_list():
    rows = cl.q("SELECT m.id,m.nickname,m.role,m.user_type,m.wx_open_id,m.phone,"
                "(SELECT GROUP_CONCAT(id) FROM mms_merchant x WHERE x.member_id=m.id),"
                "(SELECT GROUP_CONCAT(id) FROM club c WHERE c.member_id=m.id) "
                "FROM ums_member m WHERE m.wx_open_id LIKE 'e2e-openid-%%' OR m.wx_open_id LIKE 'walk-%%' ORDER BY m.id")
    print("%-6s %-14s %-9s %-4s %-22s %-12s %-8s %s" % ("id", "nickname", "role", "type", "openid(identity)", "phone", "merchant", "club"))
    for r in rows:
        print("%-6s %-14s %-9s %-4s %-22s %-12s %-8s %s" % tuple("" if x is None else x for x in r))


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__); return 0
    c = argv[0]
    if c == "list":
        cmd_list()
    elif c == "add-players":
        n = int(argv[1]) if len(argv) > 1 else 3
        have = cl.q("SELECT COUNT(*) FROM ums_member WHERE wx_open_id LIKE 'walk-player-%%'")[0][0]
        for k in range(have + 1, have + n + 1):
            r = login_code("walk-player-%d" % k)
            print("walk-player-%d -> member %s role %s (%s)" % (k, (r.get("data") or {}).get("id"), (r.get("data") or {}).get("role"), r.get("msg")))
    elif c == "token":
        r = login_code(argv[1]); print(json.dumps({"token": r.get("token"), "member": r.get("data"), "msg": r.get("msg")}, ensure_ascii=False))
    elif c == "phone-token":
        tok, r = cl.app_token(argv[1]); print(json.dumps({"token": tok, "member": r.get("data")}, ensure_ascii=False))
    elif c == "admin-token":
        print(cl.admin_token())
    elif c == "shift-session":
        act = int(argv[1]); mins = -1
        if "--start-in-min" in argv:
            mins = int(argv[argv.index("--start-in-min") + 1])
        row = cl.q("SELECT start_date,end_date FROM cms_activity WHERE id=%s", (act,))
        if not row:
            print("no cms_activity", act); return 1
        start, end = row[0]
        dur = (end - start) if (start and end) else datetime.timedelta(hours=4)
        now = datetime.datetime.strptime(cl.q("SELECT DATE_FORMAT(NOW(),'%%Y-%%m-%%d %%H:%%i:%%s')")[0][0], "%Y-%m-%d %H:%M:%S")
        # the app runs in Asia/Shanghai while MySQL runs at +00:00: activity times are stored as Shanghai wall time
        sh_now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
        new_start = (sh_now + datetime.timedelta(minutes=mins)).replace(microsecond=0)
        cl.ex("UPDATE cms_activity SET start_date=%s,end_date=%s WHERE id=%s", (new_start, new_start + dur, act))
        print("FIXTURE TIME SHIFT cms_activity %d: %s..%s -> %s..%s (Shanghai wall time; mysql NOW()=%s UTC)" % (
            act, start, end, new_start, new_start + dur, now))
        print("Redis caches may hold the old window; if a page still says 未开始, re-open it.")
    else:
        print(__doc__); return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

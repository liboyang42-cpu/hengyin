"""qx-c1 rig helpers (rig-side only, not product code): real HTTP to the candidate on 127.0.0.1:8099 + MySQL read-back.

Run with the rig venv:  CAND=<repo> RIG_HOME=~/walk-rig ~/walk-rig/venv/bin/python <script>
"""
import json, os, sys, urllib.parse, urllib.request
from pathlib import Path
import pymysql

RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
import chain_lib as cl  # noqa: E402

BASE = "http://127.0.0.1:8099"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
LOG = []


def db():
    return pymysql.connect(host="127.0.0.1", port=3316, user="root", password="", database="cy_e2e_51",
                           charset="utf8mb4", autocommit=True, cursorclass=pymysql.cursors.DictCursor)


def q(sql, *args):
    with db() as c, c.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall()


def x(sql, *args):
    with db() as c, c.cursor() as cur:
        cur.execute(sql, args)
        return cur.lastrowid


def app_token(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request(BASE + "/api/login/code", data=data, method="POST",
                                 headers={"content-type": "application/x-www-form-urlencoded"})
    return json.loads(OPENER.open(req, timeout=20).read().decode())["token"]


def admin_token():
    return cl.admin_token() if hasattr(cl, "admin_token") else None


def call(method, path, token=None, form=None, body=None, label=None):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["content-type"] = "application/x-www-form-urlencoded"
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        headers["content-type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        resp = OPENER.open(req, timeout=60)
        status, text = resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    try:
        parsed = json.loads(text)
    except ValueError:
        parsed = {"raw": text[:500]}
    LOG.append({"label": label or path, "request": {"method": method, "path": path, "form": form, "body": body},
                "httpStatus": status, "response": parsed})
    return parsed


def note(label, value):
    LOG.append({"label": label, "readBack": value})
    return value


def dump(path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(LOG, ensure_ascii=False, indent=1, default=str) + "\n", encoding="utf-8")

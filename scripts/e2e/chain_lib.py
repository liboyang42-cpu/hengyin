#!/usr/bin/env python3
"""5-1 四条链路的共用件:真 HTTP 调用 + 真 SQL 回读。

纪律(别改松):
  · 库回读一律直连本地 MySQL 跑真 SQL,不用应用接口回读它自己刚写的值;
  · 后端与脚本必须共用 CHENGYIN_E2E_DB_* 配置；首条 SQL 前回读 SELECT DATABASE() 防串库;
  · 每一步都打印【原始】响应与【原始】回读行,不做美化;
  · 拒连生产主机。
"""
import hashlib, json, os, urllib.request, urllib.parse, urllib.error
import pymysql

# 生产主机不写明文(公开仓):内置 SHA-256 黑名单 + 环境变量 CHENGYIN_FORBIDDEN_DB_HOSTS(逗号分隔)。
FORBIDDEN_HOSTS_ENV = "CHENGYIN_FORBIDDEN_DB_HOSTS"
FORBIDDEN_SHA256 = frozenset({
    "d8c695fe7dd5f6ffc048b72c96621f7e7d13d3ec23b76aab523514183e7474d9",  # 生产库主机
    "ce1a8527392b68bab1ab6a91238a5e6909cbbb937b9eb077d1025499a59f03fe",  # 生产应用机
})


def _normalize_host(host):
    return (host or "").strip().lower().strip("[]")


def is_forbidden_host(host, environ=None, forbidden_sha256=FORBIDDEN_SHA256):
    normalized = _normalize_host(host)
    if not normalized:
        return False
    environ = os.environ if environ is None else environ
    denylist = {_normalize_host(h) for h in environ.get(FORBIDDEN_HOSTS_ENV, "").split(",")}
    if normalized in denylist:
        return True
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest() in forbidden_sha256


def load_base(environ, forbidden_sha256=FORBIDDEN_SHA256):
    """后端地址默认本机 8099;CI 可经 CHENGYIN_E2E_BASE 覆盖，但同样拒连生产主机。"""
    base = environ.get("CHENGYIN_E2E_BASE", "http://127.0.0.1:8099")
    host = urllib.parse.urlsplit(base).hostname
    if not host or is_forbidden_host(host, environ, forbidden_sha256):
        raise SystemExit("拒绝连接生产主机")
    return base.rstrip("/")


BASE = load_base(os.environ)


def load_db_config(environ):
    try:
        port = int(environ.get("CHENGYIN_E2E_DB_PORT", "3316"))
    except ValueError:
        raise SystemExit("CHENGYIN_E2E_DB_PORT 必须是整数")
    return dict(
        host=environ.get("CHENGYIN_E2E_DB_HOST", "127.0.0.1"),
        port=port,
        user=environ.get("CHENGYIN_E2E_DB_USER", "root"),
        password=environ.get("CHENGYIN_E2E_DB_PASSWORD", ""),
        database=environ.get("CHENGYIN_E2E_DB_SCHEMA", "cy_e2e_51"),
        autocommit=True,
        charset="utf8mb4",
    )




def validate_db_config(config, environ=None, forbidden_sha256=FORBIDDEN_SHA256):
    if is_forbidden_host(config["host"], environ, forbidden_sha256):
        raise SystemExit("拒绝连接生产主机")
    if not config["database"].startswith("cy_e2e_"):
        raise SystemExit("只允许连接 cy_e2e_* 测试库")


def verify_connected_database(connection, expected):
    cur = connection.cursor()
    try:
        cur.execute("SELECT DATABASE()")
        actual = (cur.fetchone() or (None,))[0]
    finally:
        cur.close()
    if actual != expected:
        raise RuntimeError("SQL readback 串库:连接到 %s，配置要求 %s" % (actual, expected))


DB = load_db_config(os.environ)
validate_db_config(DB)


def db():
    connection = pymysql.connect(**DB)
    try:
        verify_connected_database(connection, DB["database"])
    except Exception:
        connection.close()
        raise
    return connection


def q(sql, args=None):
    c = db(); cur = c.cursor()
    cur.execute(sql, args or ())
    rows = cur.fetchall(); cur.close(); c.close()
    return rows


def ex(sql, args=None):
    c = db(); cur = c.cursor()
    cur.execute(sql, args or ())
    n = cur.rowcount; cur.close(); c.close()
    return n


def http(method, path, token=None, json_body=None, form=None, timeout=20):
    url = BASE + path
    data, headers = None, {}
    if json_body is not None:
        data = json.dumps(json_body, ensure_ascii=False).encode()
        headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", "replace")
            status = r.status
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace"); status = e.code
    except Exception as e:
        return {"_status": -1, "_raw": "%s: %s" % (type(e).__name__, e)}
    try:
        out = json.loads(body)
    except Exception:
        out = {"_raw": body[:400]}
    out["_status"] = status
    return out


# 一次性 E2E 库专用超管夹具口令(与 seed_fixtures.SUPERADMIN_HASH 对应)。后端拒绝 admin/ry
# 使用 RuoYi 公开默认口令(F-L5S-09),所以这里不能再是 admin123。
E2E_SUPERADMIN_PASSWORD = "E2e-Fixture-Adm1n!"


def admin_token():
    r = http("POST", "/login", json_body={"username": "admin", "password": E2E_SUPERADMIN_PASSWORD})
    return r.get("token")


def app_token(phone):
    """真实走 /api/login/phone。验证码是夹具:先把它种进 Redis,登录本身仍跑真闸。"""
    import subprocess
    subprocess.run(["redis-cli", "-n", "9", "SET", "sms_%s_code" % phone, '"123456"'],
                   capture_output=True)
    r = http("POST", "/api/login/phone", form={"phone": phone, "code": "123456"})
    return r.get("token"), r


STEP = 0


def step(title):
    global STEP
    STEP += 1
    print("\n──────── 步骤 %d:%s" % (STEP, title))


def show(label, value):
    print("   %-34s %s" % (label, value))


def readback(label, sql, args=None):
    rows = q(sql, args)
    print("   [库回读] %-24s %s" % (label, rows))
    return rows

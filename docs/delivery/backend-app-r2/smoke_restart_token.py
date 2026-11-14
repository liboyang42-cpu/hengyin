#!/usr/bin/env python3
"""TEST-only health check: login -> restart TEST 8120 -> reuse pre-restart JWT + verify stable env digests."""
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

B = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8120"
if not (B.startswith("http://127.0.0.1:8120") or ".instabox.tech" in B):
    sys.exit("refusing non-TEST base url")

APP_DIR = "/data/workspace/be-int/app"
if not (
    os.path.isfile(os.path.join(APP_DIR, "application-beint.properties"))
    and os.path.isfile(os.path.join(APP_DIR, "start.sh"))
):
    sys.exit("refusing restart: TEST app directory not found at " + APP_DIR)

REDIS = ["docker", "exec", "be-int-redis", "redis-cli", "-n", "9"]
STABLE_ENV_FILES = {
    "CHENGYIN_JWT_SECRET": ".test-jwt-secret",
    "CHENGYIN_WITHDRAWAL_PII_KEY": ".test-pii-key",
    "CHENGYIN_WXMINI_SECRET": ".test-wxmini-secret",
    "CHENGYIN_WXPAY_APIV3_KEY": ".test-wxpay-apiv3-key",
    "CHENGYIN_DRUID_PASSWORD": ".test-druid-password",
    "COUPON_QR_SECRET": ".test-coupon-qr-secret",
    "CHENGYIN_EXTERNAL_GAME_SIGNING_KEY": ".test-external-game-key",
}


def call(method, path, tok=None, form=None, q=None):
    url = B + path + ("?" + urllib.parse.urlencode(q) if q else "")
    headers = {"Accept": "application/json"}
    data = None
    if tok:
        headers["Authorization"] = "Bearer " + tok
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            status, body = resp.status, resp.read()
    except urllib.error.HTTPError as err:
        status, body = err.code, err.read()
    try:
        return status, json.loads(body)
    except Exception:
        return status, {"raw": body[:200].decode("utf-8", "replace")}


def show(label, status, payload, extra=""):
    good = status == 200 and payload.get("code") in (200, None)
    print(
        "%-36s %s http=%s code=%s %s %s"
        % (
            label,
            "OK " if good else "ERR",
            status,
            payload.get("code"),
            extra,
            "" if good else json.dumps(payload, ensure_ascii=False)[:220],
        )
    )
    return good


def find_pid():
    res = subprocess.run(["pgrep", "-f", "[a]pplication-beint.properties"], capture_output=True, text=True)
    pids = [p.strip() for p in res.stdout.splitlines() if p.strip()]
    if len(pids) != 1:
        raise RuntimeError(f"expected 1 TEST 8120 java pid, found {pids}")
    return pids[0]


def env_digests(pid):
    raw = open(f"/proc/{pid}/environ", "rb").read().split(b"\x00")
    env = {}
    for entry in raw:
        if b"=" in entry:
            k, v = entry.split(b"=", 1)
            env[k.decode()] = v.decode()
    out = {}
    for var, fname in STABLE_ENV_FILES.items():
        val = env.get(var, "")
        file_val = open(os.path.join(APP_DIR, fname), "r", encoding="utf-8").read().rstrip("\r\n")
        if not val or val != file_val:
            raise RuntimeError(f"env {var} missing or does not match {fname}")
        out[var] = hashlib.sha256(val.encode()).hexdigest()[:12]
    if env["CHENGYIN_JWT_SECRET"] == env["COUPON_QR_SECRET"]:
        raise RuntimeError("CHENGYIN_JWT_SECRET must differ from COUPON_QR_SECRET")
    return out


def port_8120_open():
    out = subprocess.run(["ss", "-ltn"], capture_output=True, text=True, check=True).stdout
    return ":8120 " in out


def restart_8120(old_pid):
    subprocess.run(["kill", old_pid], check=True)
    for _ in range(30):
        if not port_8120_open():
            break
        time.sleep(1)
    if port_8120_open():
        raise RuntimeError("port 8120 still bound 30s after kill")

    log_path = os.path.join(APP_DIR, "app.log")
    if os.path.exists(log_path):
        os.rename(log_path, os.path.join(APP_DIR, "app.log.prev-" + time.strftime("%H%M%S")))

    subprocess.run(
        ["bash", "-lc", f"cd {APP_DIR} && setsid nohup bash start.sh > app.log 2>&1 < /dev/null &"],
        check=True,
    )
    for _ in range(60):
        if port_8120_open():
            break
        time.sleep(1)
    if not port_8120_open():
        raise RuntimeError("port 8120 failed to bind within 60s")
    time.sleep(1)
    return find_pid()


def main():
    phone = "13900000009"
    subprocess.run(
        REDIS
        + [
            "DEL",
            f"sms_limit_phone_{phone}",
            f"sms_send_phone_day_{phone}",
            "sms_send_ip_window_127.0.0.1",
            "sms_verify_ip_window_127.0.0.1",
        ],
        capture_output=True,
    )
    st, d = call("POST", "/api/sms/send", form={"phone": phone})
    if not show("pre-restart sms/send", st, d):
        sys.exit(1)
    code = subprocess.run(REDIS + ["GET", f"sms_{phone}_code"], capture_output=True, text=True).stdout.strip().strip("\"")
    st, d = call("POST", "/api/login/phone", form={"phone": phone, "code": code})
    if not show("pre-restart login 9009", st, d, "role=%s" % (d.get("data") or {}).get("role")):
        sys.exit(1)
    token = d["token"]

    st, d = call("POST", "/api/userInfo", tok=token)
    uid_before = (d.get("appUser") or {}).get("userId")
    if not show("pre-restart userInfo", st, d, f"userId={uid_before}") or uid_before != 9009:
        sys.exit(1)

    old_pid = find_pid()
    before_digests = env_digests(old_pid)
    print(f"restarting TEST 8120 (pid {old_pid})...")
    new_pid = restart_8120(old_pid)
    if new_pid == old_pid:
        sys.exit(f"ERR pid did not change ({old_pid})")
    after_digests = env_digests(new_pid)
    if before_digests != after_digests:
        sys.exit(f"ERR env digests changed across restart: {before_digests} vs {after_digests}")
    print(f"restart complete (pid {old_pid} -> {new_pid}); 7/7 env sha256 prefixes unchanged")

    for attempt in range(10):
        try:
            st, d = call("POST", "/api/userInfo", tok=token)
            break
        except urllib.error.URLError:
            if attempt == 9:
                raise
            time.sleep(1)
    uid_after = (d.get("appUser") or {}).get("userId")
    if not show("post-restart userInfo (reused jwt)", st, d, f"userId={uid_after}") or uid_after != 9009:
        sys.exit(1)

    st, d = call("GET", "/api/play/nodes", tok=token, q={"activityId": 992031, "topicId": 992001})
    x = d.get("data") or {}
    reg = x.get("registered")
    tot = x.get("total")
    if not show("post-restart play/nodes (reused jwt)", st, d, f"registered={reg} total={tot}"):
        sys.exit(1)

    print("TOKEN_ACROSS_RESTART_OK")


if __name__ == "__main__":
    main()

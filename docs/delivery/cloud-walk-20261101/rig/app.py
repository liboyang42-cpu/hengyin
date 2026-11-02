#!/usr/bin/env python3
"""Start / stop / status the candidate app for the cloud walk rig.

Reuses ci/chain_acceptance.py (java_command, app_env, fixture config, logback) from the
candidate checkout, but keeps the app running in the background and persists the throwaway
secrets in $RIG_HOME/secrets.env (outside the repository, mode 600) so the PII key is
stable across restarts (BRIEF: otherwise registration / CRM reads fail with 「PII 解密失败」).
"""
import argparse, os, signal, subprocess, sys, time, urllib.request
from pathlib import Path

RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
sys.path.insert(0, str(CAND / "ci"))
import chain_acceptance as ca  # noqa: E402

SECRET_KEYS = ("CHENGYIN_JWT_SECRET", "CHENGYIN_WITHDRAWAL_PII_KEY", "CHENGYIN_WXMINI_SECRET",
               "CHENGYIN_WXPAY_APIV3_KEY", "CHENGYIN_DRUID_PASSWORD", "COUPON_QR_SECRET")
PID = RIG_HOME / "app.pid"
LOG = RIG_HOME / "out" / "application.log"


def env():
    work = RIG_HOME / "work"
    e = ca.app_env(dict(os.environ), work)
    sf = RIG_HOME / "secrets.env"
    if sf.exists():
        for line in sf.read_text().splitlines():
            k, _, v = line.partition("=")
            if k in SECRET_KEYS:
                e[k] = v
    else:
        sf.write_text("".join("%s=%s\n" % (k, e[k]) for k in SECRET_KEYS))
        os.chmod(sf, 0o600)
    return e


def alive():
    try:
        pid = int(PID.read_text())
        os.kill(pid, 0)
        return pid
    except Exception:
        return None


def start(fail_path=""):
    if alive():
        print("app already running pid", alive()); return 0
    LOG.parent.mkdir(parents=True, exist_ok=True)
    cmd = ca.java_command(RIG_HOME / "work" / "app", fail_path)
    # [SEAM] login: profile "local" lets the product's own non-prod switch send jscode2session to the rig's fake
    # WeChat endpoint (mpweb/server.mjs on 8097). Only /sns/jscode2session uses this base URL.
    cmd += ["--spring.profiles.active=druid,local",
            "--chengyin.test.wechat-api-base-url=http://127.0.0.1:%s" % os.environ.get("RIG_WEB_PORT", "8097")]
    log = open(LOG, "ab")
    p = subprocess.Popen(cmd, cwd=str(RIG_HOME / "work"), env=env(), stdout=log, stderr=subprocess.STDOUT,
                         start_new_session=True)
    PID.write_text(str(p.pid))
    start_size = LOG.stat().st_size if LOG.exists() else 0
    t0 = time.time()
    while time.time() - t0 < ca.APP_READY_SECONDS:
        if p.poll() is not None:
            print("app exited", p.returncode, "see", LOG); return 1
        with open(LOG, "rb") as fh:
            fh.seek(max(0, start_size - 10))
            if ca.APP_READY_LINE.encode() in fh.read():
                print("app ready on %s in %.0fs (pid %d, log %s)" % (ca.APP_BASE, time.time() - t0, p.pid, LOG))
                return 0
        time.sleep(2)
    print("app not ready in time; see", LOG); return 1


def stop():
    pid = alive()
    if pid:
        os.killpg(pid, signal.SIGTERM)
        for _ in range(30):
            if not alive(): break
            time.sleep(1)
    PID.unlink(missing_ok=True)
    print("app stopped")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["start", "stop", "restart", "status", "env"])
    ap.add_argument("--fail-path", default="")
    a = ap.parse_args()
    if a.cmd == "start": sys.exit(start(a.fail_path))
    if a.cmd == "stop": sys.exit(stop())
    if a.cmd == "restart": stop(); sys.exit(start(a.fail_path))
    if a.cmd == "status":
        print("pid", alive()); sys.exit(0 if alive() else 1)
    if a.cmd == "env":  # prints export lines for chain scripts (no secrets)
        print("export PATH=%s:$PATH" % (RIG_HOME / "work" / "bin"))

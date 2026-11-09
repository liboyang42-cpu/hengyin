"""Rig-only helpers for the l8-safety Round5E proof. Throwaway DB cy_e2e_51 on 127.0.0.1:3316 only."""
import json, os, sys, pathlib, datetime
CAND = os.environ.get("CAND", os.path.expanduser("~/walk-rig/candidate"))
sys.path.insert(0, str(pathlib.Path(CAND) / "scripts" / "e2e"))
os.environ["PATH"] = os.path.expanduser("~/walk-rig/work/bin") + ":" + os.environ["PATH"]
from chain_lib import http, q, ex, admin_token, app_token  # noqa: E402

MODE_FILE = os.environ.get("RIG_WXSEC_MODE_FILE", "/tmp/rig-wxsec-mode")
OUT = []

def wx_mode(m):
    open(MODE_FILE, "w").write(m)

def rec(name, ok, **detail):
    OUT.append({"check": name, "pass": bool(ok), **detail})
    print(("PASS " if ok else "FAIL ") + name + "  " + json.dumps(detail, ensure_ascii=False, default=str)[:400])

def player(n):
    tok, r = app_token("1390000000%d" % n)
    assert tok, r
    return tok

def dump(path):
    pathlib.Path(path).write_text(json.dumps(OUT, ensure_ascii=False, indent=1, default=str) + "\n")

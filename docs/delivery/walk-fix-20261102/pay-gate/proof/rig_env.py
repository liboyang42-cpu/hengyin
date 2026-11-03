"""Shared rig plumbing for the Round 3D proofs. Throwaway cloud-walk rig only (127.0.0.1:8099, MySQL cy_e2e_51 on
127.0.0.1:3316). Uses the candidate's own scripts/e2e/chain_lib and chain_player.store_trusted_payment_fact (verified
payment fact + the REAL /api/registration/callback). payment_status is never written by these scripts."""
import contextlib, io, json, os, sys
from pathlib import Path
RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
os.environ["PATH"] = "%s:%s" % (RIG_HOME / "work" / "bin", os.environ.get("PATH", ""))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1",
             "CHENGYIN_E2E_DB_PORT": "3316", "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "",
             "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
with contextlib.redirect_stdout(io.StringIO()):
    import chain_player as cp  # noqa: E402
from chain_lib import http, q, admin_token, app_token, step, show, readback  # noqa: E402,F401

CLUB_ID, OWNER_PHONE, OWNER_ID = 7002, "13900000008", 9008


def j(x, n=300):
    return json.dumps(x, ensure_ascii=False, default=str)[:n]


def pay(reg_no, reg_id):
    """Rig payment seam: VERIFIED raw payment fact + the real callback. Never writes payment_status."""
    amount = q("SELECT COALESCE(wechat_payment_amount,payable_amount) FROM cms_registration WHERE id=%s", (reg_id,))[0][0]
    fen = int(round(float(amount) * 100))
    txn = "R3D-TXN-%d" % reg_id
    with contextlib.redirect_stdout(io.StringIO()):
        fact = cp.store_trusted_payment_fact(reg_no, txn, fen)
    cb = http("POST", "/api/registration/callback", json_body=dict(fact, **{
        "out_trade_no": reg_no, "transaction_id": txn, "trade_state": "SUCCESS", "total": fen}))
    show("callback (%s fen)" % fen, j(cb))
    return cb

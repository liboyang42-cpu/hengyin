#!/usr/bin/env python3
"""[SEAM] Payment success through the REAL callback path, exactly as scripts/e2e/chain_player.py does it:
a VERIFIED raw payment fact is written (standing in for the WeChat Pay SDK query that cannot run here), then
POST /api/registration/callback runs the real handler (amount freeze check, order state machine, entitlements).
payment_status is never written directly.

  pay.py --member 9009          # newest unpaid registration of that member
  pay.py --reg-no A2026...      # a specific registration
Used by mpweb/server.mjs for wx.requestPayment; usable by hand too."""
import argparse, json, os, sys
from pathlib import Path
RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
import io, contextlib  # noqa: E402
with contextlib.redirect_stdout(io.StringIO()):
    import chain_player as cp  # noqa: E402
from chain_lib import http, q  # noqa: E402


def pay(member=None, reg_no=None):
    if reg_no:
        rows = q("SELECT id,registration_no,COALESCE(wechat_payment_amount,payable_amount),payment_status FROM cms_registration "
                 "WHERE registration_no=%s", (reg_no,))
    else:
        rows = q("SELECT id,registration_no,COALESCE(wechat_payment_amount,payable_amount),payment_status FROM cms_registration "
                 "WHERE member_id=%s AND payment_status<>2 AND payment_time IS NULL ORDER BY id DESC LIMIT 1", (member,))
    if not rows:
        return {"ok": False, "error": "no unpaid registration for member=%s reg_no=%s" % (member, reg_no)}
    rid, rno, amount, before = rows[0]
    total_fen = int(round(float(amount or 0) * 100))
    txn = "RIG-TXN-%d" % rid
    with contextlib.redirect_stdout(io.StringIO()):
        fact = cp.store_trusted_payment_fact(rno, txn, total_fen)
        cb = http("POST", "/api/registration/callback", json_body=dict(fact, **{
            "out_trade_no": rno, "transaction_id": txn, "trade_state": "SUCCESS", "total": total_fen}))
    after = q("SELECT payment_status,payment_time,transaction_id FROM cms_registration WHERE id=%s", (rid,))[0]
    ok = cb.get("_status") == 204 and after[2] == txn
    return {"ok": ok, "registrationId": rid, "registrationNo": rno, "totalFen": total_fen, "callbackStatus": cb.get("_status"),
            "paymentStatusBefore": before, "readback": [str(x) for x in after], "callbackBody": str(cb.get("_raw", ""))[:200]}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--member"); ap.add_argument("--reg-no"); ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    try:
        r = pay(a.member, a.reg_no)
    except Exception as e:  # noqa: BLE001
        r = {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}
    print(json.dumps(r, ensure_ascii=False))
    sys.exit(0 if r.get("ok") else 1)

import json
import os
import tempfile
import types
import unittest

from scripts.e2e import free_cancel_acceptance as fca

PLAYER, ORGANIZER, REG, ACT = 9101, 9102, 7001, 501
TOKENS = {"CHENGYIN_E2E_PLAYER_TOKEN": "player-secret-token", "CHENGYIN_E2E_ORGANIZER_TOKEN": "organizer-secret-token"}


class Backend:
    """假后端:库状态在一处,HTTP 与 SQL 都从它读,取消只改它一次。"""

    def __init__(self, cancel_reply="ok", commit=True, payable=0, roster_lags=False, owners=None):
        self.cancelled, self.cancel_reply, self.commit = False, cancel_reply, commit
        self.payable, self.roster_lags = payable, roster_lags
        self.owners = owners or {"player-secret-token": PLAYER, "organizer-secret-token": ORGANIZER}
        self.calls = []

    def q(self, sql, args=None):
        active = not self.cancelled
        if sql == fca.REGISTRATION_SQL:
            return ((REG, PLAYER, 2, ACT, self.payable, 0, 2, 2 if active else 3, 0),)
        if sql == fca.ENTITLEMENT_SQL:
            return ((1, 0 if active else 2),)
        if sql == fca.SIGNED_UP_SQL:
            return ((5 if active else 4,),)
        raise AssertionError(sql)

    def http(self, method, path, token=None, json_body=None, form=None, timeout=20):
        self.calls.append(path)
        if path == "/api/userInfo":
            return {"_status": 200, "code": 200, "appUser": {"userId": self.owners.get(token)}}
        if path == "/api/club/event-ops/roster":
            listed = (not self.cancelled) or self.roster_lags
            return {"_status": 200, "code": 200, "data": {"registered": [{"memberId": PLAYER}] if listed else [],
                                                          "arrived": [], "noShow": []}}
        if path == "/api/registration/list":
            status = 3 if self.cancelled else 2
            return {"_status": 200, "code": 200, "data": {"rows": [
                {"id": REG, "paymentStatus": 2, "registrationStatus": status, "canRequestRefund": not self.cancelled}]}}
        if path == "/api/registration/cancel-refund":
            if self.cancel_reply == "rejected":
                return {"_status": 200, "code": 500, "msg": "场次已开始，不可取消报名"}
            if self.commit:
                self.cancelled = True
            if self.cancel_reply == "lost":
                return {"_status": -1, "_raw": "TimeoutError: timed out"}
            return {"_status": 200, "code": 200, "msg": "报名已取消", "token": "should-not-leak"}
        raise AssertionError(path)


def args(**overrides):
    base = dict(registration_id=REG, club_id=88, player_member_id=PLAYER, organizer_member_id=ORGANIZER,
                settle_seconds=0)
    base.update(overrides)
    return types.SimpleNamespace(**base)


class FreeCancelAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "evidence.jsonl")

    def tearDown(self):
        self.dir.cleanup()

    def run_case(self, backend, **overrides):
        evidence = fca.Evidence(self.path)
        result = fca.run(args(**overrides), backend.http, backend.q, evidence, TOKENS, sleep=lambda _: None)
        with open(self.path, encoding="utf-8") as f:
            lines = [json.loads(line) for line in f]
        return result, lines

    def test_free_cancel_passes_only_when_db_roster_and_order_agree(self):
        backend = Backend()
        (verdict, reasons), lines = self.run_case(backend)
        self.assertEqual(("PASS", []), (verdict, reasons))
        self.assertEqual(["session.player", "session.organizer", "before", "cancel", "after", "verdict"],
                         [line["stage"] for line in lines])
        self.assertEqual(1, backend.calls.count("/api/registration/cancel-refund"))

    def test_raw_cancel_reply_is_kept_before_judging_and_tokens_never_reach_evidence(self):
        _, lines = self.run_case(Backend())
        cancel = next(line for line in lines if line["stage"] == "cancel")
        self.assertEqual("报名已取消", cancel["data"]["msg"])
        self.assertEqual("<redacted>", cancel["data"]["token"])
        with open(self.path, encoding="utf-8") as f:
            text = f.read()
        for secret in list(TOKENS.values()) + ["should-not-leak"]:
            self.assertNotIn(secret, text)

    def test_existing_evidence_file_is_never_overwritten(self):
        with open(self.path, "w", encoding="utf-8") as f:
            f.write("earlier abnormal result\n")
        with self.assertRaises(SystemExit):
            fca.Evidence(self.path)
        with open(self.path, encoding="utf-8") as f:
            self.assertEqual("earlier abnormal result\n", f.read())

    def test_stale_session_stops_before_any_write(self):
        backend = Backend(owners={"player-secret-token": None, "organizer-secret-token": ORGANIZER})
        (verdict, _), _ = self.run_case(backend)
        self.assertEqual("STOP_SESSION", verdict)
        self.assertNotIn("/api/registration/cancel-refund", backend.calls)

    def test_paid_order_is_not_a_free_cancellation_target(self):
        backend = Backend(payable=9.9)
        (verdict, _), _ = self.run_case(backend)
        self.assertEqual("STOP_PRECONDITION", verdict)
        self.assertNotIn("/api/registration/cancel-refund", backend.calls)

    def test_lost_reply_is_not_retried_and_is_judged_by_readback(self):
        backend = Backend(cancel_reply="lost", commit=True)
        (verdict, _), lines = self.run_case(backend)
        self.assertEqual("UNKNOWN_COMMITTED", verdict)
        self.assertEqual(1, backend.calls.count("/api/registration/cancel-refund"))
        self.assertEqual(-1, next(l for l in lines if l["stage"] == "cancel")["data"]["_status"])

    def test_lost_reply_without_commit_fails_instead_of_retrying(self):
        backend = Backend(cancel_reply="lost", commit=False)
        (verdict, reasons), _ = self.run_case(backend)
        self.assertEqual("FAIL", verdict)
        self.assertIn("取消请求结果=UNKNOWN", reasons)
        self.assertEqual(1, backend.calls.count("/api/registration/cancel-refund"))

    def test_business_rejection_stops_without_polling_or_retry(self):
        backend = Backend(cancel_reply="rejected")
        (verdict, reasons), _ = self.run_case(backend, settle_seconds=30)
        self.assertEqual("FAIL", verdict)
        self.assertIn("库里报名仍有效", reasons)
        self.assertEqual(1, backend.calls.count("/api/registration/cancel-refund"))
        self.assertEqual(2, backend.calls.count("/api/club/event-ops/roster"))  # 取消前一次 + 取消后一次,不轮询

    def test_roster_still_listing_cancelled_player_fails(self):
        (verdict, reasons), _ = self.run_case(Backend(roster_lags=True))
        self.assertEqual("FAIL", verdict)
        self.assertIn("报名已失效但主理人名册仍列出该玩家", reasons)

    def test_classify_separates_rejection_from_unknown(self):
        self.assertEqual("UNKNOWN", fca.classify({"_status": -1}))
        self.assertEqual("UNKNOWN", fca.classify({"_status": 502}))
        self.assertEqual("REJECTED", fca.classify({"_status": 200, "code": 500}))
        self.assertEqual("OK", fca.classify({"_status": 200, "code": 200}))


if __name__ == "__main__":
    unittest.main()

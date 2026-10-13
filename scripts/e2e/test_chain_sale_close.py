import datetime
import unittest

from scripts.e2e.chain_sale_close import (
    PAYEE_LICENSE_HASH,
    PAYEE_LICENSE_OBJECT_KEY,
    SESSION_COUNTS_SQL,
    assert_payee_readback,
    pending_session_days,
    recover_key_bound_payee_fixture,
    session_days_from_template,
)


class ChainSaleCloseSessionPlanTest(unittest.TestCase):
    def test_key_bound_e2e_payee_is_reset_only_after_decrypt_failure(self):
        rows = ((1, PAYEE_LICENSE_OBJECT_KEY, PAYEE_LICENSE_HASH),)
        deletes = []
        result = recover_key_bound_payee_fixture(
            {"code": 500}, rows, (), lambda: deletes.append(True) or 1)
        self.assertEqual("RESET_KEY_BOUND_FIXTURE", result)
        self.assertEqual([True], deletes)

        self.assertEqual(
            "READABLE",
            recover_key_bound_payee_fixture({"code": 200}, rows, (), lambda: 0),
        )
        self.assertEqual(
            "ABSENT",
            recover_key_bound_payee_fixture({"code": 500}, (), (), lambda: 0),
        )
        for bad_rows, snapshots in (
            (((1, "not-e2e", PAYEE_LICENSE_HASH),), ()),
            (rows, ((990028,),)),
        ):
            with self.subTest(rows=bad_rows, snapshots=snapshots), self.assertRaises(RuntimeError):
                recover_key_bound_payee_fixture({"code": 500}, bad_rows, snapshots, lambda: 1)

    def test_payee_api_readback_must_prove_current_key_can_decrypt(self):
        assert_payee_readback({
            "code": 200,
            "data": {"legalName": "E2E 测试俱乐部有限公司", "verifyStatus": "VERIFIED"},
        }, expected_status="VERIFIED")
        for response in (
            {"code": 500, "msg": "系统繁忙，请稍后重试"},
            {"code": 200, "data": {"legalName": "错误主体", "verifyStatus": "VERIFIED"}},
            {"code": 200, "data": {"legalName": "E2E 测试俱乐部有限公司", "verifyStatus": "PENDING"}},
        ):
            with self.subTest(response=response), self.assertRaises(RuntimeError):
                assert_payee_readback(response, expected_status="VERIFIED")

    def test_session_count_query_groups_by_the_selected_expression(self):
        expression = "DATE_FORMAT(start_date,'%%Y-%%m-%%d')"
        self.assertIn("SELECT %s,COUNT(*)" % expression, SESSION_COUNTS_SQL)
        self.assertIn("GROUP BY %s" % expression, SESSION_COUNTS_SQL)
        self.assertNotIn("GROUP BY DATE(start_date)", SESSION_COUNTS_SQL)

    def test_one_topic_template_expands_to_four_distinct_days(self):
        days = session_days_from_template(((datetime.datetime(2026, 10, 1, 10, 0),),))
        self.assertEqual(
            ("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04"), days)
        self.assertEqual(4, len(set(days)))

    def test_rerun_skips_one_existing_session_per_day_but_rejects_duplicates(self):
        days = ("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04")
        self.assertEqual((), pending_session_days(days, tuple((day, 1) for day in days)))
        with self.assertRaisesRegex(RuntimeError, "重复场次"):
            pending_session_days(days, (("2026-10-01", 2),))


if __name__ == "__main__":
    unittest.main()

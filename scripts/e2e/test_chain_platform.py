import sqlite3
import unittest

from scripts.e2e.chain_platform import (
    CLUB_ID,
    PUBLISHER_MEMBER,
    assert_expected_sale_block,
    fixture_topic_image_fields,
    assert_topic_club_ownership,
    club_publish_fields,
)
from scripts.e2e.chain_player import assert_paid_callback
from scripts.e2e.chain_merchant_club import (
    poster_code_status,
    require_ok,
    resolve_recruit_job,
)
from scripts.e2e.seed_fixtures import (
    DISCOVERABLE_MERCHANTS,
    RECRUIT_JOB_TARGET,
    assert_discoverable_merchants,
    assert_no_fake_fixture_images,
    clear_fake_fixture_images,
    seed_discoverable_merchants,
    seed_merchant_node_prerequisites,
    seed_recruit_deadline_job,
)


class ChainPlatformSaleGateTest(unittest.TestCase):
    def test_node_prerequisites_fill_only_missing_approved_fixture_identity(self):
        with sqlite3.connect(":memory:") as db:
            db.create_function("NOW", 0, lambda: "2026-09-28 00:00:00")
            db.executescript("""
                CREATE TABLE dual (n); INSERT INTO dual VALUES (1);
                CREATE TABLE user_permission (role,max_themes,max_events,max_coupons,
                    can_create_theme,can_create_event,can_publish_coupon,can_redeem_coupon,
                    can_open_as_node,max_city_nodes,membership_level);
                CREATE TABLE member_capability (member_id,capability,status,granted_at,granted_by,create_time);
                CREATE TABLE mms_merchant (id,member_id,status,account_status,del_flag);
                INSERT INTO mms_merchant VALUES (8001,9002,1,1,0),(8002,9005,0,1,0),
                    (8003,9006,1,2,0),(8004,9007,1,1,1),(8005,9003,1,1,0);
            """)
            execute = lambda sql, args=(): db.execute(sql.replace("%s", "?"), args)
            seed_merchant_node_prerequisites(execute)
            self.assertEqual([("merchant", 1, 1)], db.execute(
                "SELECT role,can_open_as_node,max_city_nodes FROM user_permission").fetchall())
            self.assertEqual([(9002, "merchant", 1)], db.execute(
                "SELECT member_id,capability,status FROM member_capability").fetchall())
            db.execute("UPDATE user_permission SET max_city_nodes=0")
            db.execute("UPDATE member_capability SET status=0")
            seed_merchant_node_prerequisites(execute)
            self.assertEqual([(0,)], db.execute("SELECT max_city_nodes FROM user_permission").fetchall())
            self.assertEqual([(0,)], db.execute("SELECT status FROM member_capability").fetchall())

    def test_fixture_images_use_explicit_empty_fallback_not_fake_relative_urls(self):
        self.assertEqual({"imgUrl": ""}, fixture_topic_image_fields())
        calls = []
        clear_fake_fixture_images(lambda sql, args=None: calls.append((sql, args)))
        self.assertEqual(2, len(calls))
        self.assertTrue(any("cms_member_template" in sql for sql, _ in calls))
        self.assertTrue(any("cms_topic" in sql for sql, _ in calls))
        assert_no_fake_fixture_images((("template", 6001, None), ("topic", 990028, "")))
        for path in ("tpl.jpg", "cover.jpg"):
            with self.subTest(path=path), self.assertRaises(RuntimeError):
                assert_no_fake_fixture_images((("fixture", 1, path),))

    def test_topic_create_declares_and_reads_back_club_ownership(self):
        self.assertEqual({"clubId": CLUB_ID}, club_publish_fields())
        assert_topic_club_ownership(((PUBLISHER_MEMBER, CLUB_ID),))
        for rows in ((), ((PUBLISHER_MEMBER, None),), ((PUBLISHER_MEMBER, 9999),), ((9999, CLUB_ID),)):
            with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                assert_topic_club_ownership(rows)

    def test_seed_keeps_four_merchants_discoverable_with_renderable_profiles(self):
        calls = []
        seed_discoverable_merchants(lambda sql, args=None: calls.append((sql, args)))
        self.assertEqual(4, len(calls))
        self.assertTrue(all("UPDATE mms_merchant" in sql for sql, _ in calls))
        self.assertEqual(tuple(m[0] for m in DISCOVERABLE_MERCHANTS),
                         tuple(args[-1] for _, args in calls))

        rows = tuple((merchant_id, member_id, name, 1, 1, capacity, available_time,
                      suit_types, charge_type, address, city_role, description)
                     for merchant_id, member_id, name, capacity, available_time, suit_types,
                     charge_type, address, city_role, description in DISCOVERABLE_MERCHANTS)
        assert_discoverable_merchants(rows)
        bad_rows = (rows[0][:3] + (0,) + rows[0][4:],) + rows[1:]
        with self.assertRaises(RuntimeError):
            assert_discoverable_merchants(bad_rows)

    def test_expected_pricing_block_is_explicit_and_preserves_handoff(self):
        result = assert_expected_sale_block(
            {"code": 500, "msg": "只有 PRICING 才可提审"},
            ((1, None),),
            ((0,),),
        )
        self.assertIn("EXPECTED_BLOCKED", result)

    def test_unexpected_sale_result_or_state_fails_closed(self):
        cases = (
            ({"code": 200, "msg": "成功"}, ((1, None),), ((0,),)),
            ({"code": 500, "msg": "未知错误"}, ((1, None),), ((0,),)),
            ({"code": 500, "msg": "只有 PRICING 才可提审"}, ((2, None),), ((0,),)),
            ({"code": 500, "msg": "只有 PRICING 才可提审"}, ((1, None),), ((1,),)),
        )
        for response, state, snapshots in cases:
            with self.subTest(response=response, state=state, snapshots=snapshots):
                with self.assertRaises(RuntimeError):
                    assert_expected_sale_block(response, state, snapshots)

    def test_chapter_recruit_write_fails_immediately(self):
        require_ok({"code": 200, "msg": "成功"}, "chapter 1 recruit")
        with self.assertRaisesRegex(RuntimeError, "chapter 1 recruit 失败"):
            require_ok({"code": 500, "msg": "未知列"}, "chapter 1 recruit")

    def test_recruit_job_is_seeded_and_resolved_by_invoke_target(self):
        calls = []
        seed_recruit_deadline_job(lambda sql, args=None: calls.append((sql, args)))
        self.assertEqual(1, len(calls))
        self.assertEqual((RECRUIT_JOB_TARGET, RECRUIT_JOB_TARGET), calls[0][1])
        self.assertEqual((217, "DEFAULT"), resolve_recruit_job(((217, "DEFAULT", "0"),)))
        for rows in ((), ((131, "DEFAULT", "0"), (217, "DEFAULT", "0")), ((217, "DEFAULT", "1"),)):
            with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                resolve_recruit_job(rows)

    def test_poster_code_external_block_is_explicit_but_other_500_fails(self):
        self.assertEqual("PASS", poster_code_status({"code": 200, "msg": "成功"}))
        self.assertIn(
            "EXPECTED_BLOCKED_EXTERNAL",
            poster_code_status({"code": 500, "msg": "个人码生成服务繁忙，请稍后重试"}),
        )
        with self.assertRaises(RuntimeError):
            poster_code_status({"code": 500, "msg": "Unknown column"})

    def test_payment_callback_requires_success_protocol_and_db_fact(self):
        paid = ((74, "R74", 2, "2026-09-21 12:00:00", "E2E-TXN-74"),)
        assert_paid_callback(
            {"code": "SUCCESS", "message": "成功", "_status": 200}, paid, "E2E-TXN-74")
        bad_cases = (
            ({"code": 200, "_status": 200}, paid),
            ({"code": "SUCCESS", "_status": 500}, paid),
            ({"code": "SUCCESS", "_status": 200}, ((74, "R74", 1, None, None),)),
            ({"code": "SUCCESS", "_status": 200}, ((74, "R74", 2, "2026-09-21", "OTHER"),)),
        )
        for callback, rows in bad_cases:
            with self.subTest(callback=callback, rows=rows), self.assertRaises(RuntimeError):
                assert_paid_callback(callback, rows, "E2E-TXN-74")


if __name__ == "__main__":
    unittest.main()

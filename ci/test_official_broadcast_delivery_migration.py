"""D migration source contracts only; not MySQL execution evidence."""
from pathlib import Path
import re
import unittest
from ci.migration_gates import check_sql_text
from ci.sql_splitter import executable_statement, split_statements

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "chengyinhub-admin/sql/migration_20261023_d_official_broadcast_delivery.sql"


class OfficialBroadcastDeliveryMigrationContract(unittest.TestCase):
    def test_real_splitter_and_sql_safety_accept_bounded_migration(self):
        self.assertEqual([], check_sql_text(str(MIGRATION), MIGRATION.read_text()))

    def test_unknown_channel_is_rejected_by_sql_before_alter(self):
        sql = MIGRATION.read_text()
        guard = sql[:sql.index("ALTER TABLE official_broadcast_log")]
        self.assertIn("CREATE TEMPORARY TABLE tmp_d_broadcast_delivery_guard", guard)
        self.assertIn("PRIMARY KEY", guard)
        self.assertIn("channel IS NULL", guard)
        self.assertIn("BINARY l.channel NOT IN (BINARY 'inapp', BINARY 'subscribe')", guard)
        self.assertRegex(guard, r"INSERT INTO tmp_d_broadcast_delivery_guard.*?SELECT 1 WHERE EXISTS")

    def test_only_legacy_delivery_fields_are_backfilled(self):
        statements = [executable_statement(s) for s in split_statements(MIGRATION.read_text())]
        update = next(s for s in statements if s.startswith("UPDATE official_broadcast_log"))
        self.assertIn("WHERE BINARY delivery_state = BINARY 'UNKNOWN'", update)
        for field in ["delivery_title", "delivery_sub", "failure_code", "message_id", "reached_time"]:
            self.assertIn(field + " IS NULL", update)
        assignments = update.split("SET", 1)[1].split("WHERE", 1)[0]
        for protected in ["id", "broadcast_id", "member_id", "channel", "reached", "clicked", "create_time"]:
            self.assertNotRegex(assignments, r"(?:^|,)\s*" + protected + r"\s*=")

    def test_aggregate_requires_evidence_and_an_actual_derived_difference(self):
        statements = [executable_statement(s) for s in split_statements(MIGRATION.read_text())]
        update = next(s for s in statements if s.startswith("UPDATE official_broadcast b"))
        self.assertIn("WHERE EXISTS (SELECT 1 FROM official_broadcast_log", update)
        self.assertIn("NOT (b.reached_count <=>", update)
        self.assertIn("ELSE b.status END", update)
        self.assertNotRegex(update, r"(?i)where\s+1\s*=\s*1")

    def test_preflight_and_postflight_are_executable_not_only_runner_comments(self):
        sql = MIGRATION.read_text()
        self.assertIn("-- SQL postflight:", sql)
        postflight = sql.split("-- SQL postflight:", 1)[1]
        self.assertIn("INSERT INTO tmp_d_broadcast_delivery_guard", postflight)
        self.assertIn("failure_code", postflight)
        self.assertIn("b.reached_count <=>", postflight)
        self.assertIn("DROP TEMPORARY TABLE tmp_d_broadcast_delivery_guard", postflight)

    def test_removing_either_top_level_update_scope_is_rejected(self):
        sql = MIGRATION.read_text()
        self.assertEqual([], check_sql_text(str(MIGRATION), sql))
        for marker in ["WHERE BINARY delivery_state", "WHERE EXISTS (SELECT 1 FROM official_broadcast_log"]:
            self.assertIn(marker, sql)
            # Strip the actual UPDATE's top-level condition, keeping nested WHEREs to exercise the real gate.
            statements = split_statements(sql)
            changed = False
            for i, statement in enumerate(statements):
                if executable_statement(statement).startswith("UPDATE") and marker in statement:
                    statements[i] = statement[:statement.index(marker)]
                    changed = True
                    break
            self.assertTrue(changed)
            self.assertTrue(check_sql_text(str(MIGRATION), ";\n".join(statements) + ";"))


if __name__ == "__main__":
    unittest.main()

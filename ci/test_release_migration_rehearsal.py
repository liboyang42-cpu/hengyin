#!/usr/bin/env python3
"""发布迁移包的静态契约（不连库）：pack 与 HEAD 一致、预检只读且覆盖全部发布迁移。"""
import hashlib
import json
import pathlib
import re
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "ci"))

from ci import release_migration_rehearsal as rehearsal  # noqa: E402
from ci.sql_splitter import split_statements, executable_statement  # noqa: E402

PACK = json.loads(rehearsal.PACK_JSON.read_text(encoding="utf-8"))
PRECHECK = rehearsal.PRECHECK_SQL.read_text(encoding="utf-8")
WRITE_WORDS = re.compile(r"\b(INSERT|UPDATE|DELETE|REPLACE|ALTER|CREATE|DROP|TRUNCATE|RENAME|GRANT|REVOKE|"
                         r"SET|PREPARE|EXECUTE|CALL|LOCK|HANDLER|LOAD|START|BEGIN|COMMIT|ROLLBACK|USE)\b",
                         re.IGNORECASE)
QUOTED = re.compile(r"'(?:[^'\\]|\\.|'')*'")


class ReleaseMigrationPackTest(unittest.TestCase):
    def test_pack_matches_head_release_set(self):
        _, computed = rehearsal.release_set(PACK["baseline"]["commit"])
        rehearsal.compare_pack(PACK, computed)
        self.assertEqual(len(PACK["migrations"]), len(computed))

    def test_pack_sha256_matches_files(self):
        for item in PACK["migrations"]:
            digest = hashlib.sha256((ROOT / item["path"]).read_bytes()).hexdigest()
            self.assertEqual(item["sha256"], digest, item["path"])

    def test_every_item_documents_phase_guard_risk_and_select_only_verify(self):
        for item in PACK["migrations"]:
            self.assertIn(item["phase"], PACK["phases"], item["migrationId"])
            for field in ("changes", "idempotencyGuard", "lockRisk"):
                self.assertTrue(item[field], (item["migrationId"], field))
            self.assertTrue(item["verifySelectOnly"], item["migrationId"])
            for query in item["verifySelectOnly"]:
                self.assertTrue(query.lstrip().upper().startswith("SELECT"), query)
                self.assertIsNone(WRITE_WORDS.search(QUOTED.sub("''", query.split("--")[0])), query)

    def test_precheck_is_select_only(self):
        statements = [executable_statement(s).strip() for s in split_statements(PRECHECK)]
        statements = [s for s in statements if s]
        self.assertGreaterEqual(len(statements), 5)
        for statement in statements:
            self.assertTrue(statement.upper().startswith(("SELECT", "WITH")), statement[:80])
            masked = QUOTED.sub("''", statement)
            self.assertIsNone(WRITE_WORDS.search(masked), statement[:120])

    def test_precheck_classifies_every_release_migration_once(self):
        ids = re.findall(r"AS exec_order, '([a-z0-9_]+)' AS migration_id", PRECHECK)
        self.assertEqual([m["migrationId"] for m in PACK["migrations"]], ids)

    def test_precheck_has_no_host_or_credentials(self):
        self.assertNotRegex(PRECHECK, r"\b\d{1,3}(\.\d{1,3}){3}\b")
        self.assertNotRegex(PRECHECK, r"(?i)(password|passwd|mysql\s+-h|jdbc:)")


if __name__ == "__main__":
    unittest.main()

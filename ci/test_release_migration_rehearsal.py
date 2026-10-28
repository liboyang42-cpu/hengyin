#!/usr/bin/env python3
"""发布迁移包的静态契约（不连库）：pack 与 HEAD 一致、预检只读且覆盖全部发布迁移。"""
import copy
import hashlib
import json
import pathlib
import re
import sys
import subprocess
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


    def test_source_provenance_is_exact_and_public(self):
        source = PACK["generatedFor"]
        self.assertRegex(source["masterCommit"], r"^[0-9a-f]{40}$")
        for field, suffix in (("sourceTree", "^{tree}"), ("migrationTree", ":chengyinhub-admin/sql")):
            actual = subprocess.run(["git", "rev-parse", source["masterCommit"] + suffix], cwd=ROOT,
                                    check=True, capture_output=True, text=True).stdout.strip()
            self.assertEqual(source[field], actual, field)
        dependency_bytes = subprocess.run(
            ["git", "show", source["masterCommit"] + ":ci/migration-dependencies.json"],
            cwd=ROOT, check=True, capture_output=True).stdout
        self.assertEqual(source["dependencySha256"], hashlib.sha256(dependency_bytes).hexdigest())
        self.assertTrue(PACK["baseline"]["productionAppliedState"].startswith("UNKNOWN"))

    def test_generated_document_matches_reviewed_pack(self):
        doc = (rehearsal.PACK_DIR / "release-migration-pack.md").read_text(encoding="utf-8")
        self.assertEqual(doc.count(rehearsal.GENERATED_BEGIN), 1)
        self.assertEqual(doc.count(rehearsal.GENERATED_END), 1)
        generated = doc.split(rehearsal.GENERATED_BEGIN)[1].split(rehearsal.GENERATED_END)[0]
        self.assertEqual(generated, "\n" + rehearsal.render_pack_inventory(PACK))

    def test_single_apply_policy_refuses_unsafe_skips(self):
        declared = {m["migrationId"] for m in PACK["migrations"] if m.get("rerunPolicy") == "PRECHECK_SKIP_APPLIED"}
        self.assertEqual(rehearsal.SINGLE_APPLY, declared)
        for single in (m for m in PACK["migrations"] if m["migrationId"] in rehearsal.SINGLE_APPLY):
            self.assertEqual("PRECHECK_SKIP_APPLIED", rehearsal.rerun_action(single, "APPLIED"))
            for status in (None, "NEEDED", "PARTIAL", "CONFLICT", "BLOCKED_PREREQ", "DATA_PREVIEW"):
                with self.subTest(migration=single["migrationId"], status=status), self.assertRaises(ValueError):
                    rehearsal.rerun_action(single, status)
            with self.assertRaises(ValueError):
                rehearsal.rerun_action({**single, "rerunPolicy": "EXECUTE"}, "APPLIED")
        for item in PACK["migrations"]:
            if item["migrationId"] not in rehearsal.SINGLE_APPLY:
                self.assertEqual("EXECUTE", rehearsal.rerun_action(item, None))
                with self.assertRaises(ValueError):
                    rehearsal.rerun_action({**item, "rerunPolicy": "PRECHECK_SKIP_APPLIED"}, "APPLIED")

    def test_direct_rerun_negative_control_requires_exact_failure_and_no_mutation(self):
        duplicate = [("probe", 2, "EXEC",
                      'OperationalError: (1060, "Duplicate column name \'welcome_note\'")', "ALTER TABLE club")]
        fingerprint = {"club": "unchanged"}
        self.assertTrue(rehearsal.direct_rerun_rejected_safely(False, duplicate, fingerprint, fingerprint))
        wrong_error = [("probe", 2, "EXEC", "OperationalError: (1146, table missing)", "ALTER TABLE club")]
        wrong_column = [("probe", 2, "EXEC", "1060 Duplicate column name 'other'", "ALTER TABLE club")]
        wrong_statement = [("probe", 2, "EXEC", duplicate[0][3], "SELECT 1")]
        for ok, problems, after in (
                (True, [], fingerprint), (False, [], fingerprint),
                (False, wrong_error, fingerprint), (False, wrong_column, fingerprint),
                (False, wrong_statement, fingerprint), (False, duplicate * 2, fingerprint),
                (False, duplicate, {"club": "changed"})):
            with self.subTest(ok=ok, problems=problems, after=after):
                self.assertFalse(rehearsal.direct_rerun_rejected_safely(ok, problems, fingerprint, after))

    def test_refresh_rejects_unreviewed_or_removed_contracts(self):
        computed = [{k: m[k] for k in ("migrationId", "path", "sha256", "baseSha256", "change", "order", "dependsOn")}
                    for m in PACK["migrations"]]
        refreshed = rehearsal.refreshed_items(PACK, computed)
        self.assertEqual(PACK["migrations"], refreshed)
        with self.assertRaises(ValueError):
            rehearsal.refreshed_items(PACK, computed[:-1])
        with self.assertRaises(ValueError):
            rehearsal.refreshed_items(PACK, computed + [{**computed[0], "migrationId": "unreviewed"}])
        duplicate_pack = copy.deepcopy(PACK)
        duplicate_pack["migrations"].append(duplicate_pack["migrations"][0])
        with self.assertRaises(ValueError):
            rehearsal.refreshed_items(duplicate_pack, computed)


if __name__ == "__main__":
    unittest.main()

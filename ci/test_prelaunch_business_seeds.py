"""Prove source bindings and the existing narrow runner contract, without a database."""
import ast
import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))
from run_migration import validate_transaction_statements, referenced_persistent_tables
from sql_splitter import split_statements


class MissingBusinessSeedsContract(unittest.TestCase):
    def setUp(self):
        self.provenance = json.loads((ROOT / "ci/prelaunch-seed-provenance.json").read_text())

    def test_source_hashes_and_real_controller_permissions(self):
        for path, sha in self.provenance["sourceFiles"].items():
            self.assertEqual(sha, hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), path)
        for row in self.provenance["menuRows"]:
            self.assertTrue((ROOT / "chengyinhub-ui/src/views" / (row["component"] + ".vue")).is_file())
            if row.get("controller"):
                matches = list((ROOT / "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller").rglob(row["controller"]))
                self.assertEqual(1, len(matches))
                self.assertIn("@ss.hasPermi('" + row["permission"] + "')", matches[0].read_text())
            else:
                # Pure UI/monitor pages retain their existing bootstrap permission string.
                self.assertIn(row["permission"], (ROOT / row["source"]).read_text())

    def test_every_current_index_view_has_an_existing_entry_or_explicit_canonical_target(self):
        coverage = self.provenance["viewCoverage"]
        actual = {path.relative_to(ROOT).as_posix() for path in (ROOT / "chengyinhub-ui/src/views").rglob("index.vue")}
        self.assertEqual(actual, {row["vue"] for row in coverage})
        self.assertEqual(len(actual), len(coverage))
        menus = {row["component"]: row for row in self.provenance["menuRows"]}
        for row in coverage:
            if row["state"] == "CANONICAL_C_FORWARD_REGISTERED":
                self.assertIn(row["proof"]["component"], menus)
            elif row["state"] == "ALIAS_BACKED_BY_CANONICAL":
                proof = row["proof"]
                self.assertEqual(proof["sha256"], hashlib.sha256((ROOT / row["vue"]).read_bytes()).hexdigest())
                self.assertTrue(proof["canonicalTargets"])
                for target in proof["canonicalTargets"]:
                    self.assertEqual(target["permission"], menus[target["component"]]["permission"])
                for path, sha in proof.get("wrapperSources", {}).items():
                    self.assertEqual(sha, hashlib.sha256((ROOT / path).read_bytes()).hexdigest())
                    self.assertIn("@/views/business/contentsearch/index.vue", (ROOT / path).read_text())
                if proof["mechanism"] == "EXISTING_REDIRECT":
                    self.assertIn("this.$router.replace", (ROOT / row["vue"]).read_text())
            else:
                self.assertEqual("STATIC_ALREADY_DEFINED", row["state"])
                self.assertTrue(row["proof"]["route"])

    def test_role_conflict_report_is_only_read_queries_and_has_no_personal_fields(self):
        statements = split_statements((ROOT / "chengyinhub-admin/sql/diag_canonical_role_conflicts_20261012.sql").read_text())
        self.assertEqual(3, len(statements))
        for statement in statements:
            self.assertTrue(statement.lstrip().upper().startswith("SELECT "))
            for field in ("phone", "password", "openid", "address", "nickname"):
                self.assertNotIn(field, statement.lower())

    def test_media_review_permissions_are_actual_reused_workbench_controls(self):
        controller = (ROOT / "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/MediaReviewController.java").read_text()
        sql = (ROOT / "chengyinhub-admin/sql/migration_20261012_media_review_menu.sql").read_text()
        for action in ("query", "approve", "reject"):
            permission = "business:mediaReview:" + action
            self.assertIn("@ss.hasPermi('" + permission + "')", controller)
            self.assertIn(permission, sql)
        self.assertIn("business:audit:list", sql)
        self.assertNotIn("UPDATE sys_menu", sql)
        self.assertNotIn("sys_role_menu", sql)

    def test_role_rows_use_existing_final_source_values(self):
        tree = ast.parse((ROOT / "scripts/e2e/seed_fixtures.py").read_text())
        tuples = next(ast.literal_eval(node.value) for node in tree.body
                      if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name)
                      and target.id == "DEFAULT_ROLE_PERMISSIONS" for target in node.targets))
        for actual, expected in zip(self.provenance["roleRows"], tuples):
            self.assertEqual(list(actual.values()), list(expected))
        player = self.provenance["roleRows"][0]
        self.assertEqual(1, player["withdrawable"])
        final_rule = (ROOT / "chengyinhub-admin/sql/migration_20260808_player_withdrawable.sql").read_text()
        self.assertIn("UPDATE user_permission SET withdrawable = 1 WHERE role = 'player'", final_rule)

    def test_both_forwards_fit_unchanged_transaction_allowlist_and_only_write_owned_tables(self):
        for name, table in (("missing_business_menu", "sys_menu"),
                            ("missing_role_permission_defaults", "user_permission"),
                            ("media_review_menu", "sys_menu")):
            sql = (ROOT / ("chengyinhub-admin/sql/migration_20261012_" + name + ".sql")).read_text()
            statements = split_statements(sql)
            validate_transaction_statements(statements)
            self.assertEqual({table}, set(referenced_persistent_tables(statements, "scratch")))
            self.assertIn("PRIMARY KEY", sql)
            self.assertIn("WHERE NOT EXISTS", sql)
            self.assertNotIn("UPDATE sys_menu", sql)
            self.assertNotIn("UPDATE user_permission", sql)
            self.assertNotIn("INSERT INTO sys_role_menu", sql)
            self.assertNotIn("INSERT INTO sys_user_role", sql)
            self.assertNotIn("LIMIT 1", sql)


if __name__ == "__main__":
    unittest.main()

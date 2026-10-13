import json
import hashlib
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

from scripts.e2e import build_schema


class BuildSchemaMigrationSelectionTest(unittest.TestCase):
    def test_current_migration_fingerprint_keeps_source_and_executable_contract(self):
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text(encoding="utf-8"))
        changed = [item for item in metadata["migrations"]
                   if "currentSourceSha256" in item]
        self.assertEqual(1, len(changed))
        item = changed[0]
        self.assertTrue(item["reason"])
        shown = subprocess.run(
            ["git", "show", metadata["sourceCommit"] + ":" + item["path"]],
            cwd=build_schema.REPO, capture_output=True)
        original = (shown.stdout if shown.returncode == 0 else
                    (build_schema.REPO / "scripts/e2e/fixtures/baseline_menu_2050.sql.txt").read_bytes())
        current = (build_schema.REPO / item["path"]).read_bytes()
        self.assertEqual(item["sourceSha256"], hashlib.sha256(original).hexdigest())
        self.assertEqual(item["currentSourceSha256"], hashlib.sha256(current).hexdigest())
        def executable_contract(source):
            return [(build_schema.executable_statement(statement),
                     build_schema.statement_assertion(statement))
                    for statement in build_schema.split_statements(source.decode("utf-8"))]
        self.assertEqual(executable_contract(original), executable_contract(current))

    def test_public_provenance_rejects_unknown_commit_and_byte_drift(self):
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text())
        with self.assertRaisesRegex(RuntimeError, "commit"):
            build_schema.source_provenance_files("0" * 40)
        with mock.patch.object(pathlib.Path, "read_bytes", return_value=b"tampered"):
            with self.assertRaisesRegex(RuntimeError, "漂移"):
                build_schema.source_provenance_files(metadata["sourceCommit"])

    def test_scratch_schema_sql_rewrites_dump_definer(self):
        dumped_view = (
            "/*!50001 CREATE ALGORITHM=UNDEFINED */\n"
            "/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */\n"
            "/*!50001 VIEW `view_registration` AS select 1 AS `id` */"
        )

        rewritten = build_schema.scratch_schema_sql(dumped_view)

        self.assertNotIn("DEFINER=", rewritten)
        self.assertIn("/*!50013 SQL SECURITY INVOKER */", rewritten)
        self.assertIn("VIEW `view_registration`", rewritten)

    class SchemaCursor:
        def __init__(self):
            self.tables = set()

        def execute(self, sql):
            match = re.match(r"CREATE TABLE(?: IF NOT EXISTS)?\s+`?([a-z0-9_]+)`?",
                             sql, re.IGNORECASE)
            if not match:
                return
            table = match.group(1).lower()
            if table in self.tables:
                raise RuntimeError("Duplicate table %s" % table)
            self.tables.add(table)

        def fetchone(self):
            return (1,)

    def test_target_is_loopback_scratch_only(self):
        build_schema.validate_target(SimpleNamespace(host="127.0.0.1", schema="cy_e2e_ok"))
        for host, schema in (("db.example.com", "cy_e2e_ok"),
                             ("127.0.0.1", "chengyinhubdb")):
            with self.assertRaises(SystemExit):
                build_schema.validate_target(SimpleNamespace(host=host, schema=schema))

    def test_policy_preflight_runs_before_database_connection(self):
        argv = ["build_schema.py", "--schema", "cy_e2e_preflight"]
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(build_schema, "build_manifest", return_value={"migrations": []}), \
                mock.patch.object(build_schema, "migrations_after_baseline",
                                  side_effect=RuntimeError("tampered")), \
                mock.patch.object(build_schema, "connect") as connect:
            with self.assertRaisesRegex(RuntimeError, "tampered"):
                build_schema.main()
            connect.assert_not_called()

    def test_snapshot_migrations_are_skipped_but_new_migration_is_replayed(self):
        cursor = self.SchemaCursor()
        baseline_results = []
        self.assertTrue(build_schema.run_file(
            cursor, build_schema.BASELINE, "baseline", baseline_results, False))
        self.assertEqual([], baseline_results)

        old_path = "chengyinhub-admin/sql/migration_schema_history_20260724.sql"
        duplicate_results = []
        self.assertFalse(build_schema.run_file(
            cursor, build_schema.REPO / old_path, "old", duplicate_results, False))
        self.assertIn("Duplicate table cy_migration_history", duplicate_results[0][3])

        official_path = "chengyinhub-admin/sql/migration_20260821_template_official_flag.sql"
        baseline_text = build_schema.BASELINE.read_text(encoding="utf-8")
        official_text = (build_schema.REPO / official_path).read_text(encoding="utf-8")
        self.assertIn("`is_official` tinyint NOT NULL DEFAULT '0'", baseline_text)
        self.assertIn("ADD COLUMN is_official tinyint NOT NULL DEFAULT 0", official_text)

        post_path = "chengyinhub-admin/sql/migration_20260820_play_team_core.sql"
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text(encoding="utf-8"))
        manifest = build_schema.build_manifest(
            build_schema.REPO,
            target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json",
        )
        migrations = manifest["migrations"]
        post = {"migrationId": "20260820_play_team_core", "path": post_path}

        selected = build_schema.migrations_after_baseline(migrations)

        self.assertIn(post_path, {item["path"] for item in selected})
        replay_results = []
        self.assertTrue(build_schema.run_file(
            cursor, build_schema.REPO / post_path, "post", replay_results, False))
        self.assertEqual([], replay_results)
        self.assertTrue({"play_team", "play_team_member"} <= cursor.tables)

        metadata["migrations"][0]["sourceSha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as tmp:
            tampered = pathlib.Path(tmp) / "manifest.json"
            tampered.write_text(json.dumps(metadata), encoding="utf-8")
            with mock.patch.object(build_schema, "BASELINE_MANIFEST", tampered):
                with self.assertRaisesRegex(RuntimeError, "指纹不一致"):
                    build_schema.migrations_after_baseline(migrations)

    def test_schema_rebuild_skips_data_only_migrations_but_keeps_persistent_ddl(self):
        manifest = build_schema.build_manifest(
            build_schema.REPO,
            target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json",
        )

        selected = {
            item["path"] for item in build_schema.migrations_after_baseline(manifest["migrations"])
        }

        self.assertNotIn(
            "chengyinhub-admin/sql/migration_20260819_purge_prelaunch_dirty_content.sql",
            selected,
        )
        self.assertIn(
            "chengyinhub-admin/sql/migration_approval_governance_20260828.sql",
            selected,
        )

    def test_schema_rebuild_replays_columns_missing_from_baseline(self):
        manifest = build_schema.build_manifest(
            build_schema.REPO,
            target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json",
        )

        selected = {
            item["path"] for item in build_schema.migrations_after_baseline(manifest["migrations"])
        }

        self.assertIn(
            "chengyinhub-admin/sql/migration_merchant_onsite_consent_scope_20260815.sql",
            selected,
        )
        self.assertIn(
            "chengyinhub-admin/sql/migration_20260815_shop_day_organizer_actor.sql",
            selected,
        )
        self.assertIn(
            "chengyinhub-admin/sql/migration_shop_day_batch_capacity_20260815.sql",
            selected,
        )

    def test_snapshot_manifest_does_not_hide_schema_tables_absent_from_snapshot(self):
        with self.assertRaisesRegex(RuntimeError, "source commit 格式无效"):
            build_schema.source_migration_fingerprints("--not-a-commit")

        manifest = build_schema.build_manifest(
            build_schema.REPO,
            target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json",
        )

        selected = {
            item["path"] for item in build_schema.migrations_after_baseline(manifest["migrations"])
        }

        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        missing = set(policy["baselineMissingMigrations"])
        self.assertEqual(13, len(missing))
        self.assertTrue(missing <= selected)
        seed_text = build_schema.BASELINE_SEED.read_text(encoding="utf-8")
        self.assertIn("(1, '超级管理员', 'admin'", seed_text)
        self.assertIn("(103, '财务结算', 'cy_finance'", seed_text)
        self.assertIn("config_key = 'withdrawal.player-complaint-cooldown-hours') = 0", seed_text)

        policy["baselineSeed"]["sha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as tmp:
            tampered = pathlib.Path(tmp) / "policy.json"
            tampered.write_text(json.dumps(policy), encoding="utf-8")
            with mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", tampered):
                with self.assertRaisesRegex(RuntimeError, "baseline seed 指纹不一致"):
                    build_schema.migrations_after_baseline(manifest["migrations"])

        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        policy["baselineMissingMigrations"][next(iter(missing))] = "0" * 64
        with tempfile.TemporaryDirectory() as tmp:
            tampered = pathlib.Path(tmp) / "policy.json"
            tampered.write_text(json.dumps(policy), encoding="utf-8")
            with mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", tampered):
                with self.assertRaisesRegex(RuntimeError, "指纹不一致"):
                    build_schema.migrations_after_baseline(manifest["migrations"])

        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        policy["baselineMissingMigrations"].pop(next(iter(missing)))
        policy["sourceMigrationCount"] -= 1
        with tempfile.TemporaryDirectory() as tmp:
            tampered = pathlib.Path(tmp) / "policy.json"
            tampered.write_text(json.dumps(policy), encoding="utf-8")
            with mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", tampered):
                with self.assertRaisesRegex(RuntimeError, "source commit migration"):
                    build_schema.migrations_after_baseline(manifest["migrations"])

    def test_schema_mode_reports_data_assertion_without_hiding_schema_assertion(self):
        class FalseCursor:
            def execute(self, _sql):
                pass

            def fetchone(self):
                return (0,)

        with tempfile.TemporaryDirectory() as tmp:
            data_sql = pathlib.Path(tmp) / "data.sql"
            data_sql.write_text(
                "-- runner: assert=scalar-true\n"
                "SELECT COUNT(*)=1 FROM sys_menu;\n",
                encoding="utf-8",
            )
            schema_sql = pathlib.Path(tmp) / "schema.sql"
            schema_sql.write_text(
                "-- runner: assert=scalar-true\n"
                "SELECT COUNT(*)=1 FROM information_schema.columns;\n",
                encoding="utf-8",
            )
            mixed_sql = pathlib.Path(tmp) / "mixed.sql"
            mixed_sql.write_text(
                "-- runner: assert=scalar-true\n"
                "SELECT COUNT(*)=1 FROM information_schema.columns c "
                "JOIN sys_menu m ON m.menu_name=c.column_name;\n",
                encoding="utf-8",
            )
            failures = []
            observations = []

            self.assertTrue(build_schema.run_file(
                FalseCursor(), data_sql, "data", failures, False,
                schema_only=True, observations=observations))
            self.assertEqual([], failures)
            self.assertEqual("DATA_ASSERT", observations[0][2])

            self.assertFalse(build_schema.run_file(
                FalseCursor(), schema_sql, "schema", failures, False,
                schema_only=True, observations=observations))
            self.assertEqual("ASSERT", failures[0][2])

            mixed_failures = []
            self.assertFalse(build_schema.run_file(
                FalseCursor(), mixed_sql, "mixed", mixed_failures, False,
                schema_only=True, observations=observations))
            self.assertEqual("ASSERT", mixed_failures[0][2])


if __name__ == "__main__":
    unittest.main()

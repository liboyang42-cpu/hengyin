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
    def test_schema_entrypoint_runs_predeploy_schema_but_defers_postdeploy_jobs(self):
        candidate = [
            {"migrationId": "core", "path": "core.sql"},
            {"migrationId": "job", "path": "job.sql", "executionPhase": "POST_DEPLOY"},
        ]
        cursor = mock.Mock()
        cursor.fetchone.side_effect = [("8.0.45", "isolated-test"), (1,), (1,)]
        connection = mock.Mock()
        connection.cursor.return_value = cursor
        with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_phase"]), \
                mock.patch.object(build_schema, "build_manifest", return_value={"migrations": candidate}), \
                mock.patch.object(build_schema, "migrations_after_baseline", return_value=candidate), \
                mock.patch.object(build_schema, "connect", return_value=connection), \
                mock.patch.object(build_schema, "run_file", return_value=True) as run, \
                mock.patch.object(build_schema, "verify_seed_negative_controls", return_value=6), \
                mock.patch("builtins.print") as emitted:
            self.assertEqual(0, build_schema.main())
            emitted.assert_any_call("DEFERRED_POST_DEPLOY: job")
            self.assertEqual(["baseline", "baseline-seed", "core"], [call.args[2] for call in run.call_args_list])

    def test_current_migration_fingerprint_keeps_source_and_executable_contract(self):
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text(encoding="utf-8"))
        changed = [item for item in metadata["migrations"]
                   if "currentSourceSha256" in item]
        self.assertEqual(2, len(changed))
        def executable_contract(source):
            return [(build_schema.executable_statement(statement),
                     build_schema.statement_assertion(statement))
                    for statement in build_schema.split_statements(source.decode("utf-8"))]
        for item in changed:
            with self.subTest(path=item["path"]):
                self.assertTrue(item["reason"])
                current = (build_schema.REPO / item["path"]).read_bytes()
                self.assertEqual(item["currentSourceSha256"], hashlib.sha256(current).hexdigest())
                if "executableContractSha256" in item:
                    # 原始字节含生产地址、不在公开快照里保留:钉的是原文件的可执行语句+断言。
                    contract = json.dumps(executable_contract(current), ensure_ascii=False)
                    self.assertEqual(item["executableContractSha256"],
                                     hashlib.sha256(contract.encode("utf-8")).hexdigest())
                    continue
                original = build_schema.source_file_bytes(metadata["sourceCommit"], item["path"])
                self.assertEqual(item["sourceSha256"], hashlib.sha256(original).hexdigest())
                self.assertEqual(executable_contract(original), executable_contract(current))

    def test_public_provenance_rejects_unknown_commit_and_byte_drift(self):
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text())
        with self.assertRaisesRegex(RuntimeError, "commit"):
            build_schema.source_provenance_files("0" * 40)
        with mock.patch.object(pathlib.Path, "read_bytes", return_value=b"tampered"):
            with self.assertRaisesRegex(RuntimeError, "漂移"):
                build_schema.source_provenance_files(metadata["sourceCommit"])


    def test_public_source_bytes_reject_drift_and_unretained_paths_without_private_history(self):
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text())
        source = "chengyinhub-admin/sql/migration_menu_2050_coupon_to_supply_20260726.sql"
        unavailable = SimpleNamespace(returncode=128, stdout=b"")
        with mock.patch.object(build_schema.subprocess, "run", return_value=unavailable):
            original = build_schema.source_file_bytes(metadata["sourceCommit"], source)
            self.assertEqual(build_schema.source_provenance_files(metadata["sourceCommit"])[source],
                             hashlib.sha256(original).hexdigest())
            with self.assertRaisesRegex(RuntimeError, "不可读"):
                build_schema.source_file_bytes(metadata["sourceCommit"], "unretained.sql")
            read_bytes = pathlib.Path.read_bytes
            def read_with_drift(path):
                return b"tampered" if path.name == "baseline_menu_2050.sql.txt" else read_bytes(path)
            with mock.patch.object(pathlib.Path, "read_bytes", read_with_drift):
                with self.assertRaisesRegex(RuntimeError, "指纹不一致"):
                    build_schema.source_file_bytes(metadata["sourceCommit"], source)

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
        self.assertIn("chengyinhub-admin/sql/migration_20260815_tandianri_roam_referral.sql",
                      {item["path"] for item in selected})
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
        # 临时表只服务七款种子的分类回填；空结构不代表该数据迁移已通过。
        self.assertNotIn(
            "chengyinhub-admin/sql/migration_20261007_legacy_game_v51_categories.sql",
            selected,
        )
        self.assertIn(
            "chengyinhub-admin/sql/migration_approval_governance_20260828.sql",
            selected,
        )

    def test_data_only_policy_rejects_stale_a8_fingerprint(self):
        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        path = "chengyinhub-admin/sql/migration_seed_merchant_game_a8_20260823.sql"
        self.assertEqual("fc70b86b22e32d7ac52696954f73293a7b696aca9298c1b88cec5957a8fdb661",
                         policy["dataOnlyMigrations"][path])
        policy["dataOnlyMigrations"][path] = "254524a804cb8ecd6727ff6514ec7294129a68bf909d523d14bc04414560816a"
        with tempfile.TemporaryDirectory() as tmp:
            stale = pathlib.Path(tmp) / "policy.json"
            stale.write_text(json.dumps(policy), encoding="utf-8")
            with mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", stale):
                with self.assertRaisesRegex(RuntimeError, "data-only 指纹不一致"):
                    build_schema.migrations_after_baseline(build_schema.build_manifest(
                        build_schema.REPO, target_commit="a" * 40,
                        dependency_path=build_schema.REPO / "ci/migration-dependencies.json")["migrations"])

    def test_category_collation_forward_replays_without_changing_historical_fingerprint(self):
        historical = "chengyinhub-admin/sql/migration_20261007_legacy_game_v51_categories.sql"
        forward = "chengyinhub-admin/sql/migration_20261012_legacy_game_v51_category_collation.sql"
        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        self.assertEqual(policy["dataOnlyMigrations"][historical],
                         build_schema.hashlib.sha256((build_schema.REPO / historical).read_bytes()).hexdigest())
        selected = {item["path"] for item in build_schema.migrations_after_baseline(
            build_schema.build_manifest(build_schema.REPO, target_commit="a" * 40,
                dependency_path=build_schema.REPO / "ci/migration-dependencies.json")["migrations"])}
        self.assertIn(forward, selected)
        self.assertNotIn(historical, selected)
        self.assertIn("COLLATE utf8mb4_general_ci", (build_schema.REPO / forward).read_text())

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
        self.assertEqual(14, len(missing))
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


class BuildSchemaExecutionFailureTest(unittest.TestCase):
    def run_failed_migration(self, keep_going):
        cursor = mock.Mock()
        cursor.fetchone.side_effect = [("8.0.45", "isolated-test"), (1,), (1,)]
        executed = []
        def execute(sql, *args):
            executed.append(sql)
            if "first_failure" in sql:
                raise RuntimeError("controlled first migration failure")
        cursor.execute.side_effect = execute
        admin = mock.Mock()
        connection = mock.Mock()
        admin.cursor.return_value = connection.cursor.return_value = cursor
        original_run = build_schema.run_file
        with tempfile.TemporaryDirectory() as tmp:
            first = pathlib.Path(tmp) / "first.sql"
            second = pathlib.Path(tmp) / "second.sql"
            first.write_text("SELECT 1 AS first_failure;\n", encoding="utf-8")
            second.write_text("SELECT 1 AS second_success;\n", encoding="utf-8")
            candidate = [{"migrationId": "first", "path": str(first)},
                         {"migrationId": "second", "path": str(second)}]
            def run_file(cur, path, label, results, *args, **kwargs):
                return True if label in {"baseline", "baseline-seed"} else original_run(
                    cur, path, label, results, *args, **kwargs)
            argv = ["build_schema.py", "--schema", "cy_e2e_failure"]
            if keep_going:
                argv.append("--keep-going")
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(build_schema, "build_manifest", return_value={"migrations": candidate}), \
                    mock.patch.object(build_schema, "migrations_after_baseline", return_value=candidate), \
                    mock.patch.object(build_schema, "connect", side_effect=[admin, connection]), \
                    mock.patch.object(build_schema, "run_file", side_effect=run_file), \
                    mock.patch.object(build_schema, "verify_seed_negative_controls", return_value=6), \
                    mock.patch("builtins.print") as emitted:
                result = build_schema.main()
            self.assertTrue(any("controlled first migration failure" in str(call)
                                for call in emitted.call_args_list))
        return result, executed, admin, connection

    def test_default_stops_before_second_migration_and_closes_connections(self):
        result, executed, admin, connection = self.run_failed_migration(False)
        self.assertEqual(1, result)
        self.assertTrue(any("first_failure" in sql for sql in executed))
        self.assertFalse(any("second_success" in sql for sql in executed))
        admin.close.assert_called_once_with()
        connection.close.assert_called_once_with()

    def test_explicit_keep_going_runs_second_but_stays_failed_and_closes(self):
        result, executed, admin, connection = self.run_failed_migration(True)
        self.assertEqual(1, result)
        self.assertTrue(any("second_success" in sql for sql in executed))
        admin.close.assert_called_once_with()
        connection.close.assert_called_once_with()

    def test_migration_read_exception_closes_connection(self):
        connection = mock.Mock()
        connection.cursor.return_value.fetchone.side_effect = [("8.0.45", "isolated-test")]
        with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_failure"]), \
                mock.patch.object(build_schema, "build_manifest", return_value={"migrations": []}), \
                mock.patch.object(build_schema, "migrations_after_baseline", return_value=[]), \
                mock.patch.object(build_schema, "connect", return_value=connection), \
                mock.patch.object(build_schema, "run_file", side_effect=OSError("unreadable SQL")), \
                mock.patch("builtins.print"):
            with self.assertRaisesRegex(OSError, "unreadable SQL"):
                build_schema.main()
        self.assertEqual(2, connection.close.call_count)


if __name__ == "__main__":
    unittest.main()

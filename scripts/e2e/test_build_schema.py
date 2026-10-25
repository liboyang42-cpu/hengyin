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
            self.connection = mock.Mock()

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

    def test_circle_business_seed_and_repair_skip_but_their_persistent_schema_replays(self):
        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        paths = {
            "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_20260821.sql":
                "44c1e8d44b59b7e7f6fb921bfdbd15bbcce59927857325a8112d37f9457342a2",
            "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_repair_20260821.sql":
                "68870b97f45e8da01e01dafe261efa73aeeef25f75918ab5d9fdceb9cc29836f",
        }
        for path, fingerprint in paths.items():
            with self.subTest(path=path):
                source = (build_schema.REPO / path).read_bytes()
                self.assertEqual(fingerprint, policy["dataOnlyMigrations"][path])
                self.assertEqual(fingerprint, build_schema.hashlib.sha256(source).hexdigest())
                # Existing preflight permits DML/temporary helpers and rejects persistent DDL.
                build_schema.validate_transaction_statements(build_schema.split_statements(source.decode("utf-8")))
        selected = {item["path"] for item in build_schema.migrations_after_baseline(
            build_schema.build_manifest(build_schema.REPO, target_commit="a" * 40,
                dependency_path=build_schema.REPO / "ci/migration-dependencies.json")["migrations"])}
        self.assertTrue(set(paths).isdisjoint(selected))
        schema = "chengyinhub-admin/sql/migration_circle_theme_batch2_20260821.sql"
        self.assertNotIn(schema, policy["dataOnlyMigrations"])
        self.assertIn(schema, selected)

    def test_data_only_policy_rejects_either_circle_seed_fingerprint_drift(self):
        manifest = build_schema.build_manifest(build_schema.REPO, target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json")
        for path in ("chengyinhub-admin/sql/migration_seed_circle_theme_batch2_20260821.sql",
                     "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_repair_20260821.sql"):
            with self.subTest(path=path), tempfile.TemporaryDirectory() as tmp:
                policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
                policy["dataOnlyMigrations"][path] = "0" * 64
                stale = pathlib.Path(tmp) / "policy.json"
                stale.write_text(json.dumps(policy), encoding="utf-8")
                with mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", stale):
                    with self.assertRaisesRegex(RuntimeError, "data-only 指纹不一致"):
                        build_schema.migrations_after_baseline(manifest["migrations"])

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

    def test_content_seed_closure_is_excluded_but_its_persistent_schema_still_replays(self):
        names = [
            "seed_new_city_lifestyle_category_20260821", "seed_circle_theme_batch2_20260821",
            "seed_circle_theme_batch2_repair_20260821", "seed_circle_theme_primary_20260821",
            "seed_shanghai_solo_topics_20260822", "seed_celebrate_games_20260821",
            "seed_celebrate_topic_20260821", "seed_life_backup_categories_20260820",
            "seed_life_backup_games_20260820", "20260829_circle_theme_merge_free_explore",
            "fix_celebrate_copy_20260821",
        ]
        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        excluded = policy["dataOnlyMigrations"]
        for name in names:
            path = "chengyinhub-admin/sql/migration_" + name + ".sql"
            with self.subTest(path=path):
                self.assertIn(path, excluded, "content seed must not invent business rows in a zero-data rebuild")
                source = (build_schema.REPO / path).read_bytes()
                self.assertEqual(hashlib.sha256(source).hexdigest(), excluded[path])
                for statement in build_schema.split_statements(source.decode("utf-8")):
                    sql = build_schema.executable_statement(statement).strip()
                    if sql:
                        self.assertRegex(sql, r"(?is)^(?:INSERT|UPDATE|SELECT|CREATE TEMPORARY TABLE|DROP TEMPORARY TABLE)\b",
                                         "do not classify persistent or dynamic DDL as data-only")
        selected = {item["path"] for item in build_schema.migrations_after_baseline(
            build_schema.build_manifest(build_schema.REPO, target_commit="a" * 40,
                dependency_path=build_schema.REPO / "ci/migration-dependencies.json")["migrations"])}
        for name in names:
            self.assertNotIn("chengyinhub-admin/sql/migration_" + name + ".sql", selected)
        for name in ["circle_theme_batch2_20260821", "circle_theme_primary_schema_20260821",
                     "20260829_celebration_board_decommission", "20260820_new_city_setup_plan",
                     "20260821_new_city_preference_library", "20261015_retention_job_missing_registration",
                     "20261012_legacy_game_v51_category_collation"]:
            self.assertIn("chengyinhub-admin/sql/migration_" + name + ".sql", selected,
                          "structural or explicitly approved forward migrations must still run")

    def test_content_seed_exclusion_still_rejects_changed_sql_fingerprint(self):
        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        path = "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_20260821.sql"
        policy["dataOnlyMigrations"][path] = "0" * 64
        manifest = build_schema.build_manifest(build_schema.REPO, target_commit="a" * 40,
                                              dependency_path=build_schema.REPO / "ci/migration-dependencies.json")
        with tempfile.TemporaryDirectory() as tmp:
            tampered = pathlib.Path(tmp) / "policy.json"
            tampered.write_text(json.dumps(policy), encoding="utf-8")
            with mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", tampered):
                with self.assertRaisesRegex(RuntimeError, "data-only 指纹不一致"):
                    build_schema.migrations_after_baseline(manifest["migrations"])

    def test_retention_job_registration_forward_replays_without_rewriting_the_included_seed(self):
        historical = "chengyinhub-admin/sql/migration_data_retention_job.sql"
        forward = "chengyinhub-admin/sql/migration_20261015_retention_job_missing_registration.sql"
        manifest = build_schema.build_manifest(
            build_schema.REPO, target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json")
        selected = build_schema.migrations_after_baseline(manifest["migrations"])
        selected_by_path = {item["path"]: item for item in selected}
        self.assertIn(forward, selected_by_path, "fresh rebuild must register the missing paused retention job")
        self.assertNotIn(historical, selected_by_path)
        self.assertNotEqual("POST_DEPLOY", selected_by_path[forward].get("executionPhase"))
        self.assertEqual("e263fb95eb2f83ebc29429aabac9da3db39c16a3b20c589ba8711c6af4514a60",
                         hashlib.sha256((build_schema.REPO / historical).read_bytes()).hexdigest())
        dependencies = json.loads((build_schema.REPO / "ci/migration-dependencies.json").read_text())["dependencies"]
        self.assertIn("data_retention_job", dependencies["20261015_retention_job_missing_registration"])

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
            connection = mock.Mock()

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


class BuildSchemaFileBoundaryTest(unittest.TestCase):
    class Cursor:
        def __init__(self):
            self.connection = mock.Mock()
            self.connection.begin.side_effect = self.begin
            self.connection.commit.side_effect = self.commit
            self.connection.rollback.side_effect = self.rollback
            self.isolation = "REPEATABLE-READ"
            self.active = False
            self.pending = []
            self.persisted = []
            self.executed = []
            self.observed_isolations = []
            self.rowcount = 1

        def begin(self):
            self.active = True

        def commit(self):
            self.persisted.extend(self.pending)
            self.pending.clear()
            self.active = False

        def rollback(self):
            self.pending.clear()
            self.active = False

        def execute(self, sql):
            self.executed.append(sql)
            if "TRANSACTION ISOLATION LEVEL READ COMMITTED" in sql:
                self.isolation = "READ-COMMITTED"
            elif "TRANSACTION ISOLATION LEVEL REPEATABLE READ" in sql:
                self.isolation = "REPEATABLE-READ"
            elif "fail_step" in sql:
                raise RuntimeError("controlled SQL failure")
            elif sql.startswith("SELECT"):
                self.observed_isolations.append(self.isolation)
            elif sql.startswith(("INSERT", "UPDATE", "CREATE")):
                (self.pending if self.active else self.persisted).append(sql)
            self.rowcount = 0 if "zero_rows" in sql else 1

        def fetchone(self):
            return (1,)

    def replay(self, cursor, source, keep_going=False, failures=None):
        if failures is None:
            failures = []
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "migration.sql"
            path.write_text(source, encoding="utf-8")
            result = build_schema.run_file(cursor, path, "fixture", failures, keep_going)
        return result, failures

    def test_explicit_rc_runs_as_written_but_next_file_starts_in_rr(self):
        cursor = self.Cursor()
        self.assertEqual((True, []), self.replay(cursor,
            "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED; SELECT 1;"))
        self.assertEqual("REPEATABLE-READ", cursor.isolation)
        self.assertEqual((True, []), self.replay(cursor, "SELECT 1;"))
        self.assertEqual(["READ-COMMITTED", "REPEATABLE-READ"], cursor.observed_isolations)

    def test_required_transaction_failure_stops_inside_file_and_rolls_back_with_keep_going(self):
        cursor = self.Cursor()
        result, failures = self.replay(cursor, "-- runner: requires=transaction\n"
            "INSERT INTO fixture VALUES (1); UPDATE fixture SET n=fail_step WHERE id=1;"
            "INSERT INTO fixture VALUES (2);", keep_going=True)
        self.assertFalse(result)
        self.assertEqual("EXEC", failures[0][2])
        self.assertEqual([], cursor.persisted)
        self.assertEqual([], cursor.pending)
        self.assertFalse(any("VALUES (2)" in sql for sql in cursor.executed))
        cursor.connection.begin.assert_called_once_with()
        cursor.connection.commit.assert_not_called()
        cursor.connection.rollback.assert_called_once_with()
        self.assertEqual([mock.call(False), mock.call(True)], cursor.connection.autocommit.call_args_list)
        self.assertEqual((True, failures), self.replay(cursor, "CREATE TABLE later(id INT);", failures=failures))
        self.assertEqual(1, len(failures), "continuing outside the file cannot erase its failure")

    def test_required_transaction_success_commits_all_writes_and_restores_session(self):
        cursor = self.Cursor()
        self.assertEqual((True, []), self.replay(cursor, "-- runner: requires=transaction\n"
            "INSERT INTO fixture VALUES (1); UPDATE fixture SET n=2 WHERE id=1;"))
        self.assertEqual(2, len(cursor.persisted))
        cursor.connection.begin.assert_called_once_with()
        cursor.connection.commit.assert_called_once_with()
        cursor.connection.rollback.assert_not_called()
        self.assertEqual(mock.call(True), cursor.connection.autocommit.call_args)
        self.assertEqual("REPEATABLE-READ", cursor.isolation)

    def test_required_transaction_assertion_failure_rolls_back_without_commit(self):
        cursor = self.Cursor()
        result, failures = self.replay(cursor, "-- runner: requires=transaction\n"
            "INSERT INTO fixture VALUES (1);\n-- runner: assert=rowcount-positive\n"
            "UPDATE fixture SET n=zero_rows WHERE id=1; INSERT INTO fixture VALUES (2);", keep_going=True)
        self.assertFalse(result)
        self.assertEqual("ASSERT", failures[0][2])
        self.assertEqual([], cursor.persisted)
        self.assertFalse(any("VALUES (2)" in sql for sql in cursor.executed))
        cursor.connection.commit.assert_not_called()
        cursor.connection.rollback.assert_called_once_with()

    def test_required_transaction_rejects_implicit_commit_ddl_before_execution(self):
        cursor = self.Cursor()
        result, failures = self.replay(cursor,
            "-- runner: requires=transaction\nCREATE TABLE forbidden(id INT);", keep_going=True)
        self.assertFalse(result)
        self.assertEqual("PREFLIGHT", failures[0][2])
        self.assertEqual([], cursor.executed)
        cursor.connection.begin.assert_not_called()

    def test_ordinary_ddl_is_not_wrapped_in_a_transaction(self):
        cursor = self.Cursor()
        self.assertEqual((True, []), self.replay(cursor, "CREATE TABLE ordinary(id INT);"))
        self.assertEqual(["CREATE TABLE ordinary(id INT)"], cursor.persisted)
        cursor.connection.begin.assert_not_called()
        cursor.connection.commit.assert_not_called()

    def test_commit_failure_aborts_even_with_keep_going_and_rolls_back(self):
        cursor = self.Cursor()
        cursor.connection.commit.side_effect = RuntimeError("commit unavailable")
        failures = []
        with self.assertRaisesRegex(RuntimeError, "commit unavailable"):
            self.replay(cursor, "-- runner: requires=transaction\nINSERT INTO fixture VALUES (1);",
                        keep_going=True, failures=failures)
        self.assertEqual("COMMIT_UNKNOWN", failures[0][2])
        self.assertEqual([], cursor.persisted)
        cursor.connection.rollback.assert_called_once_with()

    def test_malformed_data_assertion_readback_never_becomes_an_observation(self):
        for row in (None, (), (0, 1), (None,), (2,), ("0",)):
            with self.subTest(row=row), tempfile.TemporaryDirectory() as tmp:
                cursor = self.Cursor()
                cursor.fetchone = lambda: row
                path = pathlib.Path(tmp) / "data.sql"
                path.write_text("-- runner: assert=scalar-true\nSELECT COUNT(*)=1 FROM fixture;",
                                encoding="utf-8")
                failures, observations = [], []
                self.assertFalse(build_schema.run_file(cursor, path, "data", failures, False,
                                                       schema_only=True, observations=observations))
                self.assertEqual("ASSERT", failures[0][2])
                self.assertEqual([], observations)

    def test_data_assertion_fetch_failure_never_becomes_an_observation(self):
        cursor = self.Cursor()
        cursor.fetchone = mock.Mock(side_effect=RuntimeError("fetch unavailable"))
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "data.sql"
            path.write_text("-- runner: assert=scalar-true\nSELECT COUNT(*)=1 FROM fixture;",
                            encoding="utf-8")
            failures, observations = [], []
            self.assertFalse(build_schema.run_file(cursor, path, "data", failures, False,
                                                   schema_only=True, observations=observations))
            self.assertEqual("ASSERT", failures[0][2])
            self.assertEqual([], observations)

    def test_failed_rollback_aborts_instead_of_reusing_an_unknown_session(self):
        cursor = self.Cursor()
        cursor.connection.rollback.side_effect = RuntimeError("rollback unknown")
        with self.assertRaisesRegex(RuntimeError, "rollback unknown"):
            self.replay(cursor, "-- runner: requires=transaction\n"
                "UPDATE fixture SET n=fail_step WHERE id=1;", keep_going=True)
        self.assertNotIn(mock.call(True), cursor.connection.autocommit.call_args_list)


if __name__ == "__main__":
    unittest.main()


class AdminRolePayeeForwardContractTest(unittest.TestCase):
    ROOT = pathlib.Path(__file__).resolve().parents[2]
    OLD = "chengyinhub-admin/sql/migration_20261013_admin_role_page_grants.sql"
    NEW = "chengyinhub-admin/sql/migration_20261016_admin_role_payee_separation_forward.sql"

    def test_exact_legacy_role_initializer_is_excluded_but_atomic_replacement_is_selected(self):
        from ci.migration_ledger import build_manifest
        root = self.ROOT
        policy = json.loads((root / "scripts/e2e/schema_replay_policy_20260921.json").read_text())
        old_sha = "1f147a2136e999b6fd87a7e0a865d8fde5b0c332170d561b28e415b9fccaf56e"
        self.assertEqual(old_sha, hashlib.sha256((root / self.OLD).read_bytes()).hexdigest())
        self.assertIn(self.OLD, policy["dataOnlyMigrations"])
        self.assertEqual(old_sha, policy["dataOnlyMigrations"][self.OLD])
        self.assertNotIn(self.NEW, policy["dataOnlyMigrations"])
        with mock.patch.multiple(build_schema, REPO=root,
                BASELINE=root / "sql/base_schema_20260818.sql",
                BASELINE_SEED=root / "sql/base_seed_20260818.sql",
                BASELINE_MANIFEST=root / "scripts/e2e/base_schema_20260818_migrations.json",
                SCHEMA_REPLAY_POLICY=root / "scripts/e2e/schema_replay_policy_20260921.json"):
            migrations = build_manifest(root, "a" * 40)["migrations"]
            selected = {row["path"] for row in build_schema.migrations_after_baseline(migrations)}
        self.assertNotIn(self.OLD, selected)
        self.assertIn(self.NEW, selected)
        changed = dict(policy["dataOnlyMigrations"])
        changed[self.OLD] = "0" * 64
        policy["dataOnlyMigrations"] = changed
        with tempfile.TemporaryDirectory() as directory:
            bad_policy = pathlib.Path(directory) / "policy.json"
            bad_policy.write_text(json.dumps(policy))
            with mock.patch.multiple(build_schema, REPO=root,
                    BASELINE=root / "sql/base_schema_20260818.sql",
                    BASELINE_SEED=root / "sql/base_seed_20260818.sql",
                    BASELINE_MANIFEST=root / "scripts/e2e/base_schema_20260818_migrations.json",
                    SCHEMA_REPLAY_POLICY=bad_policy):
                with self.assertRaisesRegex(RuntimeError, "指纹不一致"):
                    build_schema.migrations_after_baseline(migrations)

    def test_existing_applied_history_stays_valid_when_only_the_new_forward_is_pending(self):
        from ci.migration_ledger import build_manifest, plan_release
        root = self.ROOT
        manifest = build_manifest(root, "a" * 40)
        history = [{"migrationId": row["migrationId"], "path": row["path"],
                    "checksum": row["sha256"], "targetCommit": "a" * 40,
                    "appliedAt": "2026-10-16T00:00:00Z", "status": "APPLIED"}
                   for row in manifest["migrations"] if row["path"] != self.NEW]
        plan = plan_release(manifest, history, bootstrap={"status": "RECONCILED"},
                            baseline_ids={row["migrationId"] for row in history})
        states = {row["path"]: row["state"] for row in plan["migrations"]}
        self.assertEqual("APPLIED", states[self.OLD])
        self.assertEqual("PENDING", states[self.NEW])
        self.assertFalse(any(state.startswith("BLOCKED") for state in states.values()))
        dependencies = json.loads((root / "ci/migration-dependencies.json").read_text())["dependencies"]
        self.assertEqual({"web_admin_roles_20260719", "20261012_missing_business_menu",
                          "20261012_media_review_menu", "role2_analytics_perm_20260918"},
                         set(dependencies["20261013_admin_role_page_grants"]))
        self.assertIn("20261013_admin_role_page_grants", dependencies["20261016_admin_role_payee_separation_forward"])
        self.assertIn("20260817_tandianri_batch3_funds_club_hours_correction",
                      dependencies["20261016_admin_role_payee_separation_forward"])

    def test_payee_forward_declares_atomic_and_only_the_two_approved_inverse_deletes(self):
        from ci.run_migration import required_transaction_declared
        sql = (self.ROOT / self.NEW).read_text()
        self.assertTrue(required_transaction_declared(sql))
        deletes = [build_schema.executable_statement(st).strip().rstrip(";")
                   for st in build_schema.split_statements(sql)
                   if build_schema.executable_statement(st).lstrip().upper().startswith("DELETE")]
        prefix = "DELETE FROM sys_role_menu WHERE role_id = (SELECT role_id FROM tmp_cy_payee_roles WHERE role_key = '{}'" + ") AND menu_id = (SELECT menu_id FROM tmp_cy_payee_menus WHERE perms = '{}')"
        self.assertEqual([prefix.format("cy_operator", "business:clubpayee:verify"),
                          prefix.format("cy_finance", "business:clubpayee:payout")], deletes)
        self.assertNotRegex(sql, r"(?im)^\s*(SET|CALL|PREPARE|EXECUTE|COMMIT|START|ALTER|TRUNCATE)\b")



class NoticeFreshForwardSelectionTest(unittest.TestCase):
    """Pure selector/routing controls; no MySQL success or application acceptance is claimed."""

    @classmethod
    def setUpClass(cls):
        cls.manifest = build_schema.build_manifest(build_schema.REPO, target_commit="a" * 40)
        cls.selected = build_schema.migrations_after_baseline(cls.manifest["migrations"])

    def copies(self):
        import copy
        return copy.deepcopy({"migrations": self.manifest["migrations"], "selected": self.selected})

    def assert_rejected_before_connect(self, rows):
        with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_notice", "--drop"]), \
                mock.patch.object(build_schema, "build_manifest", return_value=rows), \
                mock.patch.object(build_schema, "migrations_after_baseline", return_value=rows["selected"]), \
                mock.patch.object(build_schema, "connect") as connect, \
                mock.patch.object(build_schema, "run_file") as run:
            with self.assertRaisesRegex(RuntimeError, "notice forward"):
                build_schema.main()
            connect.assert_not_called()
            run.assert_not_called()

    def test_exact_canonical_pair_preserves_every_other_pending_item_and_phase(self):
        rows = self.copies()
        plan = build_schema.notice_forward_plan(rows["migrations"], rows["selected"])
        self.assertEqual([row for row in rows["selected"] if row["migrationId"] != build_schema.NOTICE_OLD],
                         plan["selected"])
        self.assertEqual(len(self.selected) - 1, len(plan["selected"]))
        self.assertEqual(self.manifest["migrations"], rows["migrations"])
        self.assertEqual(self.selected, rows["selected"])
        self.assertEqual({row["migrationId"] for row in self.selected if row.get("executionPhase") == "POST_DEPLOY"},
                         {row["migrationId"] for row in plan["selected"] if row.get("executionPhase") == "POST_DEPLOY"})
        self.assertEqual(set(build_schema.NOTICE_FORWARD_FILES), set(plan["fingerprints"]))

    def test_no_pair_does_not_add_notice_identity_or_freshness_requirements(self):
        candidate = [{"migrationId": "other", "path": "other.sql"}]
        self.assertIsNone(build_schema.notice_forward_plan(candidate, candidate))
        producer = next(row for row in self.selected if row["migrationId"] == build_schema.NOTICE_PRODUCER)
        self.assertIsNone(build_schema.notice_forward_plan([producer], [producer]))

    def test_missing_duplicate_or_nonpending_pair_fails_before_connect(self):
        for collection in ("migrations", "selected"):
            for key in build_schema.NOTICE_FORWARD_FILES:
                with self.subTest(collection=collection, missing=key):
                    rows = self.copies()
                    rows[collection] = [row for row in rows[collection] if row["migrationId"] != key]
                    self.assert_rejected_before_connect(rows)
        rows = self.copies()
        rows["migrations"].append(next(row for row in rows["migrations"]
                                       if row["migrationId"] == build_schema.NOTICE_OLD))
        self.assert_rejected_before_connect(rows)
        rows = self.copies()
        rows["selected"] = [dict(row, sha256="0" * 64) if row["migrationId"] == build_schema.NOTICE_V2 else row
                            for row in rows["selected"]]
        self.assert_rejected_before_connect(rows)

    def test_any_manifest_identity_hash_or_phase_drift_fails_before_connect(self):
        for key in build_schema.NOTICE_FORWARD_FILES:
            for field, value in (("migrationId", "wrong"), ("path", "wrong.sql"),
                                 ("sha256", "0" * 64), ("executionPhase", "POST_DEPLOY")):
                with self.subTest(migration=key, field=field):
                    rows = self.copies()
                    next(row for row in rows["migrations"] if row["migrationId"] == key)[field] = value
                    self.assert_rejected_before_connect(rows)

    def test_any_original_or_forward_file_byte_drift_fails_before_connect(self):
        read_bytes = pathlib.Path.read_bytes
        for path, _ in build_schema.NOTICE_FORWARD_FILES.values():
            with self.subTest(path=path):
                target = build_schema.REPO / path
                def drift(file):
                    value = read_bytes(file)
                    return value + b"\n" if file == target else value
                with mock.patch.object(pathlib.Path, "read_bytes", drift):
                    self.assert_rejected_before_connect(self.copies())

    def test_producer_dependency_global_order_or_pending_order_drift_fails_before_connect(self):
        for key in build_schema.NOTICE_FORWARD_FILES:
            with self.subTest(dependency=key):
                rows = self.copies()
                next(row for row in rows["migrations"] if row["migrationId"] == key)["dependsOn"] = []
                self.assert_rejected_before_connect(rows)
        rows = self.copies()
        rows["migrations"] = [row for row in rows["migrations"]
                              if row["migrationId"] != "20261013_b_im_notification_intent"]
        self.assert_rejected_before_connect(rows)
        for collection in ("migrations", "selected"):
            with self.subTest(order=collection):
                rows = self.copies()
                indices = [next(i for i, row in enumerate(rows[collection]) if row["migrationId"] == key)
                           for key in (build_schema.NOTICE_PRODUCER, build_schema.NOTICE_OLD)]
                a, b = indices
                rows[collection][a], rows[collection][b] = rows[collection][b], rows[collection][a]
                if collection == "migrations":
                    for i, row in enumerate(rows[collection], 1):
                        row["order"] = i
                self.assert_rejected_before_connect(rows)
        rows = self.copies()
        next(row for row in rows["migrations"] if row["migrationId"] == build_schema.NOTICE_V2)["order"] += 1
        self.assert_rejected_before_connect(rows)

    def test_existing_including_typed_or_unknown_schema_rejects_even_drop_before_mutation(self):
        # A schema count of one covers ordinary, typed and unknown existing schemas alike;
        # no DROP/CREATE/table probe/import is allowed to reinterpret them as fresh.
        for row in ((1,), None, (None,), (), (0, 1), (-1,), ("0",), (False,)):
            for flags in ([], ["--drop"]):
                with self.subTest(schema_readback=row, flags=flags):
                    admin = mock.Mock(); cursor = admin.cursor.return_value
                    cursor.fetchone.side_effect = [("8.0.45", "isolated-test"), row]
                    with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_notice"] + flags), \
                            mock.patch.object(build_schema, "build_manifest", return_value=self.manifest), \
                            mock.patch.object(build_schema, "migrations_after_baseline", return_value=self.selected), \
                            mock.patch.object(build_schema, "connect", return_value=admin) as connect, \
                            mock.patch.object(build_schema, "run_file") as run:
                        with self.assertRaisesRegex(RuntimeError, "nonexistent fresh scratch"):
                            build_schema.main()
                    self.assertEqual(1, connect.call_count); run.assert_not_called()
                    admin.close.assert_called_once_with()
                    self.assertEqual(["SELECT VERSION(), @@version_comment",
                                      "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s"],
                                     [call.args[0] for call in cursor.execute.call_args_list])

    def test_concurrent_create_is_not_adopted_or_imported(self):
        admin = mock.Mock(); cursor = admin.cursor.return_value
        cursor.fetchone.side_effect = [("8.0.45", "isolated-test"), (0,)]
        def execute(sql, *args):
            if sql.startswith("CREATE DATABASE"):
                raise RuntimeError("controlled concurrent schema creation")
        cursor.execute.side_effect = execute
        with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_notice", "--drop"]), \
                mock.patch.object(build_schema, "build_manifest", return_value=self.manifest), \
                mock.patch.object(build_schema, "migrations_after_baseline", return_value=self.selected), \
                mock.patch.object(build_schema, "connect", return_value=admin) as connect, \
                mock.patch.object(build_schema, "run_file") as run, mock.patch("builtins.print") as emitted:
            with self.assertRaisesRegex(RuntimeError, "concurrent schema"):
                build_schema.main()
        self.assertEqual(1, connect.call_count); run.assert_not_called()
        admin.close.assert_called_once_with()
        self.assertEqual("CREATE DATABASE `cy_e2e_notice` DEFAULT CHARACTER SET utf8mb4",
                         cursor.execute.call_args_list[-1].args[0])
        self.assertFalse(any("REPLACED_UNAPPLIED" in str(call) for call in emitted.call_args_list))

    def test_fresh_route_runs_real_v2_gate_and_stops_before_ddl_even_keep_going(self):
        admin = mock.Mock(); admin.cursor.return_value.fetchone.side_effect = [("8.0.45", "isolated-test"), (0,)]
        connection = mock.Mock(); cursor = connection.cursor.return_value
        cursor.connection = connection; cursor.fetchone.side_effect = [(227,), (256,)]
        def execute(sql, *args):
            if "ASSERT_FAILED_before_start_schema_preflight" in sql:
                raise RuntimeError("controlled original strict v2 preflight failure")
        cursor.execute.side_effect = execute
        original_run = build_schema.run_file
        def route(cur, path, label, results, *args, **kwargs):
            # Other files are routing doubles only. The v2 gate is never mocked successful:
            # run its exact original SQL splitter/assertion runner and inject a real cursor failure.
            return original_run(cur, path, label, results, *args, **kwargs) if label == build_schema.NOTICE_V2 else True
        with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_notice", "--drop", "--keep-going"]), \
                mock.patch.object(build_schema, "build_manifest", return_value=self.manifest), \
                mock.patch.object(build_schema, "migrations_after_baseline", return_value=self.selected), \
                mock.patch.object(build_schema, "connect", side_effect=[admin, connection]), \
                mock.patch.object(build_schema, "run_file", side_effect=route) as run, \
                mock.patch.object(build_schema, "verify_seed_negative_controls", return_value=6), \
                mock.patch("builtins.print") as emitted:
            self.assertEqual(1, build_schema.main())
        ordered = [row for row in self.selected if row.get("executionPhase") != "POST_DEPLOY"
                   and row["migrationId"] != build_schema.NOTICE_OLD]
        ids = [row["migrationId"] for row in ordered]
        self.assertEqual(["baseline", "baseline-seed"] + ids[:ids.index(build_schema.NOTICE_V2) + 1],
                         [call.args[2] for call in run.call_args_list])
        self.assertFalse(run.call_args_list[-1].kwargs["keep_going"])
        self.assertFalse(any("ALTER TABLE" in call.args[0] for call in cursor.execute.call_args_list))
        self.assertFalse(any(call.args[0].startswith("DROP") for call in admin.cursor.return_value.execute.call_args_list))
        records = [str(call) for call in emitted.call_args_list if "REPLACED_UNAPPLIED" in str(call)]
        self.assertEqual(1, len(records))
        for _, digest in build_schema.NOTICE_FORWARD_FILES.values():
            self.assertIn(digest, records[0])
        self.assertTrue(any("controlled original strict v2 preflight failure" in str(call)
                            for call in emitted.call_args_list))
        admin.close.assert_called_once_with(); connection.close.assert_called_once_with()

if __name__ == "__main__":
    unittest.main()

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
        cursor.fetchone.side_effect = [("8.0.45", "isolated-test"), ("REPEATABLE-READ",),
                                       (1,), ("REPEATABLE-READ",), (1,)]
        connection = mock.Mock()
        connection.get_autocommit.return_value = True
        connection.server_status = 2
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
        self.assertEqual(1, len(changed))
        item = changed[0]
        self.assertTrue(item["reason"])
        original = build_schema.source_file_bytes(metadata["sourceCommit"], item["path"])
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

    def test_marked_file_begins_and_commits_after_engine_preflight(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "atomic.sql"
            path.write_text("-- runner: requires=transaction\nINSERT INTO sys_job(job_name) VALUES('one');\n", encoding="utf-8")
            cursor = mock.Mock()
            cursor.connection.get_autocommit.return_value = True
            cursor.connection.server_status = 2
            cursor.fetchone.return_value = ("cy_e2e_atomic",)
            with mock.patch.object(build_schema, "assert_innodb_references", create=True) as engine:
                self.assertTrue(build_schema.run_file(cursor, path, "atomic", [], False))
                engine.assert_called_once()
            cursor.connection.begin.assert_called_once()
            cursor.connection.commit.assert_called_once()
            cursor.connection.rollback.assert_not_called()

    def test_marked_file_sql_failure_rolls_back_and_ignores_keep_going(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "atomic.sql"
            path.write_text("-- runner: requires=transaction\nINSERT INTO sys_job(job_name) VALUES('one');\n"
                            "INSERT INTO sys_job(job_name) VALUES('bad');\n"
                            "INSERT INTO sys_job(job_name) VALUES('must_not_run');\n", encoding="utf-8")
            cursor = mock.Mock()
            cursor.connection.get_autocommit.return_value = True
            cursor.connection.server_status = 2
            cursor.fetchone.return_value = ("cy_e2e_atomic",)
            def execute(sql):
                if "'bad'" in sql:
                    raise RuntimeError("owned SQL failure")
            cursor.execute.side_effect = execute
            errors = []
            with mock.patch.object(build_schema, "assert_innodb_references", create=True):
                self.assertFalse(build_schema.run_file(cursor, path, "atomic", errors, True))
            cursor.connection.rollback.assert_called_once()
            cursor.connection.commit.assert_not_called()
            self.assertTrue(errors)
            self.assertFalse(any("must_not_run" in call.args[0] for call in cursor.execute.call_args_list))

    def test_marked_file_rowcount_assertion_failure_rolls_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "atomic.sql"
            path.write_text("-- runner: requires=transaction\n-- runner: assert=rowcount-positive\n"
                            "INSERT INTO sys_job(job_name) SELECT 'one' WHERE FALSE;\n", encoding="utf-8")
            cursor = mock.Mock()
            cursor.connection.get_autocommit.return_value = True
            cursor.connection.server_status = 2
            cursor.fetchone.return_value = ("cy_e2e_atomic",)
            cursor.rowcount = 0
            with mock.patch.object(build_schema, "assert_innodb_references", create=True):
                self.assertFalse(build_schema.run_file(cursor, path, "atomic", [], True))
            cursor.connection.rollback.assert_called_once()
            cursor.connection.commit.assert_not_called()

    def test_marked_file_unsupported_shape_is_rejected_before_touching_connection(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "atomic.sql"
            path.write_text("-- runner: requires=transaction\nSELECT 1;\n", encoding="utf-8")
            cursor = mock.Mock()
            with mock.patch.object(build_schema, "assert_innodb_references", create=True):
                self.assertFalse(build_schema.run_file(cursor, path, "atomic", [], True))
            cursor.execute.assert_not_called()
            cursor.connection.begin.assert_not_called()

    def test_marked_file_non_innodb_is_rejected_before_begin_or_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "atomic.sql"
            path.write_text("-- runner: requires=transaction\nINSERT INTO sys_job(job_name) VALUES('one');\n", encoding="utf-8")
            cursor = mock.Mock()
            cursor.connection.get_autocommit.return_value = True
            cursor.connection.server_status = 2
            cursor.fetchone.return_value = ("cy_e2e_atomic",)
            with mock.patch.object(build_schema, "assert_innodb_references", create=True,
                                   side_effect=ValueError("non-InnoDB fixture")):
                self.assertFalse(build_schema.run_file(cursor, path, "atomic", [], True))
            cursor.connection.begin.assert_not_called()
            self.assertFalse(any("INSERT" in call.args[0] for call in cursor.execute.call_args_list))

    def test_marked_file_does_not_commit_a_callers_existing_transaction(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "atomic.sql"
            path.write_text("-- runner: requires=transaction\nINSERT INTO sys_job(job_name) VALUES('one');\n", encoding="utf-8")
            cursor = mock.Mock()
            cursor.connection.get_autocommit.return_value = False
            with mock.patch.object(build_schema, "assert_innodb_references", create=True):
                self.assertFalse(build_schema.run_file(cursor, path, "atomic", [], True))
            cursor.connection.begin.assert_not_called()
            cursor.connection.commit.assert_not_called()
            cursor.connection.rollback.assert_not_called()

    def test_marked_file_does_not_commit_an_explicit_transaction_with_autocommit_true(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "atomic.sql"
            path.write_text("-- runner: requires=transaction\nINSERT INTO sys_job(job_name) VALUES('one');\n", encoding="utf-8")
            cursor = mock.Mock()
            cursor.connection.get_autocommit.return_value = True
            cursor.connection.server_status = 3
            cursor.fetchone.return_value = ("cy_e2e_atomic",)
            with mock.patch.object(build_schema, "assert_innodb_references"):
                self.assertFalse(build_schema.run_file(cursor, path, "atomic", [], True))
            cursor.execute.assert_not_called()
            cursor.connection.begin.assert_not_called()
            cursor.connection.commit.assert_not_called()
            cursor.connection.rollback.assert_not_called()

    def test_unconfirmed_rollback_stops_keep_going_before_the_next_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            baseline = root / "baseline.sql"
            baseline.write_text("", encoding="utf-8")
            atomic = root / "atomic.sql"
            atomic.write_text("-- runner: requires=transaction\nINSERT INTO sys_job(job_name) VALUES('bad');\n", encoding="utf-8")
            later = root / "later.sql"
            later.write_text("INSERT INTO sys_job(job_name) VALUES('must_not_run');\n", encoding="utf-8")
            cursor = mock.Mock()
            connection = mock.Mock()
            cursor.connection = connection
            connection.cursor.return_value = cursor
            connection.get_autocommit.return_value = True
            connection.server_status = 2
            connection.rollback.side_effect = RuntimeError("owned rollback failure")
            last_sql = [""]
            def execute(sql, *args):
                last_sql[0] = sql
                if "'bad'" in sql:
                    raise RuntimeError("owned write failure")
            cursor.execute.side_effect = execute
            cursor.fetchone.side_effect = lambda: ("8.0.45", "isolated-test") if "VERSION()" in last_sql[0] else (
                ("REPEATABLE-READ",) if "@@SESSION.transaction_isolation" in last_sql[0] else (
                    ("cy_e2e_atomic",) if "DATABASE()" in last_sql[0] else (0,)))
            migrations = [{"migrationId": "atomic", "path": str(atomic)},
                          {"migrationId": "later", "path": str(later)}]
            with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_atomic", "--keep-going"]), \
                    mock.patch.object(build_schema, "build_manifest", return_value={"migrations": migrations}), \
                    mock.patch.object(build_schema, "migrations_after_baseline", return_value=migrations), \
                    mock.patch.object(build_schema, "connect", side_effect=[mock.Mock(cursor=mock.Mock(return_value=cursor)), connection]), \
                    mock.patch.object(build_schema, "BASELINE", baseline), \
                    mock.patch.object(build_schema, "BASELINE_SEED", baseline), \
                    mock.patch.object(build_schema, "assert_innodb_references"), \
                    mock.patch.object(build_schema, "verify_seed_negative_controls", return_value=6), \
                    mock.patch("builtins.print"):
                with self.assertRaisesRegex(RuntimeError, "rollback"):
                    build_schema.main()
            self.assertFalse(any("must_not_run" in call.args[0] for call in cursor.execute.call_args_list))
            connection.close.assert_called_once()

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
        executed = []
        cursor.fetchone.side_effect = lambda: ("8.0.45", "isolated-test") if "VERSION()" in executed[-1] else (
            ("REPEATABLE-READ",) if "@@SESSION.transaction_isolation" in executed[-1] else (1,))
        def execute(sql, *args):
            executed.append(sql)
            if "first_failure" in sql:
                raise RuntimeError("controlled first migration failure")
        cursor.execute.side_effect = execute
        admin = mock.Mock()
        connection = mock.Mock()
        connection.get_autocommit.return_value = True
        connection.server_status = 2
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
        connection.cursor.return_value.fetchone.side_effect = [("8.0.45", "isolated-test"), ("REPEATABLE-READ",)]
        with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_failure"]), \
                mock.patch.object(build_schema, "build_manifest", return_value={"migrations": []}), \
                mock.patch.object(build_schema, "migrations_after_baseline", return_value=[]), \
                mock.patch.object(build_schema, "connect", return_value=connection), \
                mock.patch.object(build_schema, "run_file", side_effect=OSError("unreadable SQL")), \
                mock.patch("builtins.print"):
            with self.assertRaisesRegex(OSError, "unreadable SQL"):
                build_schema.main()
        self.assertEqual(2, connection.close.call_count)


class BuildSchemaSessionIsolationTest(unittest.TestCase):
    class Connection:
        def __init__(self, isolation="REPEATABLE-READ", restore="ok"):
            self.isolation, self.restore = isolation, restore
            self.server_status, self.closed = 2, False
            self.last_sql, self.executed, self.observed = "", [], []
            self.restore_attempts = 0

        def cursor(self):
            return self

        def get_autocommit(self):
            return True

        def execute(self, sql, *args):
            self.last_sql = sql
            self.executed.append(sql)
            if sql.startswith("SET SESSION TRANSACTION ISOLATION LEVEL"):
                level = sql.split("ISOLATION LEVEL ", 1)[1].replace(" ", "-")
                if level != "READ-COMMITTED":
                    self.restore_attempts += 1
                    if self.restore == "raise":
                        raise RuntimeError("controlled SET failure")
                    if self.restore == "ignore":
                        return
                self.isolation = level
            elif "observe_inside_file" in sql or "observe_next_file" in sql:
                self.observed.append((sql, self.isolation))
            elif sql == "START TRANSACTION":
                self.server_status = 3
            elif "controlled_file_failure" in sql:
                raise RuntimeError("controlled SQL failure")

        def fetchone(self):
            if "@@SESSION.transaction_isolation" in self.last_sql:
                return (self.isolation,)
            if "VERSION()" in self.last_sql:
                return ("8.0.45", "isolated-test")
            return (0,)

        def close(self):
            self.closed = True

    def run_main(self, connection, first, *, keep_going=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            baseline = root / "baseline.sql"
            baseline.write_text("", encoding="utf-8")
            one, two = root / "one.sql", root / "two.sql"
            one.write_text(first, encoding="utf-8")
            two.write_text("SELECT 'observe_next_file';", encoding="utf-8")
            migrations = [{"migrationId": "one", "path": str(one)},
                          {"migrationId": "two", "path": str(two)}]
            argv = ["build_schema.py", "--schema", "cy_e2e_isolation"]
            if keep_going:
                argv.append("--keep-going")
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(build_schema, "build_manifest", return_value={"migrations": migrations}), \
                    mock.patch.object(build_schema, "migrations_after_baseline", return_value=migrations), \
                    mock.patch.object(build_schema, "connect", side_effect=[self.Connection(), connection]), \
                    mock.patch.object(build_schema, "BASELINE", baseline), \
                    mock.patch.object(build_schema, "BASELINE_SEED", baseline), \
                    mock.patch.object(build_schema, "verify_seed_negative_controls", return_value=6), \
                    mock.patch("builtins.print"):
                return build_schema.main()

    def test_file_uses_own_isolation_then_next_file_gets_initial_session_contract(self):
        for initial in ["REPEATABLE-READ", "SERIALIZABLE", "READ-UNCOMMITTED"]:
            with self.subTest(initial=initial):
                c = self.Connection(initial)
                self.assertEqual(0, self.run_main(c, "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;\n"
                                                "SELECT 'observe_inside_file';"))
                self.assertEqual(["READ-COMMITTED", initial], [level for _, level in c.observed])
                self.assertEqual(initial, c.isolation)
                self.assertTrue(c.closed)

    def test_failed_file_restores_even_when_keep_going_and_default_stops(self):
        for keep_going in [False, True]:
            with self.subTest(keep_going=keep_going):
                c = self.Connection()
                self.assertEqual(1, self.run_main(c, "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;\n"
                                                "SELECT 'controlled_file_failure';", keep_going=keep_going))
                self.assertEqual("REPEATABLE-READ", c.isolation)
                self.assertEqual(["REPEATABLE-READ"] if keep_going else [], [level for _, level in c.observed])
                self.assertTrue(c.closed)

    def test_restore_failure_and_ineffective_restore_are_fatal_with_keep_going(self):
        for restore in ["raise", "ignore"]:
            with self.subTest(restore=restore):
                c = self.Connection(restore=restore)
                with self.assertRaisesRegex(RuntimeError, "isolation"):
                    self.run_main(c, "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;", keep_going=True)
                self.assertEqual([], c.observed)
                self.assertTrue(c.closed)

    def test_unknown_transaction_state_stops_before_set_or_next_file(self):
        c = self.Connection()
        with self.assertRaisesRegex(RuntimeError, "idle"):
            self.run_main(c, "START TRANSACTION; SELECT 'controlled_file_failure';", keep_going=True)
        self.assertEqual(0, c.restore_attempts)
        self.assertEqual([], c.observed)
        self.assertTrue(c.closed)

    def test_initial_isolation_must_be_a_known_mysql_level_before_file_execution(self):
        c = self.Connection("REPEATABLE-READ; SELECT 'injected'")
        with self.assertRaisesRegex(RuntimeError, "isolation"):
            self.run_main(c, "SELECT 'observe_inside_file';", keep_going=True)
        self.assertEqual([], c.observed)
        self.assertEqual(0, c.restore_attempts)
        self.assertTrue(c.closed)

    def test_uncertain_file_exception_closes_without_attempting_set_or_masking_cause(self):
        c = self.Connection()
        original = build_schema.run_file
        def fail(cur, path, label, results, *args, **kwargs):
            if label == "one":
                raise RuntimeError("rollback could not be confirmed")
            return original(cur, path, label, results, *args, **kwargs)
        with mock.patch.object(build_schema, "run_file", side_effect=fail):
            with self.assertRaisesRegex(RuntimeError, "rollback could not be confirmed"):
                self.run_main(c, "SELECT 1;", keep_going=True)
        self.assertEqual(0, c.restore_attempts)
        self.assertEqual([], c.observed)
        self.assertTrue(c.closed)


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


if __name__ == "__main__":
    unittest.main()

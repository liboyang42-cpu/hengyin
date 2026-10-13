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
from ci import migration_ledger


class BuildSchemaMigrationSelectionTest(unittest.TestCase):
    def test_startup_fixture_is_classified_but_never_replayed(self):
        path = "ci/upgrade-startup/schema.sql"
        manifest = build_schema.build_manifest(
            build_schema.REPO, target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json")
        auxiliary = {item["path"]: item["classification"]
                     for item in manifest["inventory"]["auxiliarySql"]}
        self.assertEqual("TEST_FIXTURE", auxiliary[path])
        self.assertNotIn(path, {item["path"] for item in manifest["migrations"]})
        self.assertNotIn(path, {item["path"] for item in
                               build_schema.migrations_after_baseline(manifest["migrations"])})

    def test_startup_fixture_classification_does_not_allow_unknown_sql(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "chengyinhub-admin/sql").mkdir(parents=True)
            fixture = root / "ci/upgrade-startup/schema.sql"
            fixture.parent.mkdir(parents=True)
            fixture.write_bytes((build_schema.REPO / fixture.relative_to(root)).read_bytes())
            self.assertEqual([], migration_ledger.audit_inventory(root)["problems"])
            unknown = fixture.with_name("unreviewed.sql")
            unknown.write_text("CREATE TABLE unreviewed(id BIGINT);", encoding="utf-8")
            problems = migration_ledger.audit_inventory(root)["problems"]
            self.assertEqual([("UNMANAGED_SQL_LOCATION", "ci/upgrade-startup/unreviewed.sql")],
                             [(item["code"], item["path"]) for item in problems])
            unknown.unlink()
            classifications = dict(migration_ledger.EXPLICIT_AUXILIARY_SQL)
            del classifications["ci/upgrade-startup/schema.sql"]
            with mock.patch.object(migration_ledger, "EXPLICIT_AUXILIARY_SQL", classifications):
                problems = migration_ledger.audit_inventory(root)["problems"]
            self.assertEqual([("UNMANAGED_SQL_LOCATION", "ci/upgrade-startup/schema.sql")],
                             [(item["code"], item["path"]) for item in problems])

    def test_current_migration_fingerprint_keeps_source_and_executable_contract(self):
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text(encoding="utf-8"))
        source = build_schema.source_migration_fingerprints(metadata["sourceCommit"])
        for recorded in metadata["migrations"]:
            self.assertEqual(recorded["sourceSha256"], source[recorded["path"]])
        reviewed = [item for item in metadata["migrations"] if item.get("reason")]
        self.assertEqual(1, len(reviewed))
        item = reviewed[0]
        self.assertTrue(item["reason"])
        original = subprocess.check_output(
            ["git", "show", metadata["sourceCommit"] + ":" + item["path"]],
            cwd=build_schema.REPO)
        current = (build_schema.REPO / item["path"]).read_bytes()
        self.assertEqual(item["sourceSha256"], hashlib.sha256(original).hexdigest())
        self.assertEqual(item["sourceSha256"], hashlib.sha256(current).hexdigest())
        def executable_contract(source):
            return [(build_schema.executable_statement(statement),
                     build_schema.statement_assertion(statement))
                    for statement in build_schema.split_statements(source.decode("utf-8"))]
        self.assertEqual(executable_contract(original), executable_contract(current))

    def test_public_source_inventory_cannot_hide_or_reclassify_newer_migrations(self):
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text(encoding="utf-8"))
        policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
        manifest = build_schema.build_manifest(
            build_schema.REPO, target_commit="a" * 40,
            dependency_path=build_schema.REPO / "ci/migration-dependencies.json")
        self.assertEqual(2, policy["schemaVersion"])
        self.assertEqual(434, policy["sourceMigrationCount"])
        self.assertEqual(172, len(policy["sourceAdditionalMigrations"]))
        excluded = set(policy["dataOnlyMigrations"]) | {
            item["path"] for item in metadata["reconciledMigrations"]}
        replay = {item["path"] for item in policy["sourceAdditionalMigrations"]} - excluded
        self.assertEqual(146, len(replay))
        selected = {item["path"] for item in
                    build_schema.migrations_after_baseline(manifest["migrations"])}
        self.assertTrue(replay <= selected)
        path = sorted(replay)[0]
        for mutation in ("missing-source", "source-drift", "incorrectly-in-baseline",
                         "duplicate-path", "missing-path", "missing-source-sha"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                altered_policy = json.loads(json.dumps(policy))
                altered_metadata = json.loads(json.dumps(metadata))
                records = altered_policy["sourceAdditionalMigrations"]
                index = next(index for index, item in enumerate(records) if item["path"] == path)
                if mutation == "missing-source":
                    records.pop(index)
                    altered_policy["sourceMigrationCount"] -= 1
                elif mutation == "source-drift":
                    records[index]["sourceSha256"] = "0" * 64
                elif mutation == "incorrectly-in-baseline":
                    altered_metadata["migrations"].append(records.pop(index))
                    altered_metadata["migrationCount"] += 1
                    altered_policy["baselineManifestMigrationCount"] += 1
                elif mutation == "duplicate-path":
                    records.append(dict(records[index]))
                elif mutation == "missing-path":
                    del records[index]["path"]
                else:
                    del records[index]["sourceSha256"]
                policy_file = pathlib.Path(tmp) / "policy.json"
                metadata_file = pathlib.Path(tmp) / "manifest.json"
                policy_file.write_text(json.dumps(altered_policy), encoding="utf-8")
                metadata_file.write_text(json.dumps(altered_metadata), encoding="utf-8")
                if mutation in ("duplicate-path", "missing-path", "missing-source-sha"):
                    expected = "additional source 记录无效或路径重复"
                elif mutation == "incorrectly-in-baseline":
                    expected = "source classification 指纹不一致"
                else:
                    expected = "source commit migration 集合或指纹不一致"
                with mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", policy_file), \
                        mock.patch.object(build_schema, "BASELINE_MANIFEST", metadata_file):
                    with self.assertRaisesRegex(RuntimeError, expected):
                        build_schema.migrations_after_baseline(manifest["migrations"])

    def test_version_one_policy_still_reads_real_git_source(self):
        # A small independent v1 repository proves backward compatibility without
        # assuming that private historical objects exist in the public checkout.
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            path = "chengyinhub-admin/sql/migration_example.sql"
            source = root / path
            source.parent.mkdir(parents=True)
            source.write_text("CREATE TABLE example(id BIGINT PRIMARY KEY);", encoding="utf-8")
            baseline = root / "baseline.sql"
            baseline.write_bytes(source.read_bytes())
            seed = root / "seed.sql"
            seed.write_text("SELECT 1;", encoding="utf-8")
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, stderr=subprocess.PIPE)
            git("init", "--quiet")
            git("add", "--all")
            git("-c", "user.name=Schema fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Version one fixture")
            commit = git("rev-parse", "HEAD").decode().strip()
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            metadata = {
                "schemaVersion": 1, "sourceCommit": commit,
                "migrationCount": 1, "reconciledCount": 0, "reconciledMigrations": [],
                "baseSchema": {"path": "baseline.sql", "sha256": digest},
                "migrations": [{"path": path, "sourceSha256": digest}],
            }
            policy = {
                "schemaVersion": 1, "sourceCommit": commit,
                "sourceMigrationCount": 1, "baselineManifestMigrationCount": 1,
                "baselineMissingMigrations": {}, "baselineMissingReason": "none missing",
                "dataOnlyMigrations": {}, "dataOnlyReason": "none data-only",
                "baselineSeed": {"path": "seed.sql", "sha256": hashlib.sha256(seed.read_bytes()).hexdigest(),
                                 "reason": "synthetic v1 seed", "sourceFiles": {}},
            }
            metadata_file = root / "manifest.json"
            policy_file = root / "policy.json"
            metadata_file.write_text(json.dumps(metadata), encoding="utf-8")
            policy_file.write_text(json.dumps(policy), encoding="utf-8")
            with mock.patch.object(build_schema, "REPO", root), \
                    mock.patch.object(build_schema, "BASELINE", baseline), \
                    mock.patch.object(build_schema, "BASELINE_SEED", seed), \
                    mock.patch.object(build_schema, "BASELINE_MANIFEST", metadata_file), \
                    mock.patch.object(build_schema, "SCHEMA_REPLAY_POLICY", policy_file):
                self.assertEqual([], build_schema.migrations_after_baseline([{"path": path}]))

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
                with self.assertRaisesRegex(RuntimeError, "source classification 指纹不一致"):
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

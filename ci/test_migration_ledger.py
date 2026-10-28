#!/usr/bin/env python3
"""P1-3D migration manifest/history contract tests (pure local, no DB/network)."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from migration_ledger import (  # noqa: E402
    LedgerError,
    audit_inventory,
    build_manifest,
    plan_release,
    validate_history_snapshot_source,
    verify_manifest_files,
    verify_target_commit,
)


TARGET = "a" * 40


class RepoFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "chengyinhub-admin/sql").mkdir(parents=True)
        (self.tmp / "sql").mkdir()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def write(self, relative, text="SELECT 1;\n"):
        path = self.tmp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path


class ManifestContract(RepoFixture):
    def test_station_proposal_is_exact_auxiliary_not_an_execution_exemption(self):
        path = "docs/delivery/architecture-day/G/supply-order-fulfillment-contract/PROPOSED_TABLE.sql"
        text = "CREATE TABLE coop_order_station_fulfillment (id BIGINT);\n"
        self.write(path, text)
        canonical = "chengyinhub-admin/sql/migration_20261025_coop_order_station_fulfillment.sql"
        self.write(canonical, text)
        report = audit_inventory(self.tmp)
        self.assertEqual([], report["problems"])
        self.assertEqual([(path, "AUXILIARY_NOT_EXECUTED")],
                         [(item["path"], item["classification"]) for item in report["auxiliarySql"]])
        self.assertEqual([canonical], [item["path"] for item in report["migrations"]])
        manifest = build_manifest(self.tmp, TARGET, dependencies={})
        self.assertEqual([canonical], [item["path"] for item in manifest["migrations"]])
        unknown = path.replace("PROPOSED_TABLE.sql", "UNREVIEWED_TABLE.sql")
        self.write(unknown, text)
        self.assertEqual({("UNMANAGED_SQL_LOCATION", unknown)},
                         {(item["code"], item["path"]) for item in audit_inventory(self.tmp)["problems"]})
        with self.assertRaises(LedgerError) as rejected:
            build_manifest(self.tmp, TARGET, dependencies={})
        self.assertEqual("SQL inventory is unsafe", str(rejected.exception))

    def test_architecture_day_auxiliary_paths_are_exact_not_directory_exceptions(self):
        reviewed = {
            "chengyinhub-admin/src/test/resources/architecture-day-a-fixture.sql": "TEST_FIXTURE",
            "chengyinhub-admin/src/test/resources/it-schema/official-broadcast-delivery-it-schema.sql": "TEST_FIXTURE",
            "docs/delivery/architecture-day/A/schema-candidate.sql": "AUXILIARY_NOT_EXECUTED",
            "docs/delivery/architecture-day/A/schema-preflight.sql": "AUXILIARY_NOT_EXECUTED",
        }
        for path in reviewed:
            self.write(path)
        report = audit_inventory(self.tmp)
        self.assertEqual([], report["problems"])
        self.assertEqual([], report["migrations"])
        self.assertEqual(reviewed, {item["path"]: item["classification"] for item in report["auxiliarySql"]})
        unknown = [str(Path(path).with_name("unreviewed-" + Path(path).name)) for path in reviewed]
        for path in unknown:
            self.write(path)
        report = audit_inventory(self.tmp)
        self.assertEqual({("UNMANAGED_SQL_LOCATION", path) for path in unknown},
                         {(item["code"], item["path"]) for item in report["problems"]})

    def test_generic_route_runtime_schema_is_explicit_test_fixture(self):
        path = "chengyinhub-admin/src/test/resources/it-schema/generic-route-runtime-it-schema.sql"
        self.write(path, "CREATE TABLE topic_route_session(id BIGINT);\n")
        report = audit_inventory(self.tmp)
        self.assertEqual([], report['problems'])
        self.assertEqual([path], [item['path'] for item in report['auxiliarySql']])
        self.assertEqual('TEST_FIXTURE', report['auxiliarySql'][0]['classification'])
        self.assertEqual([], report['migrations'])

    def test_gameplay_reskin_commercial_schema_is_only_the_reviewed_fixture(self):
        path = "chengyinhub-admin/src/test/resources/it-schema/gameplay-reskin-commercial-it-schema.sql"
        self.write(path, "CREATE TABLE oms_ticket(id BIGINT);\n")
        report = audit_inventory(self.tmp)
        self.assertEqual([], report['problems'])
        self.assertEqual([path], [item['path'] for item in report['auxiliarySql']])
        self.assertEqual('TEST_FIXTURE', report['auxiliarySql'][0]['classification'])
        self.assertEqual([], report['migrations'])
        unknown = "chengyinhub-admin/src/test/resources/it-schema/gameplay-reskin-unreviewed.sql"
        self.write(unknown, "CREATE TABLE unknown_gameplay(id BIGINT);\n")
        self.assertIn(("UNMANAGED_SQL_LOCATION", unknown),
                      {(item['code'], item['path']) for item in audit_inventory(self.tmp)['problems']})

    def test_prelaunch_forward_migrations_require_their_existing_chains(self):
        declared = json.loads(
            Path(__file__).with_name("migration-dependencies.json").read_text(encoding="utf-8")
        )["dependencies"]
        targets = {
            "20261012_club_join_review": {"club_join_policy_20260809", "club_join_message_20260910"},
            "20261012_topic_like_unique": set(),
            "20261012_exp_reward_reversal": {
                "member_exp_earned_20261011", "20260817_tandianri_r6_product_contracts",
                "p3_phase_b_player_world_creator"
            },
            "20261012_roam_presence_privacy": {"roam"},
            "20261012_roam_anonymous_shop_visitors": {
                "roam_shop_visit_20260716", "20261012_roam_claim_fingerprints"
            },
            "20261012_im_media_visibility": {
                "im", "im_send_idempotency_20260913",
                "content_draft_im_poll_20260828", "wx_content_callback_inbox_20260724"
            },
        }
        for migration_id, required in targets.items():
            with self.subTest(migration=migration_id):
                self.assertEqual(required, set(declared[migration_id]))
        for migration_id in set(targets) | set().union(*targets.values()):
            self.write("chengyinhub-admin/sql/migration_" + migration_id + ".sql")
        dependencies = {name: declared[name] for name in targets}
        manifest = build_manifest(self.tmp, TARGET, dependencies=dependencies)
        ids = [item["migrationId"] for item in manifest["migrations"]]
        for migration_id, required in targets.items():
            for prerequisite in required:
                self.assertLess(ids.index(prerequisite), ids.index(migration_id))
        (self.tmp / "chengyinhub-admin/sql/migration_im.sql").unlink()
        with self.assertRaises(LedgerError):
            build_manifest(self.tmp, TARGET, dependencies=dependencies)

    def test_legacy_game_category_repair_runs_after_its_seed(self):
        repair = "20261007_legacy_game_v51_categories"
        seed = "seed_merchant_game_v51_20260827"
        seed_path = self.write("chengyinhub-admin/sql/migration_" + seed + ".sql")
        self.write("chengyinhub-admin/sql/migration_" + repair + ".sql")
        declared = json.loads(
            Path(__file__).with_name("migration-dependencies.json").read_text(encoding="utf-8")
        )["dependencies"]
        dependencies = {repair: declared.get(repair, [])}
        manifest = build_manifest(self.tmp, TARGET, dependencies=dependencies)
        ids = [item["migrationId"] for item in manifest["migrations"]]
        self.assertLess(ids.index(seed), ids.index(repair), "分类修复不能在种子插入前空跑")
        self.assertEqual(manifest["migrations"][1]["dependsOn"], [seed])
        seed_path.unlink()
        with self.assertRaises(LedgerError):
            build_manifest(self.tmp, TARGET, dependencies=dependencies)

    def test_manifest_is_deterministic_and_carries_order_dependencies_and_target(self):
        self.write("chengyinhub-admin/sql/migration_beta.sql")
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        dependencies = {"beta": ["alpha"]}

        first = build_manifest(self.tmp, TARGET, dependencies=dependencies)
        second = build_manifest(self.tmp, TARGET, dependencies=dependencies)

        self.assertEqual(first, second)
        self.assertEqual(first["targetCommit"], TARGET)
        self.assertEqual(
            [(m["migrationId"], m["order"], m["dependsOn"]) for m in first["migrations"]],
            [("alpha", 1, []), ("beta", 2, ["alpha"])],
        )
        self.assertRegex(first["migrations"][0]["sha256"], r"^[0-9a-f]{64}$")

    def test_seed_root_nested_and_bad_prefix_are_not_silently_omitted(self):
        self.write("chengyinhub-admin/sql/migration_ok.sql")
        self.write("chengyinhub-admin/sql/seed_needs_apply.sql")
        self.write("sql/migration_wrong_place.sql")
        self.write("chengyinhub-admin/sql/nested/migration_nested.sql")
        self.write("chengyinhub-admin/sql/manual_apply.sql")

        report = audit_inventory(self.tmp)

        codes = {(p["code"], p["path"]) for p in report["problems"]}
        self.assertIn(("INVALID_EXECUTABLE_PREFIX", "chengyinhub-admin/sql/seed_needs_apply.sql"), codes)
        self.assertIn(("INVALID_MIGRATION_LOCATION", "sql/migration_wrong_place.sql"), codes)
        self.assertIn(
            ("INVALID_MIGRATION_LOCATION", "chengyinhub-admin/sql/nested/migration_nested.sql"),
            codes,
        )
        self.assertIn(("INVALID_EXECUTABLE_PREFIX", "chengyinhub-admin/sql/manual_apply.sql"), codes)
        with self.assertRaises(LedgerError):
            build_manifest(self.tmp, TARGET)

    def test_startup_schema_is_exactly_classified_and_unknown_sibling_still_fails(self):
        fixture = "ci/upgrade-startup/schema.sql"
        self.write(fixture, "CREATE TABLE sys_config (config_id BIGINT);\n")
        manifest = build_manifest(self.tmp, TARGET)
        self.assertEqual(manifest["migrations"], [])
        self.assertEqual(
            [(item["path"], item["classification"]) for item in manifest["inventory"]["auxiliarySql"]],
            [(fixture, "TEST_FIXTURE")],
        )
        unknown = "ci/upgrade-startup/unreviewed.sql"
        self.write(unknown)
        report = audit_inventory(self.tmp)
        self.assertIn(("UNMANAGED_SQL_LOCATION", unknown),
                      {(item["code"], item["path"]) for item in report["problems"]})
        with self.assertRaises(LedgerError):
            build_manifest(self.tmp, TARGET)

    def test_reviewed_legacy_sql_is_explicitly_managed_or_auxiliary(self):
        self.write("chengyinhub-admin/sql/migration_ok.sql")
        self.write("chengyinhub-admin/sql/seed_preset_demo.sql", "INSERT INTO demo VALUES (1);\n")
        self.write(
            "chengyinhub-admin/sql/seed_refund_manual_menu_20260714.sql",
            "INSERT INTO sys_job (invoke_target) VALUES ('refundReconcileTask.reconcile()');\n",
        )
        self.write("chengyinhub-admin/sql/seed_official_content_template.sql", "-- reference only\n")
        self.write("chengyinhub-admin/sql/_四账映射与守恒规则_20260725.sql", "-- reference only\n")
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/cms-topic-it-schema.sql",
            "CREATE TABLE cms_topic (id BIGINT);\n",
        )
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/coop-it-schema.sql",
            "CREATE TABLE coop_order (id BIGINT);\n",
        )
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/run41-prod-ddl.sql",
            "CREATE TABLE run41_fixture (id BIGINT);\n",
        )
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/run41-prod-seed.sql",
            "INSERT INTO run41_fixture (id) VALUES (1);\n",
        )
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/game-module-mystery-market-seed.sql",
            "INSERT INTO cms_topic (id) VALUES (1);\n",
        )
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/run41-prod-ddl.sql",
            "CREATE TABLE cms_topic (id BIGINT);\n",
        )
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/run41-prod-seed.sql",
            "INSERT INTO cms_topic (id) VALUES (1);\n",
        )

        report = audit_inventory(self.tmp)

        self.assertEqual(report["problems"], [])
        self.assertEqual(
            {item["migrationId"] for item in report["migrations"]},
            {"ok", "seed_preset_demo", "seed_refund_manual_menu_20260714"},
        )
        self.assertEqual(
            {item["path"]: item["classification"] for item in report["auxiliarySql"]},
            {
                "chengyinhub-admin/sql/_四账映射与守恒规则_20260725.sql": "REFERENCE_ONLY",
                "chengyinhub-admin/sql/seed_official_content_template.sql": "REFERENCE_ONLY",
                "chengyinhub-admin/src/test/resources/it-schema/cms-topic-it-schema.sql": "TEST_FIXTURE",
                "chengyinhub-admin/src/test/resources/it-schema/coop-it-schema.sql": "TEST_FIXTURE",
                "chengyinhub-admin/src/test/resources/it-schema/run41-prod-ddl.sql": "TEST_FIXTURE",
                "chengyinhub-admin/src/test/resources/it-schema/run41-prod-seed.sql": "TEST_FIXTURE",
                "chengyinhub-admin/src/test/resources/it-schema/game-module-mystery-market-seed.sql":
                    "TEST_FIXTURE",
                "chengyinhub-admin/src/test/resources/it-schema/run41-prod-ddl.sql": "TEST_FIXTURE",
                "chengyinhub-admin/src/test/resources/it-schema/run41-prod-seed.sql": "TEST_FIXTURE",
            },
        )

    def test_unreviewed_seed_and_test_sql_still_fail_closed(self):
        self.write("chengyinhub-admin/sql/seed_unknown.sql")
        self.write("chengyinhub-admin/src/test/resources/it-schema/unknown.sql")

        report = audit_inventory(self.tmp)

        self.assertEqual(
            {(item["code"], item["path"]) for item in report["problems"]},
            {
                ("INVALID_EXECUTABLE_PREFIX", "chengyinhub-admin/sql/seed_unknown.sql"),
                ("UNMANAGED_SQL_LOCATION", "chengyinhub-admin/src/test/resources/it-schema/unknown.sql"),
            },
        )

    def test_reference_only_path_fails_if_executable_sql_is_added(self):
        self.write(
            "chengyinhub-admin/sql/seed_official_content_template.sql",
            "-- reference accidentally became executable\nSELECT 1;\n",
        )

        report = audit_inventory(self.tmp)

        self.assertEqual(
            [(item["code"], item["path"]) for item in report["problems"]],
            [
                (
                    "REFERENCE_SQL_NOT_INERT",
                    "chengyinhub-admin/sql/seed_official_content_template.sql",
                )
            ],
        )

    def test_uppercase_sql_extension_is_not_a_managed_migration(self):
        self.write("chengyinhub-admin/sql/migration_bad.SQL")

        report = audit_inventory(self.tmp)

        self.assertEqual(report["problems"][0]["code"], "INVALID_MIGRATION_EXTENSION")

    def test_duplicate_id_and_case_collision_fail_closed(self):
        self.write("chengyinhub-admin/sql/migration_Demo.sql")
        self.write("chengyinhub-admin/sql/nested/migration_demo.sql")

        report = audit_inventory(self.tmp)
        problem_codes = {p["code"] for p in report["problems"]}

        self.assertIn("CASE_COLLISION", problem_codes)
        self.assertIn("DUPLICATE_MIGRATION_ID", problem_codes)

    def test_dependency_cycle_is_fail_closed(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write("chengyinhub-admin/sql/migration_beta.sql")
        dependencies = {"alpha": ["beta"], "beta": ["alpha"]}

        with self.assertRaises(LedgerError) as ctx:
            build_manifest(self.tmp, TARGET, dependencies=dependencies)
        self.assertIn("cycle", str(ctx.exception).lower())

    def test_delimiter_shape_is_rejected_without_printing_sql(self):
        self.write(
            "chengyinhub-admin/sql/migration_proc.sql",
            "CREATE PROCEDURE p() BEGIN SELECT 1; END;\n",
        )

        report = audit_inventory(self.tmp)

        self.assertEqual(report["problems"][0]["code"], "UNSUPPORTED_SQL_SHAPE")
        self.assertNotIn("CREATE PROCEDURE", json.dumps(report, ensure_ascii=False))

    def test_delimiter_migration_is_managed_when_tokenizer_can_parse_it(self):
        self.write(
            "chengyinhub-admin/sql/migration_proc.sql",
            "DROP PROCEDURE IF EXISTS p;\n"
            "DELIMITER $$\n"
            "CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 'a;b'; END$$\n"
            "DELIMITER ;\n"
            "CALL p();\n",
        )

        report = audit_inventory(self.tmp)

        self.assertEqual(report["problems"], [])
        self.assertEqual([item["migrationId"] for item in report["migrations"]], ["proc"])

    def test_manifest_verification_detects_content_drift(self):
        path = self.write("chengyinhub-admin/sql/migration_once.sql")
        manifest = build_manifest(self.tmp, TARGET)
        path.write_text("SELECT 2;\n", encoding="utf-8")

        problems = verify_manifest_files(self.tmp, manifest)

        self.assertEqual(problems[0]["code"], "MANIFEST_CONTENT_DRIFT")

    def test_target_commit_must_contain_the_exact_manifest_content(self):
        path = self.write("chengyinhub-admin/sql/migration_once.sql")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        target = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True).strip()
        path.write_text("SELECT 2;\n", encoding="utf-8")
        manifest = build_manifest(self.tmp, target)

        problems = verify_target_commit(self.tmp, manifest)

        self.assertEqual(problems[0]["code"], "TARGET_COMMIT_CONTENT_MISMATCH")

    def test_target_commit_extra_migration_cannot_be_omitted_from_manifest(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write("chengyinhub-admin/sql/migration_beta.sql")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        target = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True).strip()
        manifest = build_manifest(self.tmp, target)
        manifest["migrations"] = manifest["migrations"][:1]

        problems = verify_target_commit(self.tmp, manifest)

        self.assertIn("TARGET_COMMIT_INVENTORY_MISMATCH", {p["code"] for p in problems})

    def test_target_commit_dependency_metadata_cannot_be_rewritten_in_manifest(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write("chengyinhub-admin/sql/migration_beta.sql")
        self.write(
            "ci/migration-dependencies.json",
            '{"schemaVersion":1,"dependencies":{"beta":["alpha"]}}\n',
        )
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        target = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True).strip()
        manifest = build_manifest(self.tmp, target)
        manifest["migrations"][1]["dependsOn"] = []

        problems = verify_target_commit(self.tmp, manifest)

        self.assertIn("TARGET_COMMIT_DEPENDENCY_MISMATCH", {p["code"] for p in problems})

    def test_target_commit_root_seed_cannot_be_omitted_from_manifest(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write("sql/quartz.sql")
        self.write("sql/ry_20250522.sql")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        first_target = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True
        ).strip()
        manifest = build_manifest(self.tmp, first_target)
        self.write("sql/seed_hidden.sql")
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "hidden seed"], cwd=self.tmp, check=True)
        manifest["targetCommit"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True
        ).strip()

        problems = verify_target_commit(self.tmp, manifest)

        self.assertIn("TARGET_COMMIT_SQL_INVENTORY_MISMATCH", {p["code"] for p in problems})

    def test_target_commit_missing_declared_auxiliary_is_an_explicit_error(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        target = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True).strip()
        manifest = build_manifest(self.tmp, target)
        manifest["inventory"]["auxiliarySql"].append({
            "path": "chengyinhub-admin/sql/_precheck_missing.sql",
            "sha256": "a" * 64,
            "classification": "AUXILIARY_NOT_EXECUTED",
        })

        problems = verify_target_commit(self.tmp, manifest)

        self.assertIn("TARGET_COMMIT_AUXILIARY_FILE_MISSING", {p["code"] for p in problems})

    def test_target_commit_not_found_is_explicit_error(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        target = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True).strip()
        manifest = build_manifest(self.tmp, target)
        manifest["targetCommit"] = "f" * 40

        problems = verify_target_commit(self.tmp, manifest)

        self.assertEqual(problems[0]["code"], "TARGET_COMMIT_NOT_FOUND")

    def test_target_commit_auxiliary_content_mismatch_is_detected(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write("chengyinhub-admin/sql/_precheck_aux.sql", "SELECT 1;\n")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        target = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True).strip()
        manifest = build_manifest(self.tmp, target)
        manifest["inventory"]["auxiliarySql"].append({
            "path": "chengyinhub-admin/sql/_precheck_aux.sql",
            "sha256": "f" * 64,
            "classification": "AUXILIARY_NOT_EXECUTED",
        })

        problems = verify_target_commit(self.tmp, manifest)

        self.assertIn("TARGET_COMMIT_AUXILIARY_CONTENT_MISMATCH", {p["code"] for p in problems})

    def test_target_commit_preserves_reviewed_legacy_managed_and_fixture_inventory(self):
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write("chengyinhub-admin/sql/seed_preset_demo.sql")
        self.write("chengyinhub-admin/sql/seed_refund_manual_menu_20260714.sql")
        self.write("chengyinhub-admin/sql/seed_official_content_template.sql", "-- reference\n")
        self.write("chengyinhub-admin/sql/_四账映射与守恒规则_20260725.sql", "-- reference\n")
        self.write("chengyinhub-admin/src/test/resources/it-schema/cms-topic-it-schema.sql")
        self.write("chengyinhub-admin/src/test/resources/it-schema/coop-it-schema.sql")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)
        target = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.tmp, text=True).strip()

        manifest = build_manifest(self.tmp, target)

        self.assertEqual(verify_target_commit(self.tmp, manifest), [])
        self.assertEqual(
            {item["migrationId"] for item in manifest["migrations"]},
            {"alpha", "seed_preset_demo", "seed_refund_manual_menu_20260714"},
        )


class HistoryPlanning(RepoFixture):
    def setUp(self):
        super().setUp()
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write("chengyinhub-admin/sql/migration_beta.sql")
        self.manifest = build_manifest(self.tmp, TARGET, dependencies={"beta": ["alpha"]})
        self.alpha, self.beta = self.manifest["migrations"]
        self.fresh = {"status": "FRESH_EMPTY"}
        self.reconciled = {"status": "RECONCILED", "targetCommit": TARGET}
        self.baseline_ids = {"alpha", "beta"}

    @staticmethod
    def row(migration, status="APPLIED", **overrides):
        row = {
            "migrationId": migration["migrationId"],
            "path": migration["path"],
            "checksum": migration["sha256"],
            "targetCommit": TARGET,
            "appliedAt": "2026-07-24T12:00:00Z" if status in ("APPLIED", "RECONCILED") else None,
            "status": status,
        }
        row.update(overrides)
        return row

    def test_first_run_is_pending(self):
        plan = plan_release(self.manifest, [], bootstrap=self.fresh)
        self.assertEqual([m["state"] for m in plan["migrations"]], ["PENDING", "PENDING"])
        self.assertTrue(plan["canProceed"])

    def test_fresh_empty_bootstrap_with_history_rows_must_fail(self):
        with self.assertRaises(LedgerError):
            plan_release(self.manifest, [self.row(self.alpha)], bootstrap=self.fresh)

    def test_reconciled_bootstrap_without_baseline_ids_must_fail(self):
        with self.assertRaises(LedgerError):
            plan_release(self.manifest, [], bootstrap=self.reconciled)

    def test_existing_database_defaults_to_blocked_bootstrap_not_bulk_pending(self):
        plan = plan_release(self.manifest, [])
        self.assertEqual({m["state"] for m in plan["migrations"]}, {"BLOCKED_BOOTSTRAP"})
        self.assertFalse(plan["canProceed"])

    def test_reconciled_baseline_requires_a_history_row_for_every_baseline_migration(self):
        plan = plan_release(
            self.manifest,
            [self.row(self.alpha, status="RECONCILED")],
            bootstrap=self.reconciled,
            baseline_ids=self.baseline_ids,
        )
        states = {m["migrationId"]: m["state"] for m in plan["migrations"]}
        self.assertEqual(states["alpha"], "APPLIED")
        self.assertEqual(states["beta"], "BLOCKED_BOOTSTRAP")
        self.assertFalse(plan["canProceed"])

    def test_deleted_baseline_file_and_missing_history_row_is_still_blocked(self):
        plan = plan_release(
            self.manifest,
            [self.row(self.alpha)],
            bootstrap=self.reconciled,
            baseline_ids={"alpha", "deleted_baseline"},
        )
        missing = [m for m in plan["migrations"] if m["migrationId"] == "deleted_baseline"]
        self.assertEqual(missing[0]["state"], "BLOCKED_MISSING")
        self.assertFalse(plan["canProceed"])

    def test_applied_same_checksum_is_idempotent_skip(self):
        plan = plan_release(
            self.manifest,
            [self.row(self.alpha), self.row(self.beta)],
            bootstrap=self.reconciled,
            baseline_ids=self.baseline_ids,
        )
        self.assertEqual([m["state"] for m in plan["migrations"]], ["APPLIED", "APPLIED"])
        self.assertEqual(plan["summary"]["executeCount"], 0)

    def test_checksum_drift_is_blocked(self):
        drifted = self.row(self.alpha, checksum="f" * 64)
        plan = plan_release(
            self.manifest, [drifted], bootstrap=self.reconciled, baseline_ids=self.baseline_ids
        )
        self.assertEqual(plan["migrations"][0]["state"], "BLOCKED_DRIFT")
        self.assertFalse(plan["canProceed"])

    def test_interrupted_or_ddl_implicit_commit_attempt_is_unknown_until_reconciled(self):
        for status in ("APPLYING", "UNKNOWN"):
            with self.subTest(status=status):
                plan = plan_release(
                    self.manifest,
                    [self.row(self.alpha, status=status), self.row(self.beta)],
                    bootstrap=self.reconciled,
                    baseline_ids=self.baseline_ids,
                )
                self.assertEqual(plan["migrations"][0]["state"], "BLOCKED_UNKNOWN")
                self.assertFalse(plan["canProceed"])

    def test_reconciled_row_is_applied_only_with_same_checksum(self):
        plan = plan_release(
            self.manifest,
            [self.row(self.alpha, status="RECONCILED"), self.row(self.beta)],
            bootstrap=self.reconciled,
            baseline_ids=self.baseline_ids,
        )
        self.assertEqual(plan["migrations"][0]["state"], "APPLIED")

    def test_history_row_without_file_is_blocked_missing(self):
        orphan = {
            "migrationId": "deleted_file",
            "path": "chengyinhub-admin/sql/migration_deleted_file.sql",
            "checksum": "b" * 64,
            "targetCommit": TARGET,
            "appliedAt": "2026-07-24T12:00:00Z",
            "status": "APPLIED",
        }
        plan = plan_release(
            self.manifest, [orphan], bootstrap=self.reconciled, baseline_ids=set()
        )
        missing = [m for m in plan["migrations"] if m["state"] == "BLOCKED_MISSING"]
        self.assertEqual([m["migrationId"] for m in missing], ["deleted_file"])
        self.assertFalse(plan["canProceed"])

    def test_dependency_cannot_run_before_pending_parent(self):
        plan = plan_release(
            self.manifest,
            [self.row(self.beta)],
            bootstrap=self.reconciled,
            baseline_ids=set(),
        )
        states = {m["migrationId"]: m["state"] for m in plan["migrations"]}
        self.assertEqual(states["alpha"], "PENDING")
        self.assertEqual(states["beta"], "BLOCKED_DEPENDENCY_ORDER")
        self.assertFalse(plan["canProceed"])

    def test_history_rejects_sql_body_or_noncanonical_path_metadata(self):
        with self.assertRaises(LedgerError):
            plan_release(
                self.manifest,
                [self.row(self.alpha, sqlBody="must-not-be-stored")],
                bootstrap=self.reconciled,
                baseline_ids=self.baseline_ids,
            )

    def test_self_asserted_reconciled_snapshot_is_not_db_observation(self):
        with self.assertRaises(LedgerError):
            validate_history_snapshot_source({
                "schemaVersion": 1,
                "source": "SELF_ASSERTED",
                "bootstrap": self.reconciled,
                "rows": [self.row(self.alpha), self.row(self.beta)],
            })
        with self.assertRaises(LedgerError):
            plan_release(
                self.manifest,
                [self.row(self.alpha, path="../../secret")],
                bootstrap=self.reconciled,
                baseline_ids=self.baseline_ids,
            )

    def test_history_snapshot_schema_version_is_required_and_exact(self):
        for version in (None, 999):
            with self.subTest(version=version), self.assertRaises(LedgerError):
                snapshot = {
                    "source": "CHENGYIN_DB_MIGRATE_READ_ONLY_EXPORT",
                    "bootstrap": self.reconciled,
                    "rows": [],
                }
                if version is not None:
                    snapshot["schemaVersion"] = version
                validate_history_snapshot_source(snapshot)

    def test_retention_structure_repairs_are_pre_deploy_without_enabling_jobs(self):
        ids = ["20261012_roam_location_retention", "20261012_roam_share_retention", "20261012_roam_claim_fingerprints",
               "20261012_roam_anonymous_shop_visitors"]
        for migration_id in ids:
            self.write("chengyinhub-admin/sql/migration_" + migration_id + ".sql")
        manifest = build_manifest(self.tmp, TARGET,
                                  dependencies={"beta": ["alpha"], ids[0]: ["beta"], ids[1]: [ids[0]], ids[2]: [ids[1]], ids[3]: [ids[2]]})
        for item in manifest["migrations"]:
            self.assertNotIn("executionPhase", item)
        plan = plan_release(manifest, [], bootstrap=self.fresh)
        self.assertEqual(6, plan["summary"]["executeCount"])
        self.assertEqual(0, plan["summary"]["deferredPostDeploy"])

    def test_npc_moderated_audio_identity_is_pre_deploy_after_real_registry_schema(self):
        ids = ["npc_chat_request_20260722", "20261012_app_media_asset",
               "20261012_app_media_asset_namespaces", "20261012_npc_chat_media_asset"]
        for migration_id in ids:
            self.write("chengyinhub-admin/sql/migration_" + migration_id + ".sql")
        dependencies = {"beta": ["alpha"], ids[2]: [ids[1]], ids[3]: ids[:3]}
        manifest = build_manifest(self.tmp, TARGET, dependencies=dependencies)
        plan = plan_release(manifest, [], bootstrap=self.fresh)
        self.assertEqual(6, plan["summary"]["executeCount"])
        self.assertEqual(0, plan["summary"]["deferredPostDeploy"])
        npc = next(item for item in manifest["migrations"] if item["migrationId"] == ids[3])
        self.assertNotIn("executionPhase", npc)
        self.assertEqual(set(ids[:3]), set(npc["dependsOn"]))

    def test_new_commerce_job_is_post_deploy_without_weakening_bootstrap_or_drift(self):
        job_id = "20261012_merchant_commerce_reconcile_job"
        self.write("chengyinhub-admin/sql/migration_" + job_id + ".sql")
        manifest = build_manifest(self.tmp, TARGET, dependencies={"beta": ["alpha"], job_id: ["beta"]})
        job = next(item for item in manifest["migrations"] if item["migrationId"] == job_id)
        self.assertEqual(job["executionPhase"], "POST_DEPLOY")
        self.assertNotIn("executionPhase", next(item for item in manifest["migrations"]
                                                if item["migrationId"] == "alpha"))
        plan = plan_release(manifest, [], bootstrap=self.fresh)
        self.assertEqual(plan["summary"]["executeCount"], 2)
        self.assertEqual(plan["summary"]["deferredPostDeploy"], 1)
        self.assertEqual(next(item for item in plan["migrations"]
                              if item["migrationId"] == job_id)["state"], "DEFERRED_POST_DEPLOY")
        rows = [self.row(item) for item in manifest["migrations"]]
        plan = plan_release(manifest, rows, bootstrap=self.reconciled, baseline_ids=set())
        self.assertEqual(plan["summary"]["applied"], 3)
        rows[-1]["checksum"] = "0" * 64
        plan = plan_release(manifest, rows, bootstrap=self.reconciled, baseline_ids=set())
        self.assertEqual(next(item for item in plan["migrations"]
                              if item["migrationId"] == job_id)["state"], "BLOCKED_DRIFT")
        plan = plan_release(manifest, [])
        self.assertTrue(all(item["state"] == "BLOCKED_BOOTSTRAP" for item in plan["migrations"]))

    def test_missing_monitor_registration_repair_is_deferred_without_rephasing_legacy_seed(self):
        repair = "20261012_monitor_missing_jobs"
        legacy = "audit_backlog_monitor_job"
        self.write("chengyinhub-admin/sql/migration_" + repair + ".sql")
        self.write("chengyinhub-admin/sql/migration_" + legacy + ".sql")
        manifest = build_manifest(self.tmp, TARGET, dependencies={repair: [legacy]})
        plan = plan_release(manifest, [], bootstrap=self.fresh)
        states = {row["migrationId"]: row["state"] for row in plan["migrations"]}
        self.assertEqual("DEFERRED_POST_DEPLOY", states[repair])
        self.assertEqual("PENDING", states[legacy])
        self.assertEqual(1, plan["summary"]["deferredPostDeploy"])
        self.assertNotIn("executionPhase", next(row for row in manifest["migrations"] if row["migrationId"] == legacy))

    def test_demo_seed_is_inventory_only_and_never_a_pending_release_write(self):
        self.write("chengyinhub-admin/sql/seed_preset_demo.sql")
        manifest = build_manifest(self.tmp, TARGET, dependencies={"beta": ["alpha"]})
        demo = next(item for item in manifest["migrations"] if item["migrationId"] == "seed_preset_demo")
        self.assertEqual(demo["executionScope"], "TEST_FIXTURE")
        plan = plan_release(manifest, [], bootstrap=self.fresh)
        self.assertEqual(plan["summary"]["executeCount"], 2)
        self.assertEqual(next(item for item in plan["migrations"]
                              if item["migrationId"] == "seed_preset_demo")["state"], "EXCLUDED_TEST_FIXTURE")

    def test_reviewed_legacy_managed_path_is_valid_history_metadata(self):
        self.write("chengyinhub-admin/sql/seed_preset_demo.sql")
        manifest = build_manifest(self.tmp, TARGET, dependencies={"beta": ["alpha"]})
        seed = next(item for item in manifest["migrations"] if item["migrationId"] == "seed_preset_demo")

        plan = plan_release(
            manifest,
            [self.row(seed)],
            bootstrap=self.reconciled,
            baseline_ids=set(),
        )

        self.assertEqual(
            next(item for item in plan["migrations"] if item["migrationId"] == "seed_preset_demo")["state"],
            "APPLIED",
        )


class CliSafety(RepoFixture):
    def test_cli_has_no_apply_command_and_audit_redacts_sql_body(self):
        self.write(
            "chengyinhub-admin/sql/migration_secret_shape.sql",
            "CREATE PROCEDURE secret_token() BEGIN SELECT 'do-not-print'; END;\n",
        )
        script = Path(__file__).with_name("migration_ledger.py")

        result = subprocess.run(
            [sys.executable, str(script), "apply"],
            cwd=self.tmp,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("do-not-print", result.stdout)

    def test_release_manifest_embeds_validated_migration_ledger(self):
        repo_root = Path(__file__).resolve().parent.parent
        (self.tmp / "scripts").mkdir(exist_ok=True)
        (self.tmp / "ci").mkdir(exist_ok=True)
        for relative in (
            "scripts/release-manifest.sh",
            "ci/migration_ledger.py",
            "ci/migration_gates.py",
            "ci/sql_splitter.py",
        ):
            source = repo_root / relative
            destination = self.tmp / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        self.write("chengyinhub-admin/sql/migration_alpha.sql")
        self.write(
            "chengyinhub-admin/src/test/resources/it-schema/game-module-mystery-market-seed.sql",
            "INSERT INTO cms_topic (id) VALUES (1);\n",
        )
        self.write("sql/quartz.sql")
        self.write("sql/ry_20250522.sql")
        self.write("chengyinhub-xcx/package-lock.json", "{}\n")
        self.write("chengyinhub-ui/package-lock.json", "{}\n")
        self.write("chengyinhub-admin/target/chengyinhub-admin.jar", "fixture\n")
        subprocess.run(["git", "init", "-q"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.tmp, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.tmp, check=True)
        subprocess.run(["git", "add", "."], cwd=self.tmp, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.tmp, check=True)

        env = dict(os.environ, RELEASE_VERSION="test-1")
        result = subprocess.run(
            ["bash", "scripts/release-manifest.sh", "out.json"],
            cwd=self.tmp,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stdout)
        payload = json.loads((self.tmp / "out.json").read_text(encoding="utf-8"))
        self.assertEqual(payload["migrationLedger"]["targetCommit"], payload["gitSha"])
        self.assertEqual(payload["migrations"][0]["path"],
                         "chengyinhub-admin/sql/migration_alpha.sql")
        self.assertNotIn(
            "chengyinhub-admin/src/test/resources/it-schema/game-module-mystery-market-seed.sql",
            {item["path"] for item in payload["migrations"]},
        )
        self.assertIn(
            {
                "path": "chengyinhub-admin/src/test/resources/it-schema/game-module-mystery-market-seed.sql",
                "sha256": next(
                    item["sha256"]
                    for item in payload["migrationLedger"]["inventory"]["auxiliarySql"]
                    if item["path"]
                    == "chengyinhub-admin/src/test/resources/it-schema/game-module-mystery-market-seed.sql"
                ),
                "classification": "TEST_FIXTURE",
            },
            payload["migrationLedger"]["inventory"]["auxiliarySql"],
        )


class HistorySchemaContract(unittest.TestCase):
    def test_wp08_schema_guard_waits_for_original_effect_tables_and_im_identity(self):
        root = Path(__file__).resolve().parent.parent
        dependencies = json.loads(
            (root / "ci/migration-dependencies.json").read_text(encoding="utf-8")
        )["dependencies"]
        consumer = "20261023_g_wp08_delivery_jobs_paused"
        required = {
            "20261013_b_im_notification_intent",
            "20261013_roam_reward_delivery",
            "20261013_roam_badge_delivery",
            "member_exp_earned_20261011",
            "20261012_im_media_visibility",
        }
        self.assertTrue(required.issubset(set(dependencies[consumer])))
        manifest = build_manifest(root, target_commit=TARGET, dependencies=dependencies)
        positions = {item["migrationId"]: item["order"] for item in manifest["migrations"]}
        # Visibility already depends on original IM message idempotency. Do not duplicate
        # or replace that edge: WP08's third assertion needs all three columns + its unique key.
        for provider in required | {"im_send_idempotency_20260913"}:
            with self.subTest(provider=provider):
                self.assertLess(positions[provider], positions[consumer])

    def test_wp08_missing_guard_prerequisite_is_rejected_by_manifest_gate(self):
        from migration_gates import check_manifest_dependency_order

        root = Path(__file__).resolve().parent.parent
        dependencies = json.loads(
            (root / "ci/migration-dependencies.json").read_text(encoding="utf-8")
        )["dependencies"]
        consumer = "20261023_g_wp08_delivery_jobs_paused"
        # Reject either missing edge even if unrelated lexical ordering happens to place its
        # table first. A valid manifest must carry the required dependency explicitly.
        required = {"member_exp_earned_20261011", "20261012_im_media_visibility"}
        for missing in ({"member_exp_earned_20261011"}, {"20261012_im_media_visibility"}, required):
            with self.subTest(missing=missing):
                broken = dict(dependencies)
                broken[consumer] = [dep for dep in dependencies[consumer] if dep not in missing]
                manifest = build_manifest(root, target_commit=TARGET, dependencies=broken)
                problems = check_manifest_dependency_order(manifest, [], dependencies)
                self.assertIn("ledger order/dependency metadata mismatch: " + consumer, problems)
                if missing == required:
                    # Restore the exact original three-edge entry: its registration really
                    # sorts before both omitted providers in the canonical repository graph.
                    positions = {item["migrationId"]: item["order"] for item in manifest["migrations"]}
                    for provider in required:
                        self.assertLess(positions[consumer], positions[provider])

    def test_ticket_source_migration_expands_payable_component_check(self):
        root = Path(__file__).resolve().parent.parent
        sql = (
            root
            / "chengyinhub-admin/sql/migration_20260817_tandianri_batch3_funds_ticket_source_payable.sql"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "CHECK (component IN ('FIXED', 'QUALITY', 'EXTRA', 'TICKET_SOURCE'))",
            sql,
        )
        self.assertIn("UPPER(check_clause) LIKE '%TICKET_SOURCE%'", sql)

    def test_repository_dependency_metadata_covers_documented_sql_prerequisites(self):
        root = Path(__file__).resolve().parent.parent
        payload = json.loads(
            (root / "ci/migration-dependencies.json").read_text(encoding="utf-8")
        )
        dependencies = payload["dependencies"]
        self.assertEqual(dependencies["city_merchant_node"], ["roam"])
        self.assertEqual(
            dependencies["topic_plaza_recommend_permission_20260723"],
            ["topic_source_menu_20260722"],
        )
        self.assertEqual(
            dependencies["20260815_tandianri_batch3_funds_core"],
            ["club", "product_type_purchase_kind_20260716"],
        )
        # ★club_compensation 的真依赖是 funds_core,不是 club_payee。
        #   它用 information_schema + PREPARE 给 `topic_edition_cost` 加 extra_approver_user_id,
        #   而那张表由 funds_core 建 —— 这是一条**对象**依赖。
        #   它对 club_payee 只有三个裸 BIGINT 列(payee_profile_id / _version / _facts_hash),
        #   没有外键、没有引用 club_payee 建的任何表 ⇒ 那条边是假的。
        #   假边一旦被人按「它不引用 payee 任何表」删掉,拓扑序就允许 club_compensation 先跑,
        #   `ALTER TABLE topic_edition_cost` 当场报表不存在 —— 真依赖不能靠假边传递成立。
        self.assertEqual(
            dependencies["20260816_tandianri_batch3_funds_club_compensation"],
            ["20260815_tandianri_batch3_funds_core", "web_admin_roles_20260719"],
        )
        self.assertEqual(
            dependencies["20260817_tandianri_batch3_funds_ticket_source_payable"],
            ["20260816_tandianri_batch3_funds_club_compensation"],
        )
        self.assertEqual(
            dependencies["20260817_tandianri_r6_product_contracts"],
            ["m7_xp_budget", "product_type_purchase_kind_20260716"],
        )
        self.assertEqual(
            dependencies["20260817_tandianri_r7_distribution_backfill"],
            [
                "20260816_tandianri_batch3_funds_club_compensation",
                "20260815_shop_day_organizer_actor",
            ],
        )
        self.assertEqual(
            dependencies["20260817_tandianri_r7_distribution_singleton"],
            ["20260817_tandianri_r7_distribution_backfill"],
        )
        # ★三份资金迁移都用 `SET @… := (SELECT role_id FROM sys_role WHERE role_key='cy_finance'…)`
        #   给菜单授权,并在文件末尾用 `-- runner: assert=scalar-true` 硬断言授权真的落到了那两个角色上。
        #   而 cy_finance / cy_operator 这两个角色由 migration_web_admin_roles_20260719.sql 创建。
        #   原来的链路 funds_core → club → rbac_role **不含**它:rbac_role 建的是 RBAC 表,
        #   不是这两条角色行。少这条边,拓扑序允许本迁移先跑 ⇒ 表建好了、末尾断言再红
        #   ⇒ 部分落库 + 门禁红,人工还得先判断「是没跑角色迁移,还是本迁移写错了」。
        #   依赖图本来就该直接答这个问题。
        self.assertEqual(
            dependencies["20260816_tandianri_batch3_funds_club_payee"],
            ["20260815_tandianri_batch3_funds_core", "web_admin_roles_20260719"],
        )
        self.assertEqual(
            dependencies["20260816_tandianri_batch3_funds_player_cooldown"],
            ["20260815_tandianri_batch3_funds_core", "web_admin_roles_20260719"],
        )
        self.assertEqual(
            dependencies["20260815_tandianri_roam_referral"],
            ["product_type_purchase_kind_20260716"],
        )
        self.assertEqual(
            dependencies["merchant_onsite_consent_scope_20260815"],
            ["compliance_consent_deregister_20260711"],
        )
        self.assertEqual(
            dependencies["shop_day_batch_capacity_20260815"],
            ["topic_template_meta_20260726"],
        )
        self.assertEqual(
            dependencies["20260815_shop_day_organizer_actor"],
            ["product_role_20260716"],
        )
        self.assertEqual(
            dependencies["topic_sale_canonical15_20260815"],
            ["topic_commercial_review_20260814"],
        )
        self.assertEqual(dependencies["seed_preset_demo"], ["preset_route"])
        reference_pattern = re.compile(r"migration_([a-z0-9_]+)\.sql")
        keyword_pattern = re.compile(r"前置|依赖|after|depends", re.IGNORECASE)
        documented = {}
        for path in (root / "chengyinhub-admin/sql").glob("migration_*.sql"):
            consumer = path.stem[len("migration_"):]
            for line in path.read_text(encoding="utf-8").splitlines():
                if not line.lstrip().startswith("--") or not keyword_pattern.search(line):
                    continue
                for dependency in reference_pattern.findall(line.lower()):
                    if dependency != consumer:
                        documented.setdefault(consumer, set()).add(dependency)
        self.assertGreaterEqual(len(documented), 18)
        for consumer, required in documented.items():
            self.assertTrue(
                required.issubset(set(dependencies.get(consumer, []))),
                "%s missing documented dependencies %s" % (consumer, sorted(required)),
            )

    def test_history_migration_has_required_metadata_and_no_sql_body_column(self):
        root = Path(__file__).resolve().parent.parent
        path = root / "chengyinhub-admin/sql/migration_schema_history_20260724.sql"
        text = path.read_text(encoding="utf-8").lower()

        for column in (
            "migration_id",
            "migration_path",
            "checksum_sha256",
            "target_commit",
            "applied_at",
            "status",
        ):
            self.assertIn(column, text)
        for status in ("applying", "applied", "unknown", "reconciled"):
            self.assertIn(status, text)
        self.assertNotIn("sql_body", text)
        self.assertNotIn("password", text)
        self.assertNotIn("if not exists", text, "bootstrap schema drift must not be silently accepted")


class HistoricalForwardFixContract(unittest.TestCase):
    def test_duplicate_events_fail_before_any_mutation_and_are_never_deleted(self):
        root = Path(__file__).resolve().parent.parent
        path = root / "chengyinhub-admin/sql/migration_zz_20260921_historical_schema_forward_fix.sql"
        text = path.read_text(encoding="utf-8").lower()

        self.assertNotRegex(text, r"\bdelete\s+(?:from\s+)?(?:`?biz_event_log`?|duplicate_event)\b")
        duplicate_guard = text.index("having count(*) > 1")
        first_schema_mutation = text.index("set @add_biz_event_actor_scope")
        self.assertLess(duplicate_guard, first_schema_mutation)
        self.assertIn("-- runner: assert=scalar-true", text[:first_schema_mutation])


if __name__ == "__main__":
    unittest.main()

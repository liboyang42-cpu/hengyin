"""Prove source bindings and the existing narrow runner contract, without a database."""
import ast
import hashlib
import json
import re
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))
from run_migration import validate_transaction_statements, referenced_persistent_tables
from sql_splitter import split_statements


JOB_DICT_FORWARD = ROOT / "chengyinhub-admin/sql/migration_20261017_job_dictionary_missing_seed.sql"
JOB_DICT_DEPENDENCIES = ROOT / "ci/migration-dependencies.json"
REGISTRATION_CONTROLLER = ROOT / "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationController.java"


class MissingBusinessSeedsContract(unittest.TestCase):
    def setUp(self):
        self.provenance = json.loads((ROOT / "ci/prelaunch-seed-provenance.json").read_text())

    def assert_generic_registration_create_closed(self, source):
        # 06a571b1 deliberately closed this CRUD shortcut; the existing add permission is not
        # authority to manufacture payment/inventory facts outside the formal order flow.
        code = re.sub(r"/\*[\s\S]*?\*/|//[^\n]*", "", source)
        endpoint = re.search(
            r"(?P<annotations>(?:    @[^\n]+\n)+)"
            r"    public AjaxResult add\(@RequestBody CmsRegistration cmsRegistration\)\s*"
            r"\{(?P<body>[\s\S]*?)\n    \}", code)
        self.assertIsNotNone(endpoint, "the original generic-add HTTP entry must remain explicit")
        annotations = endpoint.group("annotations").splitlines()
        self.assertIn("    @PreAuthorize(\"@ss.hasPermi('business:registration:add')\")", annotations)
        self.assertIn("    @PostMapping", annotations)
        self.assertIsNotNone(re.fullmatch(
            r'\s*return\s+AjaxResult\.error\(\s*403\s*,\s*'
            r'"通用新增报名已关闭，请通过正式下单流程创建报名"\s*\)\s*'
            r'\.put\(\s*"errorCode"\s*,\s*"REGISTRATION_GENERIC_CREATE_DISABLED"\s*\)\s*;\s*',
            endpoint.group("body")), "generic add must reject without any preceding or delegated write")
        self.assertNotRegex(code, r"\binsertCmsRegistration\s*\(",
                            "the admin CRUD controller must not create registrations through another method")

    def test_source_hashes_and_real_controller_permissions(self):
        for path, sha in self.provenance["sourceFiles"].items():
            self.assertEqual(sha, hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), path)
        self.assert_generic_registration_create_closed(REGISTRATION_CONTROLLER.read_text())
        for row in self.provenance["menuRows"]:
            self.assertTrue((ROOT / "chengyinhub-ui/src/views" / (row["component"] + ".vue")).is_file())
            if row.get("controller"):
                matches = list((ROOT / "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller").rglob(row["controller"]))
                self.assertEqual(1, len(matches))
                self.assertIn("@ss.hasPermi('" + row["permission"] + "')", matches[0].read_text())
            else:
                # Pure UI/monitor pages retain their existing bootstrap permission string.
                self.assertIn(row["permission"], (ROOT / row["source"]).read_text())

    def test_generic_registration_create_controls_reject_write_and_permission_regressions(self):
        source = REGISTRATION_CONTROLLER.read_text()
        refusal = ('return AjaxResult.error(403, "通用新增报名已关闭，请通过正式下单流程创建报名")'
                   '\n                .put("errorCode", "REGISTRATION_GENERIC_CREATE_DISABLED");')
        permission = '    @PreAuthorize("@ss.hasPermi(\'business:registration:add\')")\n'
        self.assertEqual(1, source.count(refusal))
        self.assertEqual(1, source.count(permission))
        mutations = {
            "restore-old-insert": source.replace(refusal,
                "return toAjax(cmsRegistrationService.insertCmsRegistration(cmsRegistration));", 1),
            "write-before-denial": source.replace(refusal,
                "cmsRegistrationService.insertCmsRegistration(cmsRegistration);\n        " + refusal, 1),
            "remove-add-permission": source.replace(permission, "", 1),
            "false-success": source.replace(refusal, refusal.replace("error(403,", "error(200,"), 1),
        }
        for name, changed in mutations.items():
            with self.subTest(mutation=name):
                self.assertNotEqual(source, changed)
                with self.assertRaises(AssertionError):
                    self.assert_generic_registration_create_closed(changed)

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
        statements = split_statements((ROOT / "chengyinhub-admin/sql/_precheck_canonical_role_conflicts_20261012.sql").read_text())
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

    def test_task_dictionary_forward_is_atomic_missing_only_and_keeps_legacy_values(self):
        import re
        sql = JOB_DICT_FORWARD.read_text(encoding="utf-8")
        statements = split_statements(sql)
        validate_transaction_statements(statements)
        self.assertEqual({"sys_dict_type", "sys_dict_data"},
                         set(referenced_persistent_tables(statements, "scratch")))
        self.assertIn("-- runner: requires=transaction", sql)
        self.assertEqual(6, sum(bool(re.match(r"INSERT INTO sys_dict_(type|data)\b", item))
                                for item in statements))
        self.assertEqual(6, sql.count("WHERE NOT EXISTS"))
        self.assertNotRegex(sql, r"(?i)\b(?:UPDATE|DELETE|REPLACE|ALTER)\s")
        legacy = (ROOT / "sql/ry_20250522.sql").read_text(encoding="utf-8")
        for name, key, remark in (("任务状态", "sys_job_status", "任务状态列表"),
                                  ("任务分组", "sys_job_group", "任务分组列表")):
            self.assertIn("SELECT '" + name + "','" + key + "','0'", sql)
            self.assertRegex(legacy, re.escape("'" + name + "'") + r",\s*" + re.escape("'" + key + "'") + r",\s*'0'")
            self.assertIn(remark, legacy)
        for sort, label, value, key, style, default, remark in (
            (1,"正常","0","sys_job_status","primary","Y","正常状态"),
            (2,"暂停","1","sys_job_status","danger","N","停用状态"),
            (1,"默认","DEFAULT","sys_job_group","","Y","默认分组"),
            (2,"系统","SYSTEM","sys_job_group","","N","系统分组")):
            self.assertIn(f"SELECT {sort},'{label}','{value}','{key}','','{style}','{default}','0'", sql)
            self.assertRegex(legacy, rf"\b{sort},\s*'{label}',\s*'{value}',\s*'{key}'")
            self.assertIn(remark, legacy)

    def test_task_dictionary_forward_is_selected_by_supported_fresh_builder(self):
        from scripts.e2e import build_schema
        key = "20261017_job_dictionary_missing_seed"
        dependencies = json.loads(JOB_DICT_DEPENDENCIES.read_text(encoding="utf-8"))
        self.assertEqual([], dependencies["dependencies"][key])
        manifest = build_schema.build_manifest(ROOT, target_commit="a" * 40,
                                              dependency_path=ROOT / "ci/migration-dependencies.json")
        path = "chengyinhub-admin/sql/migration_" + key + ".sql"
        migrations = manifest["migrations"]
        self.assertIn(path, {row["path"] for row in migrations})
        selected = build_schema.migrations_after_baseline(migrations)
        self.assertIn(path, {row["path"] for row in selected})


if __name__ == "__main__":
    unittest.main()

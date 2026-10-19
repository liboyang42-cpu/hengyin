import hashlib
import json
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("w17_mysql_gate", ROOT / "ci/w17_mysql_gate.py")
gate = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(gate)
COMMIT, RUN = "a" * 40, "b" * 32


def report():
    root = ET.Element("testsuite", name=gate.SUITE, tests="31", failures="0", errors="0", skipped="0")
    for i, method in enumerate(sorted(gate.METHODS)):
        case = ET.SubElement(root, "testcase", classname=gate.SUITE, name=method, time="0.1")
        ET.SubElement(case, "system-out").text = (
            "W17_MYSQL_EVIDENCE METHOD=" + method + " VERSION=8.0.45 SCHEMA=cy_module_city_w17_noncash_"
            + format(i, "032x") + " MIGRATION_SHA256=" + gate.MIGRATION_SHA + " COMMIT=" + COMMIT
            + " RUN=" + RUN + " SCRATCH_DROPPED=true\n")
    return root


class W17MySqlProofTest(unittest.TestCase):
    def check(self, root, accepted=False, extra=None):
        with tempfile.TemporaryDirectory() as directory:
            ET.ElementTree(root).write(Path(directory) / ("TEST-" + gate.SUITE + ".xml"))
            if extra is not None:
                ET.ElementTree(extra).write(Path(directory) / "TEST-duplicate.xml")
            if accepted:
                self.assertEqual(31, gate.validate(directory, COMMIT, RUN)["tests"])
            else:
                with self.assertRaises(ValueError):
                    gate.validate(directory, COMMIT, RUN)

    def test_accepts_only_exact_complete_invocation(self):
        self.check(report(), accepted=True)

    def test_accepts_exact_hosted_surefire_method_display_format(self):
        root = report(); root.set("version", "3.0.2")
        for case in root.findall("testcase"):
            case.set("classname", case.get("name") + "()")
        self.check(root, accepted=True)

    def test_rejects_mismatched_display_name_arbitrary_suffix_and_mixed_formats(self):
        for invalid in ("unrelated()", sorted(gate.METHODS)[1] + "()", gate.SUITE,
                        sorted(gate.METHODS)[0] + "(value)"):
            root = report()
            for case in root.findall("testcase"):
                case.set("classname", case.get("name") + "()")
            root.find("testcase").set("classname", invalid)
            self.check(root)

    def test_rejects_missing_report(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError): gate.validate(directory, COMMIT, RUN)

    def test_rejects_missing_duplicate_and_unrelated_methods_at_equal_count(self):
        for name in ("unrelated", sorted(gate.METHODS)[1]):
            root = report(); root.findall("testcase")[0].set("name", name); self.check(root)

    def test_rejects_h2_and_namesake_class(self):
        root = report(); root.set("name", "fake.NonCashRewardMySqlIT"); self.check(root)
        root = report(); root.findall("testcase")[0].set("classname", "NonCashRewardPersistenceTest"); self.check(root)

    def test_rejects_duplicate_suite(self):
        self.check(report(), extra=report())

    def test_rejects_skips_failures_errors_and_short_counts(self):
        for key, value in (("tests", "30"), ("tests", "32"), ("skipped", "1"), ("errors", "1"), ("failures", "1")):
            root = report(); root.set(key, value); self.check(root)

    def test_rejects_retry_and_hidden_failure_even_if_summary_claims_green(self):
        for tag in ("flakyFailure", "flakyError", "rerunFailure", "rerunError", "skipped", "failure", "error"):
            root = report(); ET.SubElement(root.find("testcase"), tag); self.check(root)

    def test_rejects_missing_or_duplicate_cleanup_evidence(self):
        root = report(); root.find("testcase").remove(root.find("testcase/system-out")); self.check(root)
        root = report(); output = root.find("testcase/system-out"); output.text *= 2; self.check(root)

    def test_rejects_wrong_database_ddl_commit_invocation_or_cleanup(self):
        for old, new in (("8.0.45", "H2"), (gate.MIGRATION_SHA, "0" * 64), (COMMIT, "c" * 40),
                         (RUN, "d" * 32), ("SCRATCH_DROPPED=true", "SCRATCH_DROPPED=false")):
            root = report(); output = root.find("testcase/system-out"); output.text = output.text.replace(old, new); self.check(root)

    def test_rejects_shared_or_business_schema(self):
        root = report(); outputs = root.findall("testcase/system-out")
        outputs[1].text = outputs[1].text.replace(format(1, "032x"), format(0, "032x")); self.check(root)
        root = report(); output = root.find("testcase/system-out"); output.text = output.text.replace("cy_module_city_w17_noncash_" + format(0, "032x"), "chengyinhubdb"); self.check(root)


class W17BindingTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / ".github/workflows/funds-mysql-it.yml").read_text()
        self.script = (ROOT / "ci/funds-mysql-it-gate.sh").read_text()
        self.source = (ROOT / "chengyinhub-admin/src/test/java/com/chengyinhub/business/reward/NonCashRewardMySqlIT.java").read_text()

    def test_original_migration_bytes_and_java_constant_are_pinned(self):
        migration = ROOT / "chengyinhub-admin/sql/migration_w17_noncash_inventory_candidate.sql"
        self.assertEqual(gate.MIGRATION_SHA, hashlib.sha256(migration.read_bytes()).hexdigest())
        self.assertIn('MIGRATION_SHA="' + gate.MIGRATION_SHA + '"', self.source)

    def test_current_binding_and_all_existing_funds_cases_are_preserved(self):
        self.assertEqual([], gate.binding_problems(self.workflow, self.script, self.source))
        spec = importlib.util.spec_from_file_location("funds", ROOT / "ci/funds_it_gate.py")
        funds = importlib.util.module_from_spec(spec); spec.loader.exec_module(funds)
        self.assertEqual(31, funds.EXPECTED["NonCashRewardMySqlIT"])
        self.assertEqual(20, funds.EXPECTED["CouponCommandServiceMySqlIT"])
        self.assertEqual(28, funds.EXPECTED["MerchantNpcKnowledgeDraftMySqlIT"])
        # These independently gated suites were added after the immutable legacy inventory.
        additions = {"FixedCouponAtomicMySqlIT": 17, "FixedCouponSchemaMySqlIT": 13,
                     "MerchantAuthoringCurrentRowsMySqlIT": 7, "MerchantAuthoringRevocationRaceMySqlIT": 1, "CoopListReadOnlyMybatisIntegrationTest": 5}
        self.assertEqual(additions, {name: funds.EXPECTED[name] for name in additions})
        legacy = {name: value for name, value in funds.EXPECTED.items() if name not in additions and name not in {"NonCashRewardMySqlIT", "MerchantNpcKnowledgeDraftMySqlIT", "CouponCommandServiceMySqlIT"}}
        self.assertEqual("dd4b07e0456eee0f1887ae69c96c6264094c7542e15d818c1a9fb7bd21f9f22b", hashlib.sha256(json.dumps(legacy, sort_keys=True).encode()).hexdigest())

    def test_removing_selector_readback_clean_or_commit_binding_is_red(self):
        for old, new in ((",NonCashRewardMySqlIT,", ","),
                         ("python3 ci/w17_mysql_gate.py ", "# python3 ci/w17_mysql_gate.py "),
                         ("-am clean test", "-am test"),
                         ('-Dw17.test.commit="$w17_commit"', ""),
                         ('--run-id "$w17_run_id"', "")):
            with self.subTest(old=old): self.assertTrue(gate.binding_problems(self.workflow, self.script.replace(old, new), self.source))

    def test_removing_either_trigger_or_negative_control_execution_is_red(self):
        for old in ("- 'chengyinhub-admin/src/test/java/com/chengyinhub/business/reward/**'",
                    "- 'chengyinhub-admin/sql/migration_w17_noncash_inventory_candidate.sql'",
                    "ci/test_w17_mysql_gate.py"):
            self.assertTrue(gate.binding_problems(self.workflow.replace(old, "REMOVED", 1), self.script, self.source))

    def test_source_identity_opt_in_and_scratch_substitution_are_red(self):
        for old, new in (("void originalDdlEnforcesInnoDbForeignKeysChecksAndNoPadCollation()", "void unrelated()"),
                         ('matches="1"', 'matches=".*"'), ('mysql.apply(MIGRATION)', 'fakeApply(MIGRATION)'),
                         ("class NonCashRewardMySqlIT {", "class NonCashRewardMySqlIT extends H2Base {")):
            self.assertTrue(gate.binding_problems(self.workflow, self.script, self.source.replace(old, new)))


if __name__ == "__main__": unittest.main()

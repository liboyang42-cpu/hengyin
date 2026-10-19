import hashlib
import json
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("w16_mysql_gate", ROOT / "ci/w16_mysql_gate.py")
gate = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(gate)
COMMIT, RUN = "a" * 40, "b" * 32


def report():
    root = ET.Element("testsuite", name=gate.SUITE, tests="12", failures="0", errors="0", skipped="0")
    for i, method in enumerate(sorted(gate.METHODS)):
        case = ET.SubElement(root, "testcase", classname=gate.SUITE, name=method, time="0.1")
        ET.SubElement(case, "system-out").text = (
            "W16_MYSQL_EVIDENCE METHOD=" + method + " VERSION=8.0.45 SCHEMA=cy_module_city_w16_consent_"
            + format(i, "032x") + " MIGRATION_SHA256=" + gate.MIGRATION_SHA + " COMMIT=" + COMMIT
            + " RUN=" + RUN + " SCRATCH_DROPPED=true\n")
    return root


class W16MySqlProofTest(unittest.TestCase):
    def check(self, root, accepted=False, extra=None):
        with tempfile.TemporaryDirectory() as directory:
            ET.ElementTree(root).write(Path(directory) / ("TEST-" + gate.SUITE + ".xml"))
            if extra is not None:
                ET.ElementTree(extra).write(Path(directory) / "TEST-duplicate.xml")
            if accepted:
                self.assertEqual(12, gate.validate(directory, COMMIT, RUN)["tests"])
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
        root = report(); root.set("name", "fake.RendezvousConsentMySqlIT"); self.check(root)
        root = report(); root.findall("testcase")[0].set("classname", "RendezvousConsentPersistenceTest"); self.check(root)

    def test_rejects_duplicate_suite(self):
        self.check(report(), extra=report())

    def test_rejects_skips_failures_errors_and_short_counts(self):
        for key, value in (("tests", "11"), ("tests", "13"), ("skipped", "1"), ("errors", "1"), ("failures", "1")):
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
        root = report(); output = root.find("testcase/system-out"); output.text = output.text.replace("cy_module_city_w16_consent_" + format(0, "032x"), "chengyinhubdb"); self.check(root)


class W16BindingTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / ".github/workflows/funds-mysql-it.yml").read_text()
        self.script = (ROOT / "ci/funds-mysql-it-gate.sh").read_text()
        self.source = (ROOT / "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/rendezvous/RendezvousConsentMySqlIT.java").read_text()

    def test_current_binding_and_all_existing_funds_cases_are_preserved(self):
        self.assertEqual([], gate.binding_problems(self.workflow, self.script, self.source))
        spec = importlib.util.spec_from_file_location("funds", ROOT / "ci/funds_it_gate.py")
        funds = importlib.util.module_from_spec(spec); spec.loader.exec_module(funds)
        self.assertEqual(12, funds.EXPECTED["RendezvousConsentMySqlIT"])
        self.assertEqual(20, funds.EXPECTED["CouponCommandServiceMySqlIT"])
        self.assertEqual(28, funds.EXPECTED["MerchantNpcKnowledgeDraftMySqlIT"])
        # These independently gated suites were added after the immutable legacy inventory.
        additions = {"FixedCouponAtomicMySqlIT": 17, "FixedCouponSchemaMySqlIT": 13,
                     "MerchantAuthoringCurrentRowsMySqlIT": 7, "MerchantAuthoringRevocationRaceMySqlIT": 1, "CoopListReadOnlyMybatisIntegrationTest": 5}
        self.assertEqual(additions, {name: funds.EXPECTED[name] for name in additions})
        legacy = {name: value for name, value in funds.EXPECTED.items() if name not in additions and name not in {"RendezvousConsentMySqlIT", "NonCashRewardMySqlIT", "MerchantNpcKnowledgeDraftMySqlIT", "CouponCommandServiceMySqlIT"}}
        self.assertEqual("e09364538cc07249961bb437d5905387e8ff34c486f9ddc3759cd8602dfbe0a4", hashlib.sha256(json.dumps(legacy, sort_keys=True).encode()).hexdigest())

    def test_removing_selector_readback_clean_or_commit_binding_is_red(self):
        # Remove the exact selector wherever it is in the single -Dtest list, not only first.
        import re
        match = re.search(r"(?m)^  -Dtest=([^\n]+)$", self.script)
        self.assertIsNotNone(match)
        selectors = match.group(1).split(",")
        self.assertEqual(1, selectors.count("RendezvousConsentMySqlIT"))
        removed = self.script[:match.start(1)] + ",".join(x for x in selectors if x != "RendezvousConsentMySqlIT") + self.script[match.end(1):]
        self.assertNotEqual(self.script, removed)
        self.assertTrue(gate.binding_problems(self.workflow, removed, self.source))
        for old, new in (("python3 ci/w16_mysql_gate.py ", "# python3 ci/w16_mysql_gate.py "),
                         ("-am clean test", "-am test"),
                         ('-Dw16.test.commit="$w16_commit"', ""),
                         ('--run-id "$w16_run_id"', "")):
            with self.subTest(old=old):
                changed = self.script.replace(old, new)
                self.assertNotEqual(self.script, changed)
                self.assertTrue(gate.binding_problems(self.workflow, changed, self.source))

    def test_removing_either_trigger_or_negative_control_execution_is_red(self):
        for old in ("- 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/rendezvous/**'",
                    "- 'chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql'",
                    "ci/test_w16_mysql_gate.py"):
            self.assertTrue(gate.binding_problems(self.workflow.replace(old, "REMOVED", 1), self.script, self.source))

    def test_source_identity_opt_in_and_scratch_substitution_are_red(self):
        for old, new in (("void originalDdlCreatesInnoDbAndGeneratedActorUniqueness()", "void unrelated()"),
                         ('matches="1"', 'matches=".*"'), ('mysql.apply(MIGRATION)', 'fakeApply(MIGRATION)'),
                         ("class RendezvousConsentMySqlIT {", "class RendezvousConsentMySqlIT extends H2Base {")):
            self.assertTrue(gate.binding_problems(self.workflow, self.script, self.source.replace(old, new)))


if __name__ == "__main__": unittest.main()

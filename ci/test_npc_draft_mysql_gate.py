import hashlib
import json
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("npc_draft_mysql_gate", ROOT / "ci/npc_draft_mysql_gate.py")
gate = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(gate)
COMMIT, RUN = "a" * 40, "b" * 32


def report():
    root = ET.Element("testsuite", name=gate.SUITE, tests="28", failures="0", errors="0", skipped="0")
    for i, method in enumerate(sorted(gate.METHODS)):
        case = ET.SubElement(root, "testcase", classname=gate.SUITE, name=method, time="0.1")
        ET.SubElement(case, "system-out").text = (
            "NPC_DRAFT_MYSQL_EVIDENCE METHOD=" + method + " VERSION=8.0.45 SCHEMA=cy_module_city_npc_draft_"
            + format(i, "032x") + " MIGRATION_SHA256=" + gate.MIGRATION_SHA + " FIXTURE_SHA256=" + gate.FIXTURE_SHA + " COMMIT=" + COMMIT
            + " RUN=" + RUN + " SCRATCH_DROPPED=true\n")
    return root


class NpcDraftMySqlProofTest(unittest.TestCase):
    def check(self, root, accepted=False, extra=None):
        with tempfile.TemporaryDirectory() as directory:
            ET.ElementTree(root).write(Path(directory) / ("TEST-" + gate.SUITE + ".xml"))
            if extra is not None:
                ET.ElementTree(extra).write(Path(directory) / "TEST-duplicate.xml")
            if accepted:
                self.assertEqual(28, gate.validate(directory, COMMIT, RUN)["tests"])
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
        root = report(); root.set("name", "fake.MerchantNpcKnowledgeDraftMySqlIT"); self.check(root)
        root = report(); root.findall("testcase")[0].set("classname", "NonCashRewardPersistenceTest"); self.check(root)

    def test_rejects_duplicate_suite(self):
        self.check(report(), extra=report())

    def test_rejects_skips_failures_errors_and_short_counts(self):
        for key, value in (("tests", "27"), ("tests", "29"), ("skipped", "1"), ("errors", "1"), ("failures", "1")):
            root = report(); root.set(key, value); self.check(root)

    def test_rejects_retry_and_hidden_failure_even_if_summary_claims_green(self):
        for tag in ("flakyFailure", "flakyError", "rerunFailure", "rerunError", "skipped", "failure", "error"):
            root = report(); ET.SubElement(root.find("testcase"), tag); self.check(root)

    def test_rejects_missing_or_duplicate_cleanup_evidence(self):
        root = report(); root.find("testcase").remove(root.find("testcase/system-out")); self.check(root)
        root = report(); output = root.find("testcase/system-out"); output.text *= 2; self.check(root)

    def test_rejects_wrong_database_ddl_commit_invocation_or_cleanup(self):
        for old, new in (("8.0.45", "H2"), (gate.MIGRATION_SHA, "0" * 64), (gate.FIXTURE_SHA, "1" * 64), (COMMIT, "c" * 40),
                         (RUN, "d" * 32), ("SCRATCH_DROPPED=true", "SCRATCH_DROPPED=false")):
            root = report(); output = root.find("testcase/system-out"); output.text = output.text.replace(old, new); self.check(root)

    def test_rejects_shared_or_business_schema(self):
        root = report(); outputs = root.findall("testcase/system-out")
        outputs[1].text = outputs[1].text.replace(format(1, "032x"), format(0, "032x")); self.check(root)
        root = report(); output = root.find("testcase/system-out"); output.text = output.text.replace("cy_module_city_npc_draft_" + format(0, "032x"), "chengyinhubdb"); self.check(root)


class NpcDraftSourceTest(unittest.TestCase):
    def test_exact_source_methods_and_no_h2_inheritance(self):
        import re
        source=(ROOT / "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/npc/MerchantNpcKnowledgeDraftMySqlIT.java").read_text()
        methods=re.findall(r"@Test\s+void\s+(\w+)\(",source)
        self.assertEqual(len(gate.METHODS),len(methods)); self.assertEqual(gate.METHODS,set(methods))
        fixture=ROOT / "chengyinhub-admin/src/test/resources/fixtures/merchant-npc-existing-schema.sql"
        self.assertEqual(gate.FIXTURE_SHA,hashlib.sha256(fixture.read_bytes()).hexdigest())
        self.assertIn('FIXTURE_SHA256="'+gate.FIXTURE_SHA+'"',source)
        self.assertNotIn("jdbc:h2",source); self.assertNotIn(" extends ",source)
        self.assertIn('schema.open("npc_draft")',source)
        self.assertIn('schema.apply("20261004_merchant_npc_knowledge_drafts")',source)
        self.assertIn('schema.close()',source)
        self.assertIn('DDL_SHA256="'+gate.MIGRATION_SHA+'"',source)

if __name__ == "__main__": unittest.main()

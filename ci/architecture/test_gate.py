"""Lightweight isolated negative controls. No Java, database, Docker or network."""
import copy
import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("architecture_gate", Path(__file__).with_name("gate.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)

MAPPER = "chengyinhub-system/src/main/resources/mapper/business/OrderMapper.xml"
JAVA = "chengyinhub-system/src/main/java/example/OrderService.java"
POM = "chengyinhub-system/pom.xml"
ORDER = "<update id='finish'>update orders set state=#{state} where id=#{id} and owner_id=#{owner}</update>"


def mapper(body=ORDER, namespace="example.OrderMapper"):
    return ("<mapper namespace='%s'>%s</mapper>" % (namespace, body)).encode()


def shell(root, *args):
    return gate.git(root, *args).decode().strip()


class InventoryAndDeltaTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shell(self.root, "init", "-q")
        shell(self.root, "config", "user.email", "architecture-fixture@example.invalid")
        shell(self.root, "config", "user.name", "architecture isolated fixture")
        self.write(MAPPER, mapper())
        self.write(POM, b"<project><dependencies><dependency><groupId>com.chengyinhub</groupId><artifactId>chengyinhub-common</artifactId></dependency></dependencies></project>")
        self.write(JAVA, b"package example; class OrderService {}")
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)

    def write(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def commit(self):
        shell(self.root, "add", "--all")
        shell(self.root, "commit", "-qm", "isolated fixture")
        return shell(self.root, "rev-parse", "HEAD")

    def delta(self):
        candidate = self.commit()
        changed = shell(self.root, "diff", "--name-only", "--no-renames", self.base, candidate).splitlines()
        return gate.static_findings(self.before, gate.inventory(self.root, candidate), changed)

    def test_unchanged_and_documentation_are_green(self):
        self.write("docs/example.md", b"review notes")
        self.assertEqual([], self.delta())
        record = self.before["statements"][MAPPER + "#finish"]
        self.assertEqual("LITERAL_SHAPE_ONLY", record["analysis"])
        self.assertEqual(["state"], record["column_hints"])
        self.assertEqual(["orders"], record["table_hints"])

    def test_dangerous_write_scope_removal_is_red(self):
        self.write(MAPPER, mapper(ORDER.replace(" and owner_id=#{owner}", "")))
        self.assertIn("SQL_REVIEW_REQUIRED", [f["rule"] for f in self.delta()])

    def test_new_cross_table_writer_is_red(self):
        self.write(MAPPER, mapper(ORDER.replace("update orders", "update wallet")))
        self.assertIn("NEW_TABLE_WRITER", [f["rule"] for f in self.delta()])

    def test_existing_table_new_namespace_is_red(self):
        self.write(MAPPER, mapper(namespace="foreign.OtherMapper"))
        self.assertIn("NEW_TABLE_WRITER", [f["rule"] for f in self.delta()])

    def test_dynamic_sql_remains_unknown_and_red(self):
        self.write(MAPPER, mapper("<update id='finish'>update ${table} <set><if test='state != null'>state=#{state}</if></set> where id=#{id}</update>"))
        findings = self.delta()
        self.assertTrue(any(f["rule"] == "SQL_REVIEW_REQUIRED" and f["detail"] == "UNKNOWN" for f in findings))

    def test_fragment_change_invalidates_dependent_statement(self):
        self.write(MAPPER, mapper("<sql id='scope'>and owner_id=#{owner}</sql><update id='finish'>update orders set state=#{state} where id=#{id} <include refid='scope'/></update>"))
        ref = self.commit()
        before = gate.inventory(self.root, ref)
        self.write(MAPPER, mapper("<sql id='scope'>and 1=1</sql><update id='finish'>update orders set state=#{state} where id=#{id} <include refid='scope'/></update>"))
        after = gate.inventory(self.root, self.commit())
        self.assertNotEqual(before["statements"], after["statements"])

    def test_cross_namespace_transitive_fragment_is_fingerprinted(self):
        other = MAPPER.replace("OrderMapper", "ScopeMapper")
        self.write(other, mapper("<sql id='outer'><include refid='inner'/></sql><sql id='inner'>and owner_id=#{owner}</sql>", "example.ScopeMapper"))
        self.write(MAPPER, mapper("<update id='finish'>update orders set state=#{state} where id=#{id} <include refid='example.ScopeMapper.outer'/></update>"))
        before = gate.inventory(self.root, self.commit())
        self.write(other, mapper("<sql id='outer'><include refid='inner'/></sql><sql id='inner'>and 1=1</sql>", "example.ScopeMapper"))
        after = gate.inventory(self.root, self.commit())
        self.assertNotEqual(before["statements"], after["statements"])

    def test_unresolved_cycle_and_entities_fail_closed(self):
        node = gate.xml(mapper("<sql id='loop'><include refid='loop'/></sql><update id='finish'><include refid='loop'/></update>"))
        fragments = {"example.OrderMapper.loop": node.find("sql")}
        self.assertEqual("UNKNOWN", gate.sql_shape(node.find("update"), fragments, node.get("namespace"))["analysis"])
        self.assertRaises(gate.GateError, gate.xml, b"<!DOCTYPE mapper [<!ENTITY x 'wallet'>]><mapper>&x;</mapper>")
        self.assertRaises(gate.GateError, gate.xml, b"<mapper>")

    def test_dynamic_refid_fragment_only_change_cannot_escape_review(self):
        content = "<sql id='scope'>and owner_id=#{owner}</sql><update id='finish'>update orders set state=#{state} where id=#{id} <include refid='${fragment}'/></update>"
        self.write(MAPPER, mapper(content))
        ref = self.commit()
        before = gate.inventory(self.root, ref)
        self.write(MAPPER, mapper(content.replace("and owner_id=#{owner}", "and 1=1")))
        after = gate.inventory(self.root, self.commit())
        self.assertEqual(before["statements"], after["statements"], "dynamic target is intentionally unresolved")
        findings = gate.static_findings(before, after, [MAPPER])
        self.assertIn("MAPPER_FILE_REVIEW_REQUIRED", [f["rule"] for f in findings])

    def test_new_dependency_version_or_scope_is_red(self):
        self.write(POM, (self.root / POM).read_bytes().replace(b"</dependency>", b"<version>NEW</version></dependency>"))
        self.assertIn("DEPENDENCY_EXPANSION", [f["rule"] for f in self.delta()])

    def test_dependency_removal_does_not_expand_but_requires_build_review(self):
        self.write(POM, b"<project/>")
        rules = [f["rule"] for f in self.delta()]
        self.assertNotIn("DEPENDENCY_EXPANSION", rules)
        self.assertIn("BUILD_REVIEW_REQUIRED", rules)

    def test_query_disguised_write_is_reviewed(self):
        self.write(MAPPER, mapper("<select id='hidden' affectData='true'>delete from orders</select>"))
        self.assertIn("SQL_REVIEW_REQUIRED", [f["rule"] for f in self.delta()])

    def test_removed_recovery_statement_is_red(self):
        self.write(MAPPER, mapper(""))
        self.assertIn("SQL_REMOVAL_REVIEW_REQUIRED", [f["rule"] for f in self.delta()])

    def test_unknown_java_and_side_effect_are_red(self):
        self.write(JAVA, b"class OrderService { void afterCommit() { external.charge(); } }")
        self.assertIn("PRODUCTION_REVIEW_REQUIRED", [f["rule"] for f in self.delta()])

    def test_shared_gate_and_test_skip_change_are_red(self):
        self.write("ci/example.py", b"pass")
        self.write("chengyinhub-admin/src/test/java/example/ATest.java", b"@Disabled class ATest {}")
        rules = [f["rule"] for f in self.delta()]
        self.assertIn("TEST_GATE_REVIEW_REQUIRED", rules)
        self.assertIn("TEST_SOURCE_REVIEW_REQUIRED", rules)

    def test_unknown_build_file_is_red(self):
        self.write("other/pom.xml", b"<project/>")
        self.assertIn("UNKNOWN_BUILD_FILE", [f["rule"] for f in self.delta()])

    def test_migration_and_maven_flags_cannot_bypass_gate(self):
        self.write("chengyinhub-admin/sql/migration_new.sql", b"update wallet set balance=0;")
        self.write(".mvn/maven.config", b"-DskipTests")
        rules = [f["rule"] for f in self.delta()]
        self.assertIn("SQL_SCRIPT_REVIEW_REQUIRED", rules)
        self.assertIn("BUILD_REVIEW_REQUIRED", rules)

    def prepare_policy(self):
        policy = self.root / "ci/architecture"
        policy.mkdir(parents=True)
        (policy / "baseline.json").write_bytes(gate.encode_inventory(self.before))
        (policy / "gate.py").write_bytes(b"# trusted fixture")
        candidate = self.commit()
        # Trusted files are kept outside candidate to exercise PR policy changes.
        trusted = self.root / ".git/trusted/ci/architecture"
        trusted.mkdir(parents=True)
        for file in policy.iterdir():
            (trusted / file.name).write_bytes(file.read_bytes())
        return trusted, candidate

    def source_check(self, trusted, candidate):
        with patch.object(gate, "BASE", self.base), patch.object(gate, "HERE", trusted), patch.object(gate, "REPO", trusted.parent.parent):
            return gate.check_source(self.root, candidate, trusted / "baseline.json")

    def test_clean_exact_candidate_and_trusted_policy_are_green(self):
        trusted, candidate = self.prepare_policy()
        self.assertEqual([], self.source_check(trusted, candidate)["findings"])

    def test_baseline_laundering_is_red(self):
        trusted, candidate = self.prepare_policy()
        data = json.loads((trusted / "baseline.json").read_text())
        data["statements"].clear()
        (trusted / "baseline.json").write_bytes(gate.encode_inventory(data))
        with self.assertRaisesRegex(gate.GateError, "BASELINE_LAUNDERING"):
            self.source_check(trusted, candidate)

    def test_candidate_cannot_edit_or_add_policy_exception(self):
        trusted, _ = self.prepare_policy()
        self.write("ci/architecture/allow-all.json", b"{}")
        with self.assertRaisesRegex(gate.GateError, "POLICY_CHANGE"):
            self.source_check(trusted, self.commit())
        (self.root / "ci/architecture/allow-all.json").unlink()
        self.write("ci/architecture/gate.py", b"# bypass")
        with self.assertRaisesRegex(gate.GateError, "POLICY_CHANGE"):
            self.source_check(trusted, self.commit())

    def test_dirty_and_mismatched_candidate_are_red(self):
        trusted, candidate = self.prepare_policy()
        with self.assertRaisesRegex(gate.GateError, "HEAD mismatch"):
            self.source_check(trusted, self.base)
        self.write(JAVA, b"dirty mutation")
        with self.assertRaisesRegex(gate.GateError, "dirty"):
            self.source_check(trusted, candidate)

    def test_non_descendant_candidate_is_red(self):
        trusted, _ = self.prepare_policy()
        shell(self.root, "checkout", "--orphan", "unrelated", "-q")
        unrelated = self.commit()
        with self.assertRaisesRegex(gate.GateError, "merge-base"):
            self.source_check(trusted, unrelated)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.reports = Path(self.temp.name)
        self.plan = {"candidate_sha": "a" * 40, "run_id": "123", "run_attempt": "1",
                     "suites": {"example.RequiredIT": 2}}
        self.write_report()

    def write_report(self, name="example.RequiredIT", tests=2, skipped=0, failures=0, errors=0, cases=None, filename="TEST-example.RequiredIT.xml"):
        if cases is None:
            cases = "".join("<testcase classname='%s' name='case%d'/>" % (name, n) for n in range(tests))
        text = "<testsuite name='%s' tests='%d' skipped='%d' failures='%d' errors='%d'>%s</testsuite>" % (name, tests, skipped, failures, errors, cases)
        (self.reports / filename).write_text(text)

    def receipt(self):
        return {**copy.deepcopy(self.plan), "maven_exit_code": 0,
                "reports_sha256": {p.name: gate.digest(p.read_bytes()) for p in self.reports.glob("TEST-*.xml")}}

    def check(self, receipt=None):
        return gate.readback(self.plan, receipt if receipt is not None else self.receipt(), self.reports)

    def test_exact_full_name_cases_and_candidate_are_green(self):
        self.assertEqual("REPORTS_CONSISTENT", self.check()["verdict"])

    def test_missing_report_and_empty_plan_are_red(self):
        (self.reports / "TEST-example.RequiredIT.xml").unlink()
        self.assertRaises(gate.GateError, self.check)
        self.plan["suites"] = {}
        self.assertRaises(gate.GateError, self.check)

    def test_skip_zero_failure_error_counts_are_red(self):
        for kwargs in ({"skipped": 1}, {"tests": 0}, {"failures": 1}, {"errors": 1}, {"tests": 1}):
            with self.subTest(kwargs=kwargs):
                self.write_report(**kwargs)
                self.assertRaises(gate.GateError, self.check)

    def test_candidate_run_attempt_and_maven_mismatch_are_red(self):
        for key, value in (("candidate_sha", "b" * 40), ("run_id", "124"), ("run_attempt", "2"), ("maven_exit_code", 1)):
            with self.subTest(key=key):
                receipt = self.receipt()
                receipt[key] = value
                self.assertRaises(gate.GateError, self.check, receipt)

    def test_full_name_collision_duplicate_and_foreign_suites_are_red(self):
        self.write_report(name="other.RequiredIT")
        self.assertRaises(gate.GateError, self.check)
        self.write_report()
        self.write_report(filename="TEST-duplicate.xml")
        self.assertRaises(gate.GateError, self.check)
        self.write_report(name="example.OtherIT", filename="TEST-duplicate.xml")
        self.assertRaises(gate.GateError, self.check)

    def test_report_mutation_without_new_attestation_is_red(self):
        receipt = self.receipt()
        self.write_report(tests=1)
        self.assertRaises(gate.GateError, self.check, receipt)

    def test_missing_duplicate_unnamed_foreign_cases_are_red(self):
        for cases in ("", "<testcase classname='example.RequiredIT' name='a'/>" * 2,
                      "<testcase classname='example.RequiredIT'/><testcase classname='example.RequiredIT' name='b'/>",
                      "<testcase classname='other.RequiredIT' name='a'/><testcase classname='example.RequiredIT' name='b'/>"):
            with self.subTest(cases=cases):
                self.write_report(cases=cases)
                self.assertRaises(gate.GateError, self.check)

    def test_hidden_skipped_failure_error_flaky_cases_are_red(self):
        for tag in gate.BAD_CASE:
            with self.subTest(tag=tag):
                self.write_report(cases="<testcase classname='example.RequiredIT' name='a'><%s/></testcase><testcase classname='example.RequiredIT' name='b'/>" % tag)
                self.assertRaises(gate.GateError, self.check)

    def test_malformed_xml_and_negative_counts_are_red(self):
        (self.reports / "TEST-example.RequiredIT.xml").write_text("<bad")
        self.assertRaises(ValueError, self.check)
        self.write_report(tests=-1)
        self.assertRaises(gate.GateError, self.check)

    def test_symlink_and_unplanned_reports_are_red(self):
        target = self.reports / "TEST-example.RequiredIT.xml"
        data = target.read_bytes()
        target.unlink()
        (self.reports / "other.xml").write_bytes(data)
        target.symlink_to("other.xml")
        self.assertRaises(gate.GateError, self.check)

    def test_invalid_plan_and_receipt_schema_are_red(self):
        for suites in ({"RequiredIT": 2}, {"example.RequiredIT": True}, {"example.RequiredIT": 0}):
            self.plan["suites"] = suites
            self.assertRaises(gate.GateError, self.check)
        self.plan["suites"] = {"example.RequiredIT": 2}
        receipt = self.receipt()
        receipt["unexpected"] = True
        self.assertRaises(gate.GateError, self.check, receipt)

    def test_cli_returns_failure_on_forged_success(self):
        plan = self.reports / "plan.json"
        receipt = self.reports / "receipt.json"
        plan.write_text(json.dumps(self.plan))
        forged = self.receipt()
        forged["candidate_sha"] = "b" * 40
        receipt.write_text(json.dumps(forged))
        result = subprocess.run([sys.executable, str(Path(gate.__file__)), "evidence", "--plan", str(plan),
                                 "--receipt", str(receipt), "--reports", str(self.reports)], capture_output=True)
        self.assertEqual(1, result.returncode)
        self.assertIn(b"CANDIDATE_OR_RUN_MISMATCH", result.stderr)

    def test_cli_returns_success_only_with_consistent_reports(self):
        plan = self.reports / "plan.json"
        receipt = self.reports / "receipt.json"
        plan.write_text(json.dumps(self.plan))
        receipt.write_text(json.dumps(self.receipt()))
        result = subprocess.run([sys.executable, str(Path(gate.__file__)), "evidence", "--plan", str(plan),
                                 "--receipt", str(receipt), "--reports", str(self.reports)], capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("REPORTS_CONSISTENT", json.loads(result.stdout)["verdict"])


class ExternalReviewTest(unittest.TestCase):
    def setUp(self):
        self.result = {"candidate_sha": "a" * 40, "verdict": "STATIC_ONLY",
                       "findings": [{"rule": "SQL_REVIEW_REQUIRED", "key": MAPPER + "#finish", "detail": "UNKNOWN"}]}
        self.evidence = {"candidate_sha": "a" * 40, "verdict": "REPORTS_CONSISTENT",
                         "suites": {"example.RequiredIT": 2}}
        self.review = {"schema": 1, "candidate_sha": "a" * 40,
                       "findings_sha256": gate.digest(gate.encode(self.result["findings"])),
                       "reviewer": "isolated fixture reviewer, not a real approval",
                       "decision_ref": "isolated fixture decision, not production evidence",
                       "required_suites": {"example.RequiredIT": 2},
                       "contracts": [{"rule": "SQL_REVIEW_REQUIRED", "key": MAPPER + "#finish",
                                      **{key: "fixture code/test reference" for key in
                                         ("scope", "identity", "intent", "version", "recovery", "atomicity", "reject_zero_effect")}}]}

    def test_exact_review_and_verified_test_plan_are_green(self):
        result = gate.reviewed_result(self.result, self.review, self.evidence)
        self.assertEqual("REVIEWED_STATIC_WITH_REPORTS", result["verdict"])
        self.assertEqual(self.result["findings"], result["findings"], "review must retain original findings")

    def test_stale_candidate_and_changed_findings_are_red(self):
        self.review["candidate_sha"] = "b" * 40
        self.assertRaises(gate.GateError, gate.reviewed_result, self.result, self.review, self.evidence)
        self.review["candidate_sha"] = "a" * 40
        self.result["findings"][0]["detail"] = "changed after review"
        self.assertRaises(gate.GateError, gate.reviewed_result, self.result, self.review, self.evidence)

    def test_missing_recovery_or_empty_contract_is_red(self):
        del self.review["contracts"][0]["recovery"]
        self.assertRaises(gate.GateError, gate.reviewed_result, self.result, self.review, self.evidence)
        self.review["contracts"][0]["recovery"] = " "
        self.assertRaises(gate.GateError, gate.reviewed_result, self.result, self.review, self.evidence)

    def test_missing_extra_and_duplicate_contracts_are_red(self):
        for contracts in ([], self.review["contracts"] * 2,
                          [{**self.review["contracts"][0], "key": "foreign"}]):
            with self.subTest(contracts=contracts):
                review = {**self.review, "contracts": contracts}
                self.assertRaises(gate.GateError, gate.reviewed_result, self.result, review, self.evidence)

    def test_wrong_suite_plan_or_unverified_evidence_is_red(self):
        self.review["required_suites"] = {"example.RequiredIT": 1}
        self.assertRaises(gate.GateError, gate.reviewed_result, self.result, self.review, self.evidence)
        self.review["required_suites"] = self.evidence["suites"]
        self.evidence["verdict"] = "CLAIMED_GREEN"
        self.assertRaises(gate.GateError, gate.reviewed_result, self.result, self.review, self.evidence)

    def test_approval_cannot_waive_unapproved_dependency(self):
        self.result["findings"][0]["rule"] = "DEPENDENCY_EXPANSION"
        self.review["findings_sha256"] = gate.digest(gate.encode(self.result["findings"]))
        with self.assertRaisesRegex(gate.GateError, "cannot waive"):
            gate.reviewed_result(self.result, self.review, self.evidence)

    def test_missing_reviewer_reference_and_unknown_schema_are_red(self):
        for key, value in (("reviewer", ""), ("decision_ref", ""), ("schema", 2)):
            with self.subTest(key=key):
                self.assertRaises(gate.GateError, gate.reviewed_result, self.result, {**self.review, key: value}, self.evidence)

    def test_cli_rejects_candidate_local_review_and_incomplete_review_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            candidate = Path(folder) / "candidate"
            candidate.mkdir()
            for arguments, message in ((["--review", str(candidate / "review.json")], "together"),
                                       ([flag for key in ("review", "plan", "receipt", "reports")
                                         for flag in ("--" + key, str(candidate / key))], "outside")):
                stderr = io.StringIO()
                with patch.object(gate, "check_source", return_value=self.result), patch.object(sys, "argv", [
                    "gate.py", "source", "--source", str(candidate), "--candidate", "a" * 40, *arguments
                ]), contextlib.redirect_stderr(stderr):
                    self.assertEqual(1, gate.main())
                self.assertIn(message, stderr.getvalue())


class RegisteredReferencesTest(unittest.TestCase):
    def test_command_checklist_references_real_baseline_sources(self):
        registry = json.loads((gate.HERE / "commands.json").read_text())
        self.assertEqual(gate.BASE, registry["source_baseline"])
        for command in registry["commands"]:
            paths = [command["entry"], *command["callers"], *command["persistent_sources"], *command["existing_test_sources"]]
            for path in paths:
                with self.subTest(command=command["id"], path=path):
                    self.assertTrue(gate.blob(gate.REPO, gate.BASE, path))
            self.assertIn(command["symbol"].encode(), gate.blob(gate.REPO, gate.BASE, command["entry"]))


if __name__ == "__main__":
    unittest.main()

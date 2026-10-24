"""Lightweight isolated negative controls. No Java, database, Docker or network."""
import copy
from contextlib import redirect_stdout
import io
from contextlib import contextmanager
import importlib.util
import json
import os
import re
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
        self.write(MAPPER.replace("OrderMapper", "WalletMapper"), mapper("<update id='write'>update wallet set balance=#{balance} where id=#{id}</update>", "example.WalletMapper"))
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
        details = {}
        current = gate.inventory(self.root, candidate, details)
        before_details = {}
        gate.inventory(self.root, self.before["base_sha"], before_details)
        blockers, self.review = gate.static_findings(self.before, current, changed, details, before_details)
        return blockers

    def test_unchanged_and_documentation_are_green(self):
        self.write("docs/example.md", b"review notes")
        self.assertEqual([], self.delta())
        record = self.before["statements"][MAPPER + "#finish"]
        self.assertEqual("LITERAL_SHAPE_ONLY", record["analysis"])
        self.assertEqual(["state"], record["column_hints"])
        self.assertEqual(["orders"], record["table_hints"])

    def test_dangerous_write_scope_removal_is_red(self):
        self.write(MAPPER, mapper(ORDER.split(" where ")[0] + "</update>"))
        self.assertIn("UNSCOPED_WRITE", [f["rule"] for f in self.delta()])

    def test_new_cross_table_writer_is_red(self):
        self.write(MAPPER, mapper(ORDER.replace("update orders", "update wallet")))
        self.assertIn("NEW_TABLE_WRITER", [f["rule"] for f in self.delta()])

    def test_existing_table_new_namespace_is_red(self):
        self.write(MAPPER, mapper(namespace="foreign.OtherMapper"))
        self.assertIn("NEW_TABLE_WRITER", [f["rule"] for f in self.delta()])

    def test_dynamic_sql_is_reviewable_unknown_not_a_violation(self):
        self.write(MAPPER, mapper("<update id='finish'>update ${table} <set><if test='state != null'>state=#{state}</if></set> where id=#{id}</update>"))
        self.assertEqual([], self.delta())
        self.assertTrue(any(item["reason"] == "UNKNOWN" for item in self.review))

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
        details = {}
        after = gate.inventory(self.root, shell(self.root, "rev-parse", "HEAD"), details)
        before_details = {}
        gate.inventory(self.root, ref, before_details)
        blockers, review = gate.static_findings(before, after, [MAPPER], details, before_details)
        self.assertEqual([], blockers)
        self.assertTrue(any(item["key"] == MAPPER for item in review))

    def test_new_dependency_coordinate_is_red(self):
        self.write(POM, (self.root / POM).read_bytes().replace(b"chengyinhub-common", b"new-library"))
        self.assertIn("DEPENDENCY_EXPANSION", [f["rule"] for f in self.delta()])

    def test_version_formatting_changes_do_not_introduce_a_dependency(self):
        self.write(POM, (self.root / POM).read_bytes().replace(b"</dependency>", b"<version>NEW</version></dependency>"))
        self.assertEqual([], self.delta())
        self.assertTrue(self.review)

    def test_scope_expansion_is_red(self):
        self.write(POM, (self.root / POM).read_bytes().replace(b"</dependency>", b"<scope>test</scope></dependency>"))
        self.before = gate.inventory(self.root, self.commit())
        self.write(POM, (self.root / POM).read_bytes().replace(b"<scope>test</scope>", b"<scope>compile</scope>"))
        self.assertIn("DEPENDENCY_EXPANSION", [f["rule"] for f in self.delta()])

    def test_scope_narrowing_is_not_a_violation(self):
        self.write(POM, (self.root / POM).read_bytes().replace(b"</dependency>", b"<scope>runtime</scope></dependency>"))
        self.assertEqual([], self.delta())

    def test_scoped_write_change_is_reviewable_not_blocked(self):
        self.write(MAPPER, mapper(ORDER.replace(" and owner_id=#{owner}", "")))
        self.assertEqual([], self.delta())
        self.assertTrue(self.review, "identity/CAS/authorization still need normal PR review")

    def test_unconditional_where_is_red_but_quoted_where_does_not_hide_broad_write(self):
        details = {}
        self.write(MAPPER, mapper("<update id='finish'>update orders set state='where' where 1=1</update>"))
        self.assertIn("UNSCOPED_WRITE", [item["rule"] for item in self.delta()])
        self.write(MAPPER, mapper("<update id='finish'>update orders set state='where'</update>"))
        self.assertIn("UNSCOPED_WRITE", [item["rule"] for item in self.delta()])

    def test_dependency_removal_does_not_expand_but_requires_build_review(self):
        self.write(POM, b"<project/>")
        rules = [f["rule"] for f in self.delta()]
        self.assertNotIn("DEPENDENCY_EXPANSION", rules)
        self.assertTrue(any(item["key"] == POM for item in self.review))

    def test_query_disguised_write_is_reviewed(self):
        self.write(MAPPER, mapper("<select id='hidden' affectData='true'>delete from orders</select>"))
        self.assertIn("UNSCOPED_WRITE", [item["rule"] for item in self.delta()])
        self.assertTrue(self.review)

    def test_removed_recovery_statement_is_review_item(self):
        self.write(MAPPER, mapper(""))
        self.assertEqual([], self.delta())
        self.assertTrue(any(item["reason"].startswith("SQL_REMOVED") for item in self.review))

    def test_java_change_alone_is_not_a_violation(self):
        self.write(JAVA, b"class OrderService { void afterCommit() { external.charge(); } }")
        self.assertEqual([], self.delta())

    def test_test_and_workflow_changes_do_not_require_java_receipt(self):
        self.write("ci/example.py", b"pass")
        self.write("chengyinhub-admin/src/test/java/example/ATest.java", b"@Disabled class ATest {}")
        self.assertEqual([], self.delta())

    def test_unknown_build_file_is_review_item(self):
        self.write("other/pom.xml", b"<project/>")
        self.assertEqual([], self.delta())
        self.assertTrue(any(item["key"] == "other/pom.xml" for item in self.review))

    def test_migration_and_maven_flags_cannot_bypass_gate(self):
        self.write("chengyinhub-admin/sql/migration_new.sql", b"update wallet set balance=0;")
        self.write(".mvn/maven.config", b"-DskipTests")
        self.assertEqual([], self.delta())
        self.assertEqual(2, len(self.review))

    def prepare_policy(self):
        policy = self.root / "ci/architecture"
        policy.mkdir(parents=True)
        (policy / "baseline.json").write_bytes(gate.encode_inventory(self.before))
        details = {}
        gate.inventory(self.root, self.before["base_sha"], details)
        (policy / "locking-select-baseline.proposal.json").write_bytes(
            gate.encode_inventory(gate.locking_debt_proposal(self.before, details)))
        (policy / "gate.py").write_bytes((gate.HERE / "gate.py").read_bytes())
        for name in ("owners.json", "commands.json", "collect_jdeps.py"):
            (policy / name).write_bytes((gate.HERE / name).read_bytes())
        candidate = self.commit()
        # Trusted files are kept outside candidate to exercise PR policy changes.
        trusted = self.root / ".git/trusted/ci/architecture"
        trusted.mkdir(parents=True)
        for file in policy.iterdir():
            (trusted / file.name).write_bytes(file.read_bytes())
        return trusted, candidate

    def source_check(self, trusted, candidate, compare=None, context=None):
        compare = compare or self.base
        context = context or {"event": "push", "checkout_sha": candidate,
                              "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin"},
                                          "before": compare, "after": candidate}}
        with patch.object(gate, "BASE", self.base), patch.object(gate, "HERE", trusted), patch.object(gate, "REPO", trusted.parent.parent):
            return gate.check_source(self.root, candidate, compare, context, trusted / "baseline.json")

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

    def test_candidate_cannot_edit_frozen_baseline_or_verifier(self):
        trusted, _ = self.prepare_policy()
        self.write("ci/architecture/baseline.json", b"{}")
        with self.assertRaisesRegex(gate.GateError, "POLICY_CHANGE"):
            self.source_check(trusted, self.commit())
        self.write("ci/architecture/baseline.json", (trusted / "baseline.json").read_bytes())
        self.write("ci/architecture/gate.py", b"# bypass")
        with self.assertRaisesRegex(gate.GateError, "POLICY_CHANGE"):
            self.source_check(trusted, self.commit())

    def test_ordinary_gate_documentation_and_test_edits_do_not_block(self):
        trusted, prior = self.prepare_policy()
        self.write("ci/architecture/README.md", b"review documentation")
        self.write("ci/architecture/test_new_rule.py", b"# proposed negative control")
        self.assertEqual([], self.source_check(trusted, self.commit(), prior)["findings"])

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

    def test_accepted_history_is_not_reported_again_and_baseline_is_unchanged(self):
        trusted, prior = self.prepare_policy()
        baseline_bytes = (trusted / "baseline.json").read_bytes()
        self.write(MAPPER, mapper(ORDER.replace("update orders", "update wallet")))
        accepted = self.commit()
        self.assertTrue(self.source_check(trusted, accepted, prior)["findings"])
        self.write("docs/next-small-change.md", b"new normal PR")
        current = self.commit()
        result = self.source_check(trusted, current, accepted)
        self.assertEqual([], result["findings"])
        self.assertEqual([], result["review_items"])
        self.assertEqual(["docs/next-small-change.md"], result["changed_files"])
        self.assertEqual(baseline_bytes, (trusted / "baseline.json").read_bytes())

    def test_pr_requires_actual_merge_base_not_any_ancestor(self):
        self.write("docs/target.md", b"target")
        target = self.commit()
        self.write("docs/pr.md", b"head")
        head = self.commit()
        context = {"event": "pull_request", "checkout_sha": head,
                   "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin"},
                               "pull_request": {"head": {"sha": head}, "base": {"sha": target}}}}
        self.assertEqual(target, gate.comparison_base(self.root, head, target, context))
        with self.assertRaisesRegex(gate.GateError, "COMPARISON_BASE_MISMATCH"):
            gate.comparison_base(self.root, head, self.base, context)
        with self.assertRaisesRegex(gate.GateError, "COMPARISON_BASE_MISMATCH"):
            gate.comparison_base(self.root, head, head, context)
        context["payload"]["pull_request"]["head"]["sha"] = target
        self.assertRaises(gate.GateError, gate.comparison_base, self.root, head, target, context)

    def test_real_synthetic_merge_context_is_bound_to_exact_base_and_head(self):
        original_branch = shell(self.root, "branch", "--show-current")
        shell(self.root, "branch", "fixture-pr", self.base)
        self.write("docs/base-advance.md", b"base advanced")
        target = self.commit()
        shell(self.root, "checkout", "fixture-pr", "-q")
        self.write("docs/pr-change.md", b"PR changed")
        head = self.commit()
        shell(self.root, "checkout", original_branch, "-q")
        shell(self.root, "merge", "--no-ff", "fixture-pr", "-m", "synthetic fixture")
        merge = shell(self.root, "rev-parse", "HEAD")
        context = {"event": "pull_request", "checkout_sha": merge,
                   "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin"},
                               "pull_request": {"head": {"sha": head}, "base": {"sha": target}}}}
        self.assertEqual(self.base, gate.comparison_base(self.root, head, self.base, context))
        context["checkout_sha"] = target
        with self.assertRaisesRegex(gate.GateError, "synthetic merge"):
            gate.comparison_base(self.root, head, self.base, context)

    def manual_context(self, candidate):
        return {"event": "workflow_dispatch", "checkout_sha": candidate,
                "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin",
                                           "default_branch": "master"}}}

    @contextmanager
    def manual_origin(self, tip):
        # Only replace network transport, not refs, fetch, merge-base or checker.
        # The local bare repo models the trusted public origin's actual tip.
        original_git = gate.git
        remote = self.root / ".git/manual-origin.git"
        shell(self.root, "clone", "--bare", "--quiet", str(self.root), str(remote))
        shell(remote, "update-ref", "refs/heads/master", tip)
        shell(self.root, "remote", "add", "origin", "https://github.com/liboyang42-cpu/hengyin.git")

        def transport(root, *args):
            if args[0] == "fetch":
                self.assertEqual(("fetch", "--no-tags", "--no-recurse-submodules", "origin",
                                  "+refs/heads/master:refs/remotes/origin/master"), args)
                return original_git(root, *args[:3], str(remote), *args[4:])
            return original_git(root, *args)
        with patch.object(gate, "git", side_effect=transport):
            yield

    def test_manual_first_feature_violation_then_docs_is_still_red(self):
        trusted, trunk = self.prepare_policy()
        self.write(MAPPER, mapper(ORDER.replace("update orders", "update wallet")))
        first = self.commit()
        self.write("docs/second.md", b"docs-only second feature commit")
        candidate = self.commit()
        context = self.manual_context(candidate)
        with self.manual_origin(trunk):
            self.assertEqual(trunk, gate.manual_base(self.root, candidate, context))
            result = self.source_check(trusted, candidate, trunk, context)
            self.assertIn("NEW_TABLE_WRITER", [f["rule"] for f in result["findings"]])
            self.assertIn(MAPPER, result["changed_files"])
            with self.assertRaisesRegex(gate.GateError, "COMPARISON_BASE_MISMATCH"):
                self.source_check(trusted, candidate, first, context)

    def test_manual_already_merged_history_is_not_reported_again(self):
        trusted, _ = self.prepare_policy()
        baseline = (trusted / "baseline.json").read_bytes()
        self.write(MAPPER, mapper(ORDER.replace("update orders", "update wallet")))
        accepted = self.commit()
        self.write("docs/next.md", b"normal small change after reviewed trunk")
        candidate = self.commit()
        with self.manual_origin(accepted):
            result = self.source_check(trusted, candidate, accepted, self.manual_context(candidate))
        self.assertEqual([], result["findings"])
        self.assertEqual([], result["review_items"])
        self.assertEqual(["docs/next.md"], result["changed_files"])
        self.assertEqual(baseline, (trusted / "baseline.json").read_bytes())

    def test_manual_diverged_trunk_uses_merge_base_not_tip_or_parent(self):
        branch = shell(self.root, "branch", "--show-current")
        shell(self.root, "branch", "feature", self.base)
        self.write("docs/trunk.md", b"trunk advanced separately")
        trunk = self.commit()
        shell(self.root, "checkout", "feature", "-q")
        self.write("docs/first.md", b"first feature commit")
        first = self.commit()
        self.write("docs/second.md", b"second feature commit")
        candidate = self.commit()
        context = self.manual_context(candidate)
        with self.manual_origin(trunk):
            self.assertEqual(self.base, gate.comparison_base(self.root, candidate, self.base, context))
            for wrong in (trunk, first, candidate):
                self.assertRaises(gate.GateError, gate.comparison_base, self.root, candidate, wrong, context)
        shell(self.root, "checkout", branch, "-q")

    def test_manual_missing_or_forged_default_branch_and_origin_are_red(self):
        context = self.manual_context(self.base)
        for invalid in (None, "", "../forged", "master:refs/heads/forged", "-master"):
            with self.subTest(branch=invalid):
                context["payload"]["repository"]["default_branch"] = invalid
                self.assertRaises(gate.GateError, gate.manual_base, self.root, self.base, context)
        context = self.manual_context(self.base)
        with self.manual_origin(self.base):
            shell(self.root, "remote", "set-url", "origin", "https://example.invalid/forged.git")
            with self.assertRaisesRegex(gate.GateError, "fixed public repository"):
                gate.manual_base(self.root, self.base, context)

    def test_manual_fetch_failure_cannot_use_stale_tracking_ref(self):
        shell(self.root, "remote", "add", "origin", "https://github.com/liboyang42-cpu/hengyin.git")
        shell(self.root, "update-ref", "refs/remotes/origin/master", self.base)
        original_git = gate.git
        def offline(root, *args):
            if args[0] == "fetch":
                raise gate.GateError("fixture fetch unavailable")
            return original_git(root, *args)
        with patch.object(gate, "git", side_effect=offline):
            with self.assertRaisesRegex(gate.GateError, "fetch unavailable"):
                gate.manual_base(self.root, self.base, self.manual_context(self.base))

    def test_manual_fetched_ref_mismatch_and_ambiguous_merge_base_are_red(self):
        self.write("docs/next.md", b"next")
        candidate = self.commit()
        with self.manual_origin(candidate):
            transport = gate.git
            def mismatch(root, *args):
                if args == ("rev-parse", "--verify", "FETCH_HEAD^{commit}"):
                    return (self.base + "\n").encode()
                return transport(root, *args)
            with patch.object(gate, "git", side_effect=mismatch):
                with self.assertRaisesRegex(gate.GateError, "freshly fetched origin"):
                    gate.manual_base(self.root, candidate, self.manual_context(candidate))
            def ambiguous(root, *args):
                if args[:2] == ("merge-base", "--all"):
                    return (self.base + "\n" + candidate + "\n").encode()
                return transport(root, *args)
            with patch.object(gate, "git", side_effect=ambiguous):
                with self.assertRaisesRegex(gate.GateError, "ambiguous manual"):
                    gate.manual_base(self.root, candidate, self.manual_context(candidate))

    def test_manual_candidate_must_match_real_checkout_and_repository(self):
        context = self.manual_context(self.base)
        context["checkout_sha"] = "a" * 40
        self.assertRaises(gate.GateError, gate.manual_base, self.root, self.base, context)
        context = self.manual_context(self.base)
        context["payload"]["repository"]["full_name"] = "forged/repo"
        self.assertRaises(gate.GateError, gate.manual_base, self.root, self.base, context)

    def test_push_base_is_bound_to_trusted_event(self):
        self.write("docs/first.md", b"one")
        prior = self.commit()
        self.write("docs/second.md", b"two")
        candidate = self.commit()
        context = {"event": "push", "checkout_sha": candidate,
                   "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin"},
                               "before": prior, "after": candidate}}
        self.assertEqual(prior, gate.comparison_base(self.root, candidate, prior, context))
        self.assertRaises(gate.GateError, gate.comparison_base, self.root, candidate, candidate, context)
        context["payload"]["before"] = "0" * 40
        self.assertRaises(gate.GateError, gate.comparison_base, self.root, candidate, "0" * 40, context)

    def test_missing_ci_context_and_short_compare_sha_are_rejected(self):
        self.assertRaises(gate.GateError, gate.ci_context, {})
        self.assertRaises(gate.GateError, gate.comparison_base, self.root, self.base, self.base[:8], {})


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



class BusinessOwnerPolicyTest(unittest.TestCase):
    """Real source CLI on isolated Git/event fixtures, no Java/DB/network."""
    setUp = InventoryAndDeltaTest.setUp
    write = InventoryAndDeltaTest.write
    commit = InventoryAndDeltaTest.commit
    prepare_policy = InventoryAndDeltaTest.prepare_policy
    manual_context = InventoryAndDeltaTest.manual_context
    manual_origin = InventoryAndDeltaTest.manual_origin
    facts = (("ai_quota_reservation", "AiQuotaReservationMapper"),
             ("photo_check_intent", "PhotoCheckIntentMapper"))

    def put_write(self, table, writer, sql=None, name="mutate", module="system"):
        path = "chengyinhub-%s/src/main/resources/mapper/business/%s.xml" % (module, writer)
        namespace = "com.chengyinhub.business.mapper." + writer
        sql = sql or "update %s set state=#{state} where id=#{id}" % table
        self.write(path, mapper('<update id="%s">%s</update>' % (name, sql), namespace))
        return path

    def source_cli(self, trusted, candidate, compare=None, context=None):
        compare = compare or self.base
        context = context or {"event": "push", "checkout_sha": candidate,
                              "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin"},
                                          "before": compare, "after": candidate}}
        argv = ["gate.py", "source", "--source", str(self.root), "--candidate", candidate,
                "--compare-base", compare]
        output = io.StringIO()
        check = gate.check_source
        # Same production CLI/parser/checker. Only isolated public-base/event and
        # trusted-checkout location replace real CI context; no mocked verdict.
        with patch.object(gate, "BASE", self.base), patch.object(gate, "HERE", trusted), \
             patch.object(gate, "check_source", side_effect=lambda *args: check(*args, baseline_path=trusted / "baseline.json")), \
             patch.object(gate, "ci_context", return_value=context), \
             patch.object(sys, "argv", argv), redirect_stdout(output):
            status = gate.main()
        return status, json.loads(output.getvalue())

    def assert_rule(self, result, rule):
        status, report = result
        self.assertEqual(1, status, report)
        self.assertIn(rule, [f["rule"] for f in report["findings"]])

    def test_same_owner_both_facts_are_green_and_scope_is_partial(self):
        trusted, _ = self.prepare_policy()
        for table, writer in self.facts:
            self.put_write(table, writer)
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(0, status, report)
        self.assertEqual("PARTIAL", report["business_policy"]["coverage"])
        self.assertEqual(["AI_QUOTA_RESERVATION", "PHOTO_CHECK_INTENT"], report["business_policy"]["fact_ids"])
        self.assertTrue(any("service call paths" in x for x in report["not_proven"]))

    def test_foreign_namespace_is_red_even_if_table_absent_in_compare_base(self):
        trusted, _ = self.prepare_policy()
        self.put_write("ai_quota_reservation", "ForeignMapper")
        self.assert_rule(self.source_cli(trusted, self.commit()), "UNDECLARED_WRITE_BINDING")

    def test_other_approved_business_owner_is_red(self):
        trusted, _ = self.prepare_policy()
        self.put_write("photo_check_intent", "AiQuotaReservationMapper")
        self.assert_rule(self.source_cli(trusted, self.commit()), "NEW_BUSINESS_OWNER_WRITE")

    def test_same_namespace_in_other_module_does_not_inherit_binding(self):
        trusted, _ = self.prepare_policy()
        self.put_write("ai_quota_reservation", "AiQuotaReservationMapper", module="quartz")
        self.assert_rule(self.source_cli(trusted, self.commit()), "UNDECLARED_WRITE_BINDING")

    def test_legacy_foreign_writer_is_not_relitigated_by_docs_or_same_edge_edit(self):
        self.put_write("ai_quota_reservation", "ForeignMapper")
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        trusted, accepted = self.prepare_policy()
        baseline = (trusted / "baseline.json").read_bytes()
        self.write("docs/normal.md", b"normal already reviewed history")
        self.assertEqual(0, self.source_cli(trusted, self.commit(), accepted)[0])
        self.put_write("ai_quota_reservation", "ForeignMapper", "update ai_quota_reservation set state=2 where id=#{id}")
        self.assertEqual(0, self.source_cli(trusted, self.commit(), accepted)[0])
        self.assertEqual(baseline, (trusted / "baseline.json").read_bytes())

    def test_removed_foreign_writer_reintroduced_is_new_and_red(self):
        path = self.put_write("ai_quota_reservation", "ForeignMapper")
        self.commit()
        self.write(path, mapper("", "com.chengyinhub.business.mapper.ForeignMapper"))
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        trusted, _ = self.prepare_policy()
        self.put_write("ai_quota_reservation", "ForeignMapper")
        self.assert_rule(self.source_cli(trusted, self.commit()), "UNDECLARED_WRITE_BINDING")

    def test_new_operation_on_legacy_foreign_writer_is_red(self):
        path = self.put_write("ai_quota_reservation", "ForeignMapper")
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        trusted, _ = self.prepare_policy()
        self.write(path, mapper('<delete id="mutate">delete from ai_quota_reservation where id=#{id}</delete>',
                                "com.chengyinhub.business.mapper.ForeignMapper"))
        self.assert_rule(self.source_cli(trusted, self.commit()), "UNDECLARED_WRITE_BINDING")

    def test_cross_namespace_include_target_rewrite_is_red(self):
        self.write(MAPPER, mapper('<update id="finish"><include refid="example.Target.sql"/></update>'))
        other = MAPPER.replace("OrderMapper", "TargetMapper")
        self.write(other, mapper('<sql id="sql">update orders set state=1 where id=#{id}</sql>', "example.Target"))
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        trusted, _ = self.prepare_policy()
        self.write(other, mapper('<sql id="sql">update photo_check_intent set state=1 where id=#{id}</sql>', "example.Target"))
        self.assert_rule(self.source_cli(trusted, self.commit()), "UNDECLARED_WRITE_BINDING")

    def test_unknown_dynamic_target_is_review_not_safety(self):
        trusted, _ = self.prepare_policy()
        for sql in ('update ${table} set state=1 where id=#{id}', '<include refid="${dynamic}"/>'):
            with self.subTest(sql=sql):
                self.put_write("ai_quota_reservation", "ForeignMapper", sql)
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(0, status, report)
                self.assertEqual("PASS_WITH_REVIEW_ITEMS", report["verdict"])
                self.assertTrue(any(r["reason"] == "UNKNOWN_BUSINESS_WRITE_EFFECT" for r in report["review_items"]))

    def test_known_target_with_dynamic_fields_blocks_foreign_and_keeps_unknown(self):
        trusted, _ = self.prepare_policy()
        self.put_write("ai_quota_reservation", "ForeignMapper",
                       'update ai_quota_reservation set <if test="change">state=1</if> where id=#{id}')
        result = self.source_cli(trusted, self.commit())
        self.assert_rule(result, "UNDECLARED_WRITE_BINDING")
        self.assertTrue(any(r["reason"] == "UNKNOWN_BUSINESS_WRITE_EFFECT" for r in result[1]["review_items"]))

    def test_outside_facts_and_java_changes_do_not_become_new_owner_violations(self):
        trusted, _ = self.prepare_policy()
        self.put_write("unapproved_table", "ForeignMapper")
        self.write(JAVA, b"class OrderService { void newCaller() {} }")
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(0, status, report)
        self.assertTrue(any(r["reason"] == "BUSINESS_WRITE_OUTSIDE_POLICY" for r in report["review_items"]))
        self.assertEqual("PARTIAL", report["business_policy"]["coverage"])

    def test_approved_insert_ignore_replace_delete_and_select_dml(self):
        trusted, _ = self.prepare_policy()
        path = self.put_write("photo_check_intent", "PhotoCheckIntentMapper")
        namespace = "com.chengyinhub.business.mapper.PhotoCheckIntentMapper"
        for tag, sql in (("insert", "insert into photo_check_intent (state) values (1)"),
                         ("insert", "insert ignore into photo_check_intent (state) values (1)"),
                         ("insert", "replace into photo_check_intent (state) values (1)"),
                         ("delete", "delete from photo_check_intent where id=#{id}"),
                         ("select", "update photo_check_intent set state=1 where id=#{id}")):
            with self.subTest(tag=tag, sql=sql):
                self.write(path, mapper('<%s id="mutate">%s</%s>' % (tag, sql, tag), namespace))
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(0, status, report)

    def test_existing_technical_writer_rule_remains_red(self):
        self.put_write("photo_check_intent", "PhotoCheckIntentMapper")
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        trusted, _ = self.prepare_policy()
        self.put_write("photo_check_intent", "ForeignMapper")
        result = self.source_cli(trusted, self.commit())
        self.assert_rule(result, "NEW_TABLE_WRITER")
        self.assert_rule(result, "UNDECLARED_WRITE_BINDING")

    def test_approved_dynamic_effects_stay_unknown(self):
        trusted, _ = self.prepare_policy()
        self.put_write("ai_quota_reservation", "AiQuotaReservationMapper",
                       'update ai_quota_reservation set <if test="change">state=1</if> where id=#{id}')
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(0, status, report)
        self.assertTrue(any(r["reason"] == "UNKNOWN_BUSINESS_WRITE_EFFECT" for r in report["review_items"]))

    def test_legacy_foreign_new_statement_identity_cannot_inherit_debt(self):
        self.put_write("ai_quota_reservation", "ForeignMapper")
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        trusted, _ = self.prepare_policy()
        self.put_write("ai_quota_reservation", "ForeignMapper", name="newIdentity")
        self.assert_rule(self.source_cli(trusted, self.commit()), "UNDECLARED_WRITE_BINDING")

    def test_plain_java_and_docs_do_not_require_new_approval(self):
        trusted, accepted = self.prepare_policy()
        self.write(JAVA, b"class OrderService { void changed() {} }")
        self.write("docs/normal.md", b"normal review")
        status, report = self.source_cli(trusted, self.commit(), accepted)
        self.assertEqual(0, status, report)
        self.assertEqual([], report["findings"])
        self.assertEqual([], report["review_items"])
        self.assertEqual("PARTIAL", report["business_policy"]["coverage"])

    def test_candidate_owner_fact_scope_or_writer_forgery_is_fail_closed(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for alteration in ("owner", "delete_fact", "clear_scope", "writer"):
            with self.subTest(alteration=alteration):
                value = copy.deepcopy(original)
                policy = value["business_policy"]
                if alteration == "owner": policy["facts"][0]["business_owner"] = "FORGED"
                elif alteration == "delete_fact": policy["facts"].pop()
                elif alteration == "clear_scope": policy["scope"].clear()
                else: policy["pure_writers"][0]["namespace"] = "foreign.Mapper"
                self.write("ci/architecture/owners.json", gate.encode(value))
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status)
                self.assertIn("POLICY_CHANGE", report["error"])

    def test_candidate_command_or_baseline_forgery_is_fail_closed(self):
        trusted, _ = self.prepare_policy()
        for file, value in (("commands.json", b'{"approved":true}'), ("baseline.json", b'{}')):
            with self.subTest(file=file):
                self.write("ci/architecture/" + file, value)
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status)
                self.assertIn("POLICY_CHANGE", report["error"])
                self.write("ci/architecture/" + file, (trusted / file).read_bytes())
                self.commit()

    def test_first_commit_owner_violation_second_docs_cannot_use_parent_as_base(self):
        trusted, trunk = self.prepare_policy()
        self.put_write("photo_check_intent", "ForeignMapper")
        first = self.commit()
        self.write("docs/second.md", b"docs second commit")
        candidate = self.commit()
        with self.manual_origin(trunk):
            context = self.manual_context(candidate)
            self.assert_rule(self.source_cli(trusted, candidate, trunk, context), "UNDECLARED_WRITE_BINDING")
            status, report = self.source_cli(trusted, candidate, first, context)
            self.assertEqual(1, status)
            self.assertIn("COMPARISON_BASE_MISMATCH", report["error"])

    def test_malformed_trusted_policy_is_not_treated_as_disabled(self):
        trusted, _ = self.prepare_policy()
        self.write("ci/architecture/owners.json", b'{}')
        (trusted / "owners.json").write_bytes(b'{}')
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(1, status)
        self.assertIn("BUSINESS_POLICY_INVALID", report["error"])

    def test_command_owner_or_fact_mismatch_in_trusted_data_is_red(self):
        trusted, _ = self.prepare_policy()
        data = json.loads((trusted / "commands.json").read_text())
        data["business_commands"][0]["business_owner"] = "FORGED"
        payload = gate.encode(data)
        self.write("ci/architecture/commands.json", payload)
        (trusted / "commands.json").write_bytes(payload)
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(1, status)
        self.assertIn("BUSINESS_POLICY_INVALID", report["error"])


class PrivatePortPolicyFreezeTest(unittest.TestCase):
    setUp = InventoryAndDeltaTest.setUp
    write = InventoryAndDeltaTest.write
    commit = InventoryAndDeltaTest.commit
    prepare_policy = InventoryAndDeltaTest.prepare_policy
    source_cli = BusinessOwnerPolicyTest.source_cli

    def test_candidate_producer_cannot_approve_its_own_output(self):
        trusted, _ = self.prepare_policy()
        self.write("ci/architecture/collect_jdeps.py", b"# forged collector emits safe-looking JSON")
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(1, status, report)
        self.assertIn("POLICY_CHANGE", report["error"])

    def test_candidate_consumer_binding_cannot_self_approve(self):
        trusted, _ = self.prepare_policy()
        data = json.loads((trusted / "owners.json").read_text())
        data["business_policy"]["private_ports"][0]["consumer"] = "foreign.Consumer"
        self.write("ci/architecture/owners.json", gate.encode(data))
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(1, status)
        self.assertIn("POLICY_CHANGE", report["error"])

    def test_malformed_trusted_consumer_binding_is_not_disabled(self):
        trusted, _ = self.prepare_policy()
        data = json.loads((trusted / "owners.json").read_text())
        data["business_policy"]["private_ports"][0]["consumer"] = "foreign.Consumer"
        value = gate.encode(data)
        self.write("ci/architecture/owners.json", value)
        (trusted / "owners.json").write_bytes(value)
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(1, status)
        self.assertIn("BUSINESS_POLICY_INVALID", report["error"])


    def test_three_private_ports_do_not_approve_a_third_whole_table_fact_or_command(self):
        policy = gate.business_policy()
        self.assertEqual({"ai_quota_reservation", "photo_check_intent"}, set(policy["facts"]))
        self.assertEqual(2, len(policy["writers"]))
        self.assertEqual(2, len(policy["commands"]))
        self.assertEqual(3, len(policy["private_ports"]))
        self.assertEqual({"owner": "chengyinhub-system", "consumer":
                         "com.chengyinhub.business.service.impl.AiThemeDraftRequestService"},
                         policy["private_ports"]["com.chengyinhub.business.mapper.AiThemeDraftIntentMapper"])

    def test_candidate_third_binding_change_or_removal_requires_independent_trusted_revision(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for change in ("consumer", "remove"):
            with self.subTest(change=change):
                data = copy.deepcopy(original)
                if change == "remove": data["business_policy"]["private_ports"].pop()
                else: data["business_policy"]["private_ports"][2]["consumer"] = "foreign.Consumer"
                self.write("ci/architecture/owners.json", gate.encode(data))
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status, report)
                self.assertIn("POLICY_CHANGE", report["error"])

    def test_invalid_trusted_third_port_is_not_a_disabled_rule(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for field, value in (("owner", "unknown-module"), ("consumer", "not a class name")):
            with self.subTest(field=field):
                data = copy.deepcopy(original)
                data["business_policy"]["private_ports"][2][field] = value
                payload = gate.encode(data)
                self.write("ci/architecture/owners.json", payload)
                (trusted / "owners.json").write_bytes(payload)
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status, report)
                self.assertIn("BUSINESS_POLICY_INVALID", report["error"])

    def test_adding_third_port_does_not_allow_removing_original_writer_port(self):
        trusted, _ = self.prepare_policy()
        data = json.loads((trusted / "owners.json").read_text())
        data["business_policy"]["private_ports"][0]["target"] = "foreign.OtherMapper"
        payload = gate.encode(data)
        self.write("ci/architecture/owners.json", payload)
        (trusted / "owners.json").write_bytes(payload)
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(1, status, report)
        self.assertIn("BUSINESS_POLICY_INVALID", report["error"])


    def test_candidate_dependency_directions_cannot_self_approve_or_delete_owner(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for change in ("directions", "delete"):
            data = copy.deepcopy(original)
            if change == "delete": del data["technical_dependency_policy"]
            else: data["technical_dependency_policy"]["allowed_directions"].append(["D_CONTENT_RULES", "D_CONTENT_GRAPH"])
            self.write("ci/architecture/owners.json", gate.encode(data))
            status, report = self.source_cli(trusted, self.commit())
            self.assertEqual(1, status)
            self.assertIn("POLICY_CHANGE", report["error"])

    def test_cyclic_or_missing_trusted_dependency_policy_is_not_disabled(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for change in ("cycle", "delete"):
            data = copy.deepcopy(original)
            if change == "delete": del data["technical_dependency_policy"]
            else: data["technical_dependency_policy"]["allowed_directions"].append(["D_CONTENT_RULES", "D_CONTENT_GRAPH"])
            payload = gate.encode(data)
            self.write("ci/architecture/owners.json", payload)
            (trusted / "owners.json").write_bytes(payload)
            status, report = self.source_cli(trusted, self.commit())
            self.assertEqual(1, status)
            self.assertIn("OWNER_DEPENDENCY_POLICY_INVALID", report["error"])

    def test_v11_candidate_cannot_delete_new_owner_or_clear_original_god(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for change in ("remove_owner", "clear_backrefs", "allow_reverse"):
            with self.subTest(change=change):
                data = copy.deepcopy(original)
                policy = data["technical_dependency_policy"]
                if change == "remove_owner":
                    policy["owners"] = [o for o in policy["owners"] if o["id"] != "A_SHARED_RUN_CONTROL"]
                elif change == "clear_backrefs":
                    next(o for o in policy["owners"] if o["id"] == "C_PLAY_NODE_QUERY")["forbidden_backrefs"] = []
                else:
                    policy["allowed_directions"].append(["C_PLAY_NODE_QUERY", "C_PLAY_ENTRY"])
                self.write("ci/architecture/owners.json", gate.encode(data))
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status, report)
                self.assertIn("POLICY_CHANGE", report["error"])

    def test_final_d_candidate_cannot_delete_publication_owner_or_clear_god(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for change in ("remove_owner", "clear_backrefs"):
            with self.subTest(change=change):
                data = copy.deepcopy(original)
                policy = data["technical_dependency_policy"]
                if change == "remove_owner":
                    policy["owners"] = [o for o in policy["owners"] if o["id"] != "D_PUBLICATION"]
                else:
                    next(o for o in policy["owners"] if o["id"] == "D_SELF_HOST_ACTIVITY")["forbidden_backrefs"] = []
                self.write("ci/architecture/owners.json", gate.encode(data))
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status, report)
                self.assertIn("POLICY_CHANGE", report["error"])

    def test_followup_sdk_owner_or_reverse_direction_cannot_self_approve(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for change in ("remove", "reverse"):
            with self.subTest(change=change):
                data = copy.deepcopy(original)
                policy = data["technical_dependency_policy"]
                if change == "remove":
                    policy["owners"] = [o for o in policy["owners"] if o["id"] != "B_PAYMENT_SDK"]
                else:
                    policy["allowed_directions"].append(["F_LOG_CONTEXT", "F_REQUEST_CONTEXT"])
                self.write("ci/architecture/owners.json", gate.encode(data))
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status, report)
                self.assertIn("POLICY_CHANGE", report["error"])

    def test_candidate_cannot_remove_topic_query_owner_or_clear_writer_restrictions(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for change in ("remove", "clear"):
            data = copy.deepcopy(original)
            policy = data["technical_dependency_policy"]
            if change == "remove": policy["owners"] = [o for o in policy["owners"] if o["id"] != "E_TOPIC_QUERY"]
            else: next(o for o in policy["owners"] if o["id"] == "E_TOPIC_QUERY")["forbidden_backrefs"] = []
            self.write("ci/architecture/owners.json", gate.encode(data))
            status, report = self.source_cli(trusted, self.commit())
            self.assertEqual(1, status, report)
            self.assertIn("POLICY_CHANGE", report["error"])



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


def wiring_problems(text):
    """Narrow text contract, like existing ci/test_ci_cost_contract.py, not YAML parsing."""
    match = re.search(r"      # F architecture gate: begin\n(.*?)      # F architecture gate: end\n", text, re.S)
    if not match:
        return ["missing architecture steps"]
    block = match.group(1)
    problems = []
    if not (re.search(r"^  fast_gates:\n", text, re.M).start() < match.start()
            < re.search(r"^  backend:\n", text, re.M).start()):
        problems.append("architecture must reuse unconditional fast_gates")
    if not re.search(r"^          ref: [0-9a-f]{40}$", block, re.M):
        problems.append("trusted verifier must use an immutable SHA")
    for expected in ("repository: liboyang42-cpu/hengyin", "fetch-depth: 0", "persist-credentials: false",
                     "if: '!cancelled()'", "steps.architecture_checkout.outcome == 'success'",
                     "steps.architecture_selftest.outcome == 'success'", "--compare-base", "git merge-base",
                     "github.event.pull_request.head.sha || github.sha", "github.event.pull_request.base.sha",
                     "github.event.before", "workflow_dispatch)", "gate.py manual-base --source . --candidate", "set -euo pipefail",
                     "git worktree add --detach", "git worktree remove", "actions/upload-artifact@v4",
                     "architecture_check.outcome != 'skipped'", "if-no-files-found: error"):
        if expected not in block:
            problems.append("missing " + expected)
    if "continue-on-error" in block or "|| true" in block or "^1" in block or "max-parallel: 20" not in text:
        problems.append("error propagation/20-shard budget changed")
    return problems


class WorkflowWiringTest(unittest.TestCase):
    def setUp(self):
        path = Path(os.environ.get("ARCHITECTURE_WORKFLOW", gate.REPO / ".github/workflows/ci.yml"))
        self.text = path.read_text()

    def test_wired_steps_preserve_trust_and_budget(self):
        self.assertEqual([], wiring_problems(self.text))

    def test_missing_comparison_mutable_verifier_or_suppressed_error_is_red(self):
        for mutated in (self.text.replace("gate.py manual-base --source . --candidate", "gate.py inventory --source"),
                        self.text.replace("--compare-base", "--wrong-base"),
                        re.sub(r"(          ref: )[0-9a-f]{40}", r"\1master", self.text),
                        self.text.replace("set -euo pipefail", "set +e\n          true || true")):
            with self.subTest(mutated=mutated[-100:]):
                self.assertTrue(wiring_problems(mutated))

    def test_check_shell_parses_without_running_it(self):
        start = self.text.index('          set -euo pipefail\n', self.text.index('# F architecture gate: begin'))
        end = self.text.index('      - name: Preserve architecture', start)
        script = "\n".join(line[10:] for line in self.text[start:end].splitlines()) + "\n"
        result = subprocess.run(["bash", "-n"], input=script, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()

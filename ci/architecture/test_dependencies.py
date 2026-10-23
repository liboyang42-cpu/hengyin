"""WP01 tests use only temporary Git/Java text; no Java/bytecode execution."""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("wp01_dependencies", Path(__file__).with_name("dependencies.py"))
dep = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dep)

COMMON, SYSTEM, ADMIN = "chengyinhub-common", "chengyinhub-system", "chengyinhub-admin"


class DependencyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "WP01 isolated fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>' +
                   "".join("<module>%s</module>" % m for m in (COMMON, SYSTEM, ADMIN)) + "</modules></project>")
        for module, dependencies in ((COMMON, []), (SYSTEM, [COMMON]), (ADMIN, [SYSTEM])):
            self.write(module + "/pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>' +
                       "".join('<dependency><groupId>com.chengyinhub</groupId><artifactId>%s</artifactId></dependency>' % d for d in dependencies) + "</dependencies></project>")
        self.java(COMMON, "com.chengyinhub.common.api.CommonApi", "", "public static Object mapper;")
        self.java(COMMON, "com.chengyinhub.common.internal.Secret")
        self.java(SYSTEM, "com.chengyinhub.business.api.OrdersApi", "com.chengyinhub.common.api.CommonApi")
        self.java(SYSTEM, "com.chengyinhub.business.mapper.OrderMapper")
        self.controller = self.java(ADMIN, "com.chengyinhub.web.Controller", "com.chengyinhub.business.api.OrdersApi")
        self.base = self.commit()
        self.before = dep.inventory(self.root, self.base)

    def git(self, *args):
        return dep.shared.git(self.root, *args).decode().strip()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def java(self, module, fqcn, target="", body=""):
        package, name = fqcn.rsplit(".", 1)
        path = module + "/src/main/java/" + fqcn.replace(".", "/") + ".java"
        self.write(path, "package %s;\n%s\npublic class %s {%s}\n" %
                   (package, "import %s;" % target if target else "", name, body))
        return path

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "-qm", "isolated fixture")
        return self.git("rev-parse", "HEAD")

    def delta(self, before=None):
        candidate = self.commit()
        prior = before or self.before
        changed = set(self.git("diff", "--name-only", prior["source_sha"], candidate).splitlines())
        return dep.delta(prior, dep.inventory(self.root, candidate), changed)

    def test_public_api_and_body_only_changes_pass(self):
        self.java(ADMIN, "com.chengyinhub.web.Controller", "com.chengyinhub.business.api.OrdersApi", "int revision=2;")
        self.assertEqual([], self.delta()["findings"])

    def test_same_owner_mapper_passes(self):
        self.java(SYSTEM, "com.chengyinhub.business.Service", "com.chengyinhub.business.mapper.OrderMapper")
        self.assertEqual([], self.delta()["findings"])

    def test_new_cross_owner_mapper_and_internal_are_red(self):
        for target in ("com.chengyinhub.business.mapper.OrderMapper", "com.chengyinhub.common.internal.Secret"):
            self.java(ADMIN, "com.chengyinhub.web.Controller", target)
            with self.subTest(target=target):
                self.assertTrue(any(item["rule"] == "NEW_CROSS_MODULE_PRIVATE_IMPORT" for item in self.delta()["findings"]))

    def test_legacy_private_import_not_a_directory_wide_exception(self):
        self.java(ADMIN, "com.chengyinhub.web.Controller", "com.chengyinhub.business.mapper.OrderMapper")
        accepted = dep.inventory(self.root, self.commit())
        self.java(ADMIN, "com.chengyinhub.web.Controller", "com.chengyinhub.business.mapper.OrderMapper", "int next=1;")
        self.assertEqual([], self.delta(accepted)["findings"])
        self.java(ADMIN, "com.chengyinhub.web.OtherController", "com.chengyinhub.business.mapper.OrderMapper")
        self.assertTrue(self.delta(accepted)["findings"])

    def test_closing_cycle_is_red_with_source_witness(self):
        self.java(COMMON, "com.chengyinhub.common.BackEdge", "com.chengyinhub.business.api.OrdersApi")
        findings = self.delta()["findings"]
        cycle = next(item for item in findings if item["rule"] == "NEW_OR_EXPANDED_MODULE_SCC")
        self.assertEqual((COMMON, SYSTEM), cycle["modules"])
        self.assertTrue(cycle["new_edge_witnesses"])

    def test_new_public_api_edge_is_allowed_but_three_node_ring_is_red(self):
        self.java(ADMIN, "com.chengyinhub.web.CommonConsumer", "com.chengyinhub.common.api.CommonApi")
        self.assertEqual([], self.delta()["findings"])
        self.java(COMMON, "com.chengyinhub.common.BackEdge", "com.chengyinhub.web.Controller")
        cycle = next(item for item in self.delta()["findings"] if item["rule"] == "NEW_OR_EXPANDED_MODULE_SCC")
        self.assertEqual(tuple(sorted((COMMON, SYSTEM, ADMIN))), cycle["modules"])

    def test_old_scc_unchanged_or_shrunk_is_not_rejected(self):
        self.assertEqual([("a", "b", "c")], dep.components(["a", "b", "c"], [("a", "b"), ("b", "c"), ("c", "a")]))
        before = {"references": {}, "scc": [("a", "b", "c")], "edge_witnesses": {}}
        after = {"references": {}, "scc": [("a", "b")], "edge_witnesses": {}, "unknown": []}
        self.assertEqual([], dep.delta(before, after, set())["findings"])
        before["scc"] = [("a", "b")]
        after["scc"] = [("a", "b", "c")]
        self.assertTrue(dep.delta(before, after, set())["findings"])

    def test_pom_edge_can_close_cycle_without_any_java_edit(self):
        self.write(COMMON + "/pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency><groupId>com.chengyinhub</groupId><artifactId>chengyinhub-system</artifactId></dependency></dependencies></project>')
        self.assertTrue(any(item["rule"] == "NEW_OR_EXPANDED_MODULE_SCC" for item in self.delta()["findings"]))

    def test_explicit_maven_self_dependency_is_a_cycle(self):
        self.write(COMMON + "/pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency><groupId>com.chengyinhub</groupId><artifactId>chengyinhub-common</artifactId></dependency></dependencies></project>')
        self.assertTrue(any(item["rule"] == "NEW_OR_EXPANDED_MODULE_SCC" and item["modules"] == (COMMON,)
                            for item in self.delta()["findings"]))

    def test_comments_and_string_imports_do_not_create_edges(self):
        self.java(ADMIN, "com.chengyinhub.web.Controller", "", '\n// import com.chengyinhub.business.mapper.OrderMapper;\nString fake="import com.chengyinhub.common.internal.Secret;";\n')
        result = self.delta()
        self.assertEqual([], result["findings"])
        self.assertEqual([], result["review_items"])

    def test_shared_package_is_resolved_by_concrete_type(self):
        self.java(ADMIN, "com.chengyinhub.business.util.AdminOnly")
        self.java(SYSTEM, "com.chengyinhub.business.util.SystemOnly")
        self.java(ADMIN, "com.chengyinhub.web.Controller", "com.chengyinhub.business.util.SystemOnly")
        data = dep.inventory(self.root, self.commit())
        self.assertEqual([ADMIN, SYSTEM], data["package_owners"]["com.chengyinhub.business.util"])
        reference = next(r for r in data["references"].values() if r["target"].endswith("SystemOnly"))
        self.assertEqual(SYSTEM, reference["target_owner"])
        self.assertTrue(any(item["reason"] == "SHARED_PACKAGE_IMPLICIT_TYPE_USAGE_NOT_RESOLVED" for item in data["unknown"]))

    def test_static_public_field_named_mapper_is_not_a_private_package(self):
        self.write(self.controller, "package com.chengyinhub.web;\nimport static com.chengyinhub.common.api.CommonApi.mapper;\npublic class Controller {}")
        self.assertEqual([], self.delta()["findings"])

    def test_wildcards_text_blocks_unicode_and_fqcn_are_unknown(self):
        cases = ["import com.chengyinhub.business.mapper.*;\nclass Controller {}",
                 'class Controller { String s="""\nimport com.chengyinhub.business.mapper.OrderMapper;\n"""; }',
                 r'class Controller { String s="\u0069"; }',
                 "class Controller { com.chengyinhub.business.mapper.OrderMapper field; }"]
        for text in cases:
            self.write(self.controller, "package com.chengyinhub.web;\n" + text)
            with self.subTest(text=text):
                result = self.delta()
                self.assertEqual([], result["findings"])
                self.assertTrue(result["review_items"])
                self.assertFalse(result["graph_complete"])

    def test_duplicate_type_owner_is_unknown_not_guessed(self):
        self.java(ADMIN, "com.chengyinhub.business.mapper.OrderMapper")
        self.java(COMMON, "com.chengyinhub.common.Consumer", "com.chengyinhub.business.mapper.OrderMapper")
        result = self.delta()
        self.assertTrue(any(item["reason"] == "AMBIGUOUS_TYPE_OWNER" for item in result["review_items"]))

    def test_unicode_escape_in_commented_source_is_not_treated_as_inactive(self):
        self.write(self.controller, r"// \u000apackage com.chengyinhub.web; \u000aimport com.chengyinhub.business.mapper.OrderMapper;")
        result = self.delta()
        self.assertEqual([], result["findings"])
        self.assertTrue(any(item["path"] == self.controller and item["reason"] == "JAVA_TEXT_BLOCK_OR_UNICODE_ESCAPE"
                            for item in result["review_items"]))

    def policy(self):
        path = self.root / "ci/architecture"
        path.mkdir(parents=True)
        frozen = dep.shared.encode(dep.snapshot(self.before))
        self.write("ci/architecture/dependency-baseline.json", frozen.decode())
        for name in ("ci/architecture/dependencies.py", "ci/architecture/gate.py", "ci/real_storage_it.py"):
            self.write(name, "# trusted isolated fixture")
        candidate = self.commit()
        trusted = self.root / ".git/trusted"
        self.git("clone", "--quiet", "--no-hardlinks", str(self.root), str(trusted))
        return trusted, candidate

    def check(self, trusted, candidate, compare=None, frozen=None):
        compare = compare or self.base
        context = {"event": "push", "checkout_sha": candidate,
                   "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin"}, "before": compare, "after": candidate}}
        with patch.object(dep, "FROZEN", frozen or self.base), patch.object(dep, "HERE", trusted / "ci/architecture"), \
                patch.object(dep, "ROOT", trusted), patch.object(dep.shared, "REPO", trusted), \
                patch.object(dep.shared, "BASE", self.base):
            return dep.check(self.root, candidate, compare, context)

    def test_exact_baseline_and_normal_candidate_pass_then_laundering_fails(self):
        trusted, candidate = self.policy()
        self.assertEqual([], self.check(trusted, candidate)["findings"])
        (trusted / "ci/architecture/dependency-baseline.json").write_text("{}")
        with self.assertRaisesRegex(dep.shared.GateError, "BASELINE_LAUNDERING"):
            self.check(trusted, candidate)

    def test_candidate_baseline_policy_tampering_fails_but_helper_is_not_executed(self):
        trusted, _ = self.policy()
        self.write("ci/architecture/dependency-baseline.json", "{}")
        candidate = self.commit()
        self.assertRaises(dep.shared.GateError, self.check, trusted, candidate)
        self.write("ci/architecture/dependency-baseline.json", (trusted / "ci/architecture/dependency-baseline.json").read_text())
        # The verifier imports its own frozen helper. Ordinary candidate harness
        # changes neither execute here nor change trusted lexical semantics.
        self.write("ci/real_storage_it.py", "raise AssertionError('candidate helper must not execute')")
        self.assertEqual([], self.check(trusted, self.commit())["findings"])
        for name in ("dependencies.py", "gate.py"):
            path = "ci/architecture/" + name
            self.write(path, "# unreviewed verifier")
            with self.assertRaisesRegex(dep.shared.GateError, "verifier change"):
                self.check(trusted, self.commit())
            self.write(path, (trusted / path).read_text())

    def test_compact_imports_static_and_nested_private_types_are_red(self):
        self.java(SYSTEM, "com.chengyinhub.business.mapper.OrderMapper", body="public static class Nested {} public static Object VALUE;")
        for declaration in ("import com.chengyinhub.business.mapper.OrderMapper;",
                            "import com.chengyinhub.business.mapper.OrderMapper.Nested;",
                            "import static com.chengyinhub.business.mapper.OrderMapper.VALUE;"):
            self.write(self.controller, "package com.chengyinhub.web; " + declaration + " public class Controller {}")
            with self.subTest(declaration=declaration):
                self.assertTrue(any(item["rule"] == "NEW_CROSS_MODULE_PRIVATE_IMPORT" for item in self.delta()["findings"]))

    def test_unsupported_import_spelling_is_unknown_not_silently_ignored(self):
        # Java permits whitespace around dots; do not build a larger parser for it.
        self.write(self.controller, "package com.chengyinhub.web;\nimport com . chengyinhub . business . mapper . OrderMapper;\nclass Controller {}")
        result = self.delta()
        self.assertEqual([], result["findings"])
        self.assertTrue(any(item["reason"] == "UNRESOLVED_IMPORT_DECLARATION" for item in result["review_items"]))

    def test_unresolved_maven_properties_and_profiles_are_unknown(self):
        self.write(COMMON + "/pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><profiles/><dependencies><dependency><groupId>${project.groupId}</groupId><artifactId>${module.name}</artifactId></dependency></dependencies></project>')
        result = self.delta()
        self.assertEqual([], result["findings"])
        self.assertEqual({"MAVEN_PROFILES_NOT_EVALUATED", "MAVEN_DEPENDENCY_NOT_RESOLVED"},
                         {item["reason"] for item in result["review_items"]})

    def test_module_owner_change_is_explicit_unknown(self):
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>chengyinhub-common</module><module>chengyinhub-system</module></modules></project>')
        result = self.delta()
        self.assertTrue(any(item["reason"] == "MAVEN_MODULE_OWNERS_CHANGED" for item in result["review_items"]))

    def test_documentation_only_source_check_is_green_with_no_new_review(self):
        trusted, trunk = self.policy()
        self.write("docs/change.md", "documentation only")
        result = self.check(trusted, self.commit(), trunk)
        self.assertEqual([], result["findings"])
        self.assertEqual([], result["review_items"])
        self.assertFalse(result["graph_complete"])

    def test_patch_integrated_candidate_need_not_contain_frozen_verifier_history(self):
        trusted, _ = self.policy()
        dep.shared.git(trusted, "config", "user.name", "trusted fixture")
        dep.shared.git(trusted, "config", "user.email", "trusted@example.invalid")
        (trusted / "trusted-only.txt").write_text("snapshot only exists in verifier history")
        dep.shared.git(trusted, "add", "trusted-only.txt")
        dep.shared.git(trusted, "commit", "-qm", "trusted verifier only")
        frozen = dep.shared.git(trusted, "rev-parse", "HEAD").decode().strip()
        snapshot = dep.shared.encode(dep.snapshot(dep.inventory(trusted, frozen)))
        (trusted / "ci/architecture/dependency-baseline.json").write_bytes(snapshot)
        self.write("ci/architecture/dependency-baseline.json", snapshot.decode())
        candidate = self.commit()
        with self.assertRaises(dep.shared.GateError):
            dep.shared.git(self.root, "cat-file", "-e", frozen + "^{commit}")
        self.assertEqual([], self.check(trusted, candidate, frozen=frozen)["findings"])

    def test_cli_normal_change_passes_and_both_real_rules_exit_nonzero(self):
        trusted = self.root / ".git/cli-trusted"
        programs = ("ci/architecture/dependencies.py", "ci/architecture/gate.py", "ci/real_storage_it.py")
        self.write("ci/architecture/dependency-baseline.json", dep.shared.encode(dep.snapshot(self.before)).decode())
        for name in programs:
            text = (dep.ROOT / name).read_text()
            if name.endswith("dependencies.py"):
                text = text.replace('FROZEN = "' + dep.FROZEN + '"', 'FROZEN = "' + self.base + '"')
            elif name.endswith("gate.py"):
                text = text.replace('BASE = "' + dep.shared.BASE + '"', 'BASE = "' + self.base + '"')
            self.write(name, text)
            (trusted / name).parent.mkdir(parents=True, exist_ok=True)
            (trusted / name).write_text(text)
        (trusted / "ci/architecture/dependency-baseline.json").write_bytes(
            (self.root / "ci/architecture/dependency-baseline.json").read_bytes())
        dep.shared.git(trusted, "init", "-q")
        dep.shared.git(trusted, "fetch", "--quiet", str(self.root), self.base)
        event_file = self.root / ".git/cli-event.json"
        def run():
            candidate = self.commit()
            event_file.write_text(json.dumps({"repository": {"full_name": "liboyang42-cpu/hengyin"},
                                              "before": self.base, "after": candidate}))
            environment = {**os.environ, "GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "liboyang42-cpu/hengyin",
                           "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": candidate, "GITHUB_EVENT_PATH": str(event_file)}
            result = subprocess.run([sys.executable, str(trusted / programs[0]), "source", "--source", str(self.root),
                                     "--candidate", candidate, "--compare-base", self.base],
                                    env=environment, capture_output=True, text=True)
            return result.returncode, json.loads(result.stdout)
        status, result = run()
        self.assertEqual(0, status)
        self.assertEqual([], result["findings"])
        self.java(ADMIN, "com.chengyinhub.web.Controller", "com.chengyinhub.business.mapper.OrderMapper")
        status, result = run()
        self.assertEqual(1, status)
        self.assertIn("NEW_CROSS_MODULE_PRIVATE_IMPORT", {item["rule"] for item in result["findings"]})
        self.java(ADMIN, "com.chengyinhub.web.Controller", "com.chengyinhub.business.api.OrdersApi")
        self.java(COMMON, "com.chengyinhub.common.BackEdge", "com.chengyinhub.business.api.OrdersApi")
        status, result = run()
        self.assertEqual(1, status)
        self.assertIn("NEW_OR_EXPANDED_MODULE_SCC", {item["rule"] for item in result["findings"]})

    def test_wrong_comparison_event_dirty_and_wrong_head_fail(self):
        trusted, candidate = self.policy()
        self.assertRaises(dep.shared.GateError, self.check, trusted, self.base)
        self.write(self.controller, "dirty")
        self.assertRaises(dep.shared.GateError, self.check, trusted, candidate)


if __name__ == "__main__":
    unittest.main()

"""ZIP/protocol fixtures only. No Java, javac, jdeps, database or network runs."""
import importlib.util
from contextlib import redirect_stdout
import copy
import io
import json
import os
import sys
from unittest.mock import patch
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("architecture_jdeps", Path(__file__).with_name("collect_jdeps.py"))
producer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(producer)
MODULES = ["chengyinhub-" + name for name in ("admin", "common", "framework", "generator", "quartz", "system")]


def jar(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return out.getvalue()


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.git("init", "-q")
        self.git("config", "user.name", "jdeps protocol fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        (self.root / ".gitignore").write_text("target/\n")
        (self.root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>' +
                    "".join("<module>" + m + "</module>" for m in MODULES) + "</modules></project>")
        self.git("add", "--all")
        self.git("commit", "-qm", "isolated fixture")
        self.classes = {m: "example." + m.split("-")[1] + ".Type" for m in MODULES}
        self.classes["chengyinhub-system"] = "example.system.mapper.DataMapper"
        self.boot = self.root / "chengyinhub-admin/target/chengyinhub-admin.jar"
        self.identity = self.boot.with_suffix(".build.json")
        self.output = self.boot.parent / "architecture-jdeps"
        self.tool = self.root / ".git/fake-jdk/bin/jdeps"
        self.tool.parent.mkdir(parents=True)
        self.tool.write_text("TEST FIXTURE ONLY; not an executable JDK")
        self.env = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "liboyang42-cpu/hengyin",
                    "GITHUB_SHA": self.git("rev-parse", "HEAD"), "GITHUB_RUN_ID": "123",
                    "GITHUB_RUN_ATTEMPT": "2", "JAVA_HOME": str(self.tool.parent.parent)}
        self.calls = []
        self.exit_code, self.stderr, self.version = 0, "", "21.0.0-fixture\n"
        self.make_artifacts()
        self.stdout = "\n".join(m + ".jar -> java.base\n   " + self.classes[m] + " -> java.lang.Object java.base" for m in MODULES)
        self.stdout += "\n   " + self.classes[MODULES[0]] + " -> " + self.classes[MODULES[-1]] + " chengyinhub-system.jar\n"

    def git(self, *args):
        return producer.shared.git(self.root, *args).decode().strip()

    def make_artifacts(self):
        entries = {}
        for module, classname in self.classes.items():
            relative = classname.replace(".", "/") + ".class"
            # Deliberately not valid bytecode: the producer only reads ZIP paths.
            data = ("SYNTHETIC_NOT_COMPILED_" + module).encode()
            compiled = self.root / module / "target/classes" / relative
            compiled.parent.mkdir(parents=True, exist_ok=True)
            compiled.write_bytes(data)
            original = jar({relative: data})
            if module == "chengyinhub-admin":
                self.boot.with_name(self.boot.name + ".original").write_bytes(original)
                entries["BOOT-INF/classes/" + relative] = data
            else:
                name = module + "-1.jar"
                (self.root / module / "target" / name).write_bytes(original)
                entries["BOOT-INF/lib/" + name] = original
        entries["BOOT-INF/lib/external-1.jar"] = jar({"external/Type.class": b"EXTERNAL_FIXTURE"})
        self.boot.write_bytes(jar(entries))
        metadata = {"checkoutCommit": self.env["GITHUB_SHA"], "checkoutTree": self.git("rev-parse", "HEAD^{tree}"),
                    "candidateHead": self.env["GITHUB_SHA"], "runId": "123", "runAttempt": "2",
                    "jarSha256": producer.shared.digest(self.boot.read_bytes())}
        self.identity.write_text(json.dumps(metadata))

    def runner(self, args, **kwargs):
        self.calls.append((args, kwargs))
        if args[-1] == "--version":
            return subprocess.CompletedProcess(args, 0, self.version, "")
        return subprocess.CompletedProcess(args, self.exit_code, self.stdout, self.stderr)

    def collect(self):
        return producer.collect(self.root, self.boot, self.identity, self.output, self.env, self.runner)

    def test_collects_exact_original_artifacts_and_standard_resolved_edges_without_acceptance(self):
        report = self.collect()
        self.assertEqual("COLLECTED_STATIC_REFERENCES", report["collection_status"])
        self.assertTrue(report["static_graph_complete"])
        self.assertFalse(report["graph_complete"])
        self.assertIsNone(report["scc"])
        self.assertIsNone(report["baseline"])
        self.assertEqual([], report["findings"])
        self.assertEqual([("chengyinhub-admin", "chengyinhub-system")], report["module_edges"])
        self.assertEqual(set(MODULES), set(report["artifacts"]))
        for row in report["artifacts"].values():
            self.assertEqual((self.root / row["path"]).read_bytes(), (self.output / row["evidence_path"]).read_bytes())
            self.assertEqual(producer.shared.digest((self.root / row["path"]).read_bytes()), row["sha256"])
        args, options = self.calls[-1]
        self.assertIn("-verbose:class", args)
        self.assertIn("-filter:none", args)
        self.assertNotIn("--ignore-missing-deps", args)
        self.assertNotIn("--recursive", args)
        self.assertEqual("C", options["env"]["LC_ALL"])
        self.assertEqual(180, options["timeout"])
        self.assertEqual(6, len(args[args.index("--class-path") + 2:]))
        self.assertNotIn("*", args[args.index("--class-path") + 1])
        self.assertTrue((self.output / "jdeps.stdout.txt").is_file())
        self.assertTrue((self.output / "class-index.json").is_file())
        self.assertEqual(self.env["GITHUB_SHA"], report["source_sha"])
        self.assertEqual("2", report["run_attempt"])

    def test_missing_dependency_and_partial_exit_are_not_no_cycle_proof(self):
        self.stdout += "   " + self.classes[MODULES[0]] + " -> missing.Type not found\n"
        self.exit_code, self.stderr = 1, "fixture unresolved dependencies\n"
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertEqual(1, report["jdeps_exit_code"])
        self.assertEqual("missing.Type", report["missing_dependencies"][0]["target_class"])
        self.assertFalse(report["static_graph_complete"])
        self.assertIsNone(report["scc"])
        self.assertEqual([], report["findings"])
        self.assertEqual(self.stdout, (self.output / "jdeps.stdout.txt").read_text())
        self.assertEqual(self.stderr, (self.output / "jdeps.stderr.txt").read_text())

    def test_missing_summary_unparsed_format_and_incomplete_owner_coverage_are_unknown(self):
        self.stdout = "chengyinhub-admin.jar -> not found\nformat changed\n"
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertTrue(report["missing_dependencies"])
        reasons = {r["reason"] for r in report["review_items"]}
        self.assertIn("UNRECOGNIZED_JDEPS_OUTPUT", reasons)
        self.assertIn("MODULE_OUTPUT_COVERAGE_INCOMPLETE", reasons)
        self.assertEqual([], report["module_edges"])

    def test_missing_or_changed_original_artifact_is_unknown(self):
        (self.root / "chengyinhub-common/target/chengyinhub-common-1.jar").write_bytes(b"changed")
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertIn("original/packaged", report["review_items"][0]["detail"])
        self.assertEqual(1, len(self.calls))

    def test_stale_compiled_class_is_unknown(self):
        compiled = next((self.root / "chengyinhub-admin/target/classes").rglob("*.class"))
        compiled.write_bytes(b"stale")
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertIn("class inventory mismatch", report["review_items"][0]["detail"])

    def test_missing_admin_original_does_not_fabricate_an_analysis_jar(self):
        self.boot.with_name(self.boot.name + ".original").unlink()
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertFalse(report["static_graph_complete"])
        self.assertEqual(1, len(self.calls))

    def test_class_owner_collision_is_unknown(self):
        self.classes["chengyinhub-system"] = self.classes["chengyinhub-admin"]
        # Remove the previous generated target to model a clean package.
        for p in (self.root / "chengyinhub-system/target/classes").rglob("*.class"):
            p.unlink()
        self.make_artifacts()
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertIn("ambiguous class owner", report["review_items"][0]["detail"])

    def test_boot_source_run_and_actual_checkout_are_bound(self):
        for field in ("checkoutCommit", "checkoutTree", "jarSha256", "runId", "runAttempt"):
            with self.subTest(field=field):
                data = json.loads(self.identity.read_text())
                data[field] = "forged"
                self.identity.write_text(json.dumps(data))
                report = self.collect()
                self.assertEqual("UNKNOWN", report["collection_status"])
                self.assertEqual([], self.calls)
                # Each isolated collection must have a fresh output.
                import shutil
                shutil.rmtree(self.output)
                self.make_artifacts()

    def test_dirty_checkout_cannot_be_called_source_bound(self):
        (self.root / "pom.xml").write_text("changed")
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertIn("dirty", report["review_items"][0]["detail"])

    def test_untracked_root_report_is_not_silently_ignored_by_source_binding(self):
        (self.root / "backend-test-identity.json").write_text("{}")
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertFalse(report["source_identity_verified"])
        self.assertIn("dirty", report["review_items"][0]["detail"])

    def test_non_jdk21_is_unknown_and_does_not_analyze(self):
        self.version = "17.0.13\n"
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertEqual(1, len(self.calls))

    def test_timeout_preserves_partial_raw_output_and_never_promotes_it(self):
        def timeout(args, **kwargs):
            if args[-1] == "--version":
                return self.runner(args, **kwargs)
            raise subprocess.TimeoutExpired(args, 180, output=b"partial\n", stderr=b"timed out\n")
        report = producer.collect(self.root, self.boot, self.identity, self.output, self.env, timeout)
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertFalse(report["static_graph_complete"])
        self.assertEqual(b"partial\n", (self.output / "jdeps.stdout.txt").read_bytes())

    def test_shadowed_or_unindexed_project_target_is_unknown_not_an_owner_guess(self):
        self.stdout = self.stdout.replace("chengyinhub-system.jar\n", "external-1.jar\n")
        self.stdout += "   " + self.classes[MODULES[0]] + " -> example.Unindexed chengyinhub-system.jar\n"
        report = self.collect()
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertEqual([], report["module_edges"])
        self.assertEqual({"TARGET_OWNER_LOCATION_MISMATCH", "TARGET_CLASS_NOT_INDEXED"},
                         {r["reason"] for r in report["review_items"]})

    def test_cli_missing_ci_context_is_unknown_without_approving_or_failing_existing_gates(self):
        args = ["collect_jdeps.py", "--source", str(self.root), "--artifact", str(self.boot),
                "--identity", str(self.identity), "--output", str(self.output)]
        with patch.dict(os.environ, {}, clear=True), patch.object(sys, "argv", args):
            self.assertEqual(0, producer.main())
        report = json.loads((self.output / "observation.json").read_text())
        self.assertEqual("UNKNOWN", report["collection_status"])
        self.assertEqual("OBSERVATION_ONLY", report["verdict"])
        self.assertFalse(report["graph_complete"])
        self.assertIsNone(report["scc"])
        self.assertIsNone(report["baseline"])
        self.assertEqual([], report["findings"])

    def test_cli_evidence_io_failure_remains_visible_unknown_not_a_new_acceptance_rule(self):
        self.output.write_text("occupied output path")
        args = ["collect_jdeps.py", "--source", str(self.root), "--artifact", str(self.boot),
                "--identity", str(self.identity), "--output", str(self.output)]
        with patch.dict(os.environ, self.env, clear=True), patch.object(sys, "argv", args), \
                patch("builtins.print") as log:
            self.assertEqual(0, producer.main())
        self.assertIn("UNKNOWN; evidence not persisted", log.call_args[0][0])
        self.assertTrue(self.output.is_file())

    def test_duplicate_unsafe_and_multirelease_project_zip_entries_are_rejected(self):
        for name in ("../Escape.class", "META-INF/versions/21/Type.class", "module-info.class"):
            with self.subTest(name=name):
                self.assertRaises(producer.shared.GateError, producer.class_entries, jar({name: b"fixture"}))
        out = io.BytesIO()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(out, "w") as archive:
                archive.writestr("a/Type.class", b"one")
                archive.writestr("a/Type.class", b"two")
        self.assertRaises(producer.shared.GateError, producer.class_entries, out.getvalue())


class SelectedPrivateReferencesTest(unittest.TestCase):
    def test_root_published_actual_rows_are_collected_without_module_self_edges(self):
        path = Path(__file__).resolve().parents[2] / "docs/delivery/architecture-day/COORDINATOR/864e-private-mapper-raw-jdeps.json"
        evidence = json.loads(path.read_text())
        targets = {name: {} for name in evidence["class_index_exact_records"] if name.endswith("Mapper")}
        text = "\n".join(evidence["actual_target_direction_lines"])
        original = producer.parse_output(text, evidence["class_index_exact_records"], [Path("chengyinhub-system.jar")])
        current = producer.parse_output(text, evidence["class_index_exact_records"], [Path("chengyinhub-system.jar")], targets)
        self.assertEqual(original, current)
        self.assertEqual(5, len(current))
        self.assertEqual([], current[1])
        self.assertEqual([1, 1], sorted(len(refs) for refs in targets.values()))
        for target, refs in targets.items():
            ref = next(iter(refs.values()))
            self.assertEqual(target, ref["target_class"])
            self.assertEqual("chengyinhub-system", ref["source_owner"])
            self.assertEqual("chengyinhub-system", ref["target_owner"])
            self.assertEqual(evidence["class_index_exact_records"][target]["sha256"], ref["target_sha256"])


    def test_selected_collection_preserves_existing_nine_module_edges_and_five_return_values(self):
        # Synthetic module rows exercise format invariance, not actual CI graphs.
        classes = {m: "fixture." + m.split("-")[1] + ".Type" for m in MODULES}
        index = {name: {"owner": owner, "sha256": "0" * 64} for owner, name in classes.items()}
        path = Path(__file__).resolve().parents[2] / "docs/delivery/architecture-day/COORDINATOR/864e-private-mapper-raw-jdeps.json"
        actual = json.loads(path.read_text())
        index.update(actual["class_index_exact_records"])
        module_edges = [("admin", "common"), ("admin", "framework"), ("admin", "quartz"), ("admin", "system"),
                        ("framework", "common"), ("framework", "system"), ("generator", "common"),
                        ("quartz", "common"), ("system", "common")]
        rows = ["   %s -> %s %s.jar" % (classes["chengyinhub-" + a], classes["chengyinhub-" + b], "chengyinhub-" + b)
                for a, b in module_edges]
        text = "\n".join(rows + actual["actual_target_direction_lines"])
        inputs = [Path(m + ".jar") for m in MODULES]
        before = producer.parse_output(text, index, inputs)
        selected = {t: {} for t in actual["class_index_exact_records"] if t.endswith("Mapper")}
        after = producer.parse_output(text, index, inputs, selected)
        self.assertEqual(before, after)
        self.assertEqual(5, len(after))
        self.assertEqual(9, len(after[1]))
        self.assertTrue(all(a != b for a, b in after[1]))
        self.assertEqual(2, sum(map(len, selected.values())))


class PrivatePortsReadbackTest(unittest.TestCase):
    """Protocol/ZIP fixtures, deliberately NOT compiled bytecode or real jdeps."""
    git = CollectorTest.git
    make_artifacts = CollectorTest.make_artifacts
    runner = CollectorTest.runner
    collect = CollectorTest.collect

    def setUp(self):
        CollectorTest.setUp(self)
        self.ports = producer.shared.business_policy()["private_ports"]
        self.foreign = "example.system.ForeignConsumer"
        self.nested = next(iter(self.ports.values()))["consumer"] + "$Nested"
        self.system_jar = self.root / "chengyinhub-system/target/chengyinhub-system-1.jar"
        with zipfile.ZipFile(self.system_jar) as z:
            self.system_entries = {name: z.read(name) for name in z.namelist()}
        for name in [*self.ports, *(b["consumer"] for b in self.ports.values()), self.foreign, self.nested]:
            self.system_entries[name.replace(".", "/") + ".class"] = ("NOT_COMPILED_FIXTURE_" + name).encode()
        self.write_system()
        for target, binding in self.ports.items():
            self.stdout += "   " + binding["consumer"] + " -> " + target + " chengyinhub-system.jar\n"
        report = self.collect()
        self.assertTrue(report["static_graph_complete"], report)

    def write_system(self):
        classes = self.root / "chengyinhub-system/target/classes"
        for path in classes.rglob("*.class"):
            path.unlink()  # Only this test's TemporaryDirectory fixtures.
        for name, value in self.system_entries.items():
            path = classes / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(value)
        self.system_jar.write_bytes(jar(self.system_entries))
        with zipfile.ZipFile(self.boot) as z:
            entries = {name: z.read(name) for name in z.namelist()}
        entries["BOOT-INF/lib/chengyinhub-system-1.jar"] = self.system_jar.read_bytes()
        self.boot.write_bytes(jar(entries))
        value = json.loads(self.identity.read_text())
        value["jarSha256"] = producer.shared.digest(self.boot.read_bytes())
        self.identity.write_text(json.dumps(value))

    def check(self, ports=None):
        return producer.check_private_ports(self.root, self.boot, self.identity, self.output,
                                            self.env, self.ports if ports is None else ports)

    def replace_evidence(self, name, value):
        (self.output / name).write_bytes(value)
        report = json.loads((self.output / "observation.json").read_text())
        report["evidence_sha256"][name] = producer.shared.digest(value)
        (self.output / "observation.json").write_text(json.dumps(report))

    def change_report(self, key, value):
        report = json.loads((self.output / "observation.json").read_text())
        report[key] = value
        (self.output / "observation.json").write_text(json.dumps(report))

    def add_reference(self, source):
        target = next(iter(self.ports))
        text = (self.output / "jdeps.stdout.txt").read_text()
        self.replace_evidence("jdeps.stdout.txt", (text + "   " + source + " -> " + target + " chengyinhub-system.jar\n").encode())
        return target

    def assert_unknown(self, report):
        self.assertEqual("UNKNOWN", report["verdict"], report)
        self.assertFalse(report["coverage_complete"])
        self.assertTrue(report["review_items"])
        self.assertEqual([], report["findings"])

    def test_exact_consumers_pass_only_scoped_static_references_without_running_java(self):
        run = subprocess.run
        def git_only(args, **kwargs):
            self.assertEqual("git", args[0], "post-check must not execute Java/jdeps or other commands")
            return run(args, **kwargs)
        with patch.object(subprocess, "run", side_effect=git_only):
            report = self.check()
        self.assertEqual("PASS_SCOPED_COMPILED_REFERENCES", report["verdict"], report)
        self.assertTrue(report["coverage_complete"])
        self.assertTrue(report["evidence_verified"])
        self.assertEqual(2, len(report["references"]))
        self.assertEqual(2, len(self.calls), "only mocked producer version/analysis calls")
        self.assertEqual("TWO_DECLARED_TARGET_TYPES_ONLY", report["coverage"])
        self.assertNotIn("module_edges", report)
        self.assertNotIn("scc", report)

    def test_foreign_same_system_compiled_reference_is_blocked_with_no_XML_change(self):
        target = self.add_reference(self.foreign)
        report = self.check()
        self.assertEqual("BLOCKED", report["verdict"], report)
        self.assertEqual("PRIVATE_MAPPER_CONSUMER", report["findings"][0]["rule"])
        self.assertEqual(self.foreign, report["findings"][0]["source_class"])
        self.assertEqual(target, report["findings"][0]["target_class"])
        old = json.loads((self.output / "observation.json").read_text())
        self.assertEqual([["chengyinhub-admin", "chengyinhub-system"]], old["module_edges"])

    def test_nested_class_is_not_implicitly_allowed_by_service_prefix(self):
        self.add_reference(self.nested)
        report = self.check()
        self.assertEqual("BLOCKED", report["verdict"], report)
        self.assertEqual(self.nested, report["findings"][0]["source_class"])

    def test_missing_raw_or_empty_target_rows_are_unknown_not_empty_graph_pass(self):
        for text in ("", "\n".join(line for line in self.stdout.splitlines() if not any(t in line for t in self.ports))):
            with self.subTest(empty=not text):
                self.replace_evidence("jdeps.stdout.txt", text.encode())
                self.assert_unknown(self.check())
        (self.output / "jdeps.stdout.txt").unlink()
        self.assert_unknown(self.check())

    def test_forged_safe_candidate_JSON_does_not_hide_foreign_raw_reference(self):
        self.add_reference(self.foreign)
        self.change_report("references", {})
        self.change_report("findings", [])
        self.change_report("static_graph_complete", True)
        self.assertEqual("BLOCKED", self.check()["verdict"])

    def test_index_sha_or_owner_forgery_is_unknown_even_with_resigned_manifest(self):
        original = json.loads((self.output / "class-index.json").read_text())
        target = next(iter(self.ports))
        for field, value in (("sha256", "0" * 64), ("owner", "chengyinhub-admin")):
            with self.subTest(field=field):
                changed = copy.deepcopy(original)
                changed[target][field] = value
                self.replace_evidence("class-index.json", json.dumps(changed).encode())
                self.assert_unknown(self.check())

    def test_changed_input_bytes_cannot_be_approved_by_manifest_rehash(self):
        self.replace_evidence("inputs/chengyinhub-system.jar", b"FORGED_NOT_THE_PACKAGED_JAR")
        self.assert_unknown(self.check())

    def test_changed_actual_class_or_original_artifact_is_unknown(self):
        path = self.root / "chengyinhub-system/target/classes" / (next(iter(self.ports)).replace(".", "/") + ".class")
        path.write_bytes(b"different actual target class")
        self.assert_unknown(self.check())

    def test_missing_actual_target_class_is_unknown(self):
        target = next(iter(self.ports))
        del self.system_entries[target.replace(".", "/") + ".class"]
        self.write_system()
        self.output = self.output.with_name("new-observation")
        self.collect()
        self.assert_unknown(self.check())

    def test_missing_allowed_consumer_does_not_hide_definite_foreign_reference(self):
        consumer = next(iter(self.ports.values()))["consumer"]
        del self.system_entries[consumer.replace(".", "/") + ".class"]
        self.write_system()
        self.output = self.output.with_name("new-observation")
        self.collect()
        self.add_reference(self.foreign)
        report = self.check()
        self.assertEqual("BLOCKED", report["verdict"], report)
        self.assertFalse(report["coverage_complete"])

    def test_location_mismatch_unresolved_class_and_unparsed_lines_are_unknown(self):
        original = self.stdout
        target = next(iter(self.ports))
        for text in (original.replace("-> " + target + " chengyinhub-system.jar", "-> " + target + " external-1.jar"),
                     original.replace("-> " + target + " chengyinhub-system.jar", "-> " + target + " not found"),
                     original + "unrecognized JDK format\n"):
            with self.subTest(text=text[-80:]):
                self.replace_evidence("jdeps.stdout.txt", text.encode())
                self.assert_unknown(self.check())

    def test_definite_bad_reference_still_blocks_when_other_parse_coverage_unknown(self):
        self.add_reference(self.foreign)
        raw = (self.output / "jdeps.stdout.txt").read_bytes()
        self.replace_evidence("jdeps.stdout.txt", raw + b"unknown additional output\n")
        report = self.check()
        self.assertEqual("BLOCKED", report["verdict"], report)
        self.assertFalse(report["coverage_complete"])

    def test_raw_hash_mismatch_is_unknown_before_any_edges_are_trusted(self):
        (self.output / "jdeps.stdout.txt").write_text(self.stdout + "forged")
        self.assert_unknown(self.check())

    def test_source_tree_run_attempt_and_boot_identity_are_bound(self):
        for field in ("source_sha", "source_tree", "run_id", "run_attempt", "boot_artifact_sha256"):
            with self.subTest(field=field):
                original = (self.output / "observation.json").read_bytes()
                self.change_report(field, "forged")
                self.assert_unknown(self.check())
                (self.output / "observation.json").write_bytes(original)
        value = json.loads(self.identity.read_text())
        value["jarSha256"] = "0" * 64
        self.identity.write_text(json.dumps(value))
        self.assert_unknown(self.check())

    def test_untrusted_local_context_and_dirty_tree_are_unknown(self):
        original = self.env.copy()
        self.env.pop("GITHUB_ACTIONS")
        self.assert_unknown(self.check())
        self.env = original
        (self.root / "untracked-source.java").write_text("dirty")
        self.assert_unknown(self.check())

    def test_jdk_tool_version_and_invocation_cannot_be_replaced(self):
        original = (self.output / "invocation.json").read_bytes()
        for token in ("-filter:none", "-verbose:class"):
            with self.subTest(token=token):
                data = json.loads(original)
                data["argv"].remove(token)
                self.replace_evidence("invocation.json", json.dumps(data).encode())
                self.assert_unknown(self.check())
        self.replace_evidence("invocation.json", original)
        self.replace_evidence("jdeps-version.txt", b"17.0.13\n")
        self.assert_unknown(self.check())

    def test_wrong_input_path_locale_or_budget_is_unknown(self):
        original = (self.output / "invocation.json").read_bytes()
        for field, value in (("locale", "other"), ("timeout_seconds", 0)):
            with self.subTest(field=field):
                data = json.loads(original)
                data[field] = value
                self.replace_evidence("invocation.json", json.dumps(data).encode())
                self.assert_unknown(self.check())
        data = json.loads(original)
        data["argv"][-1] = "/tmp/not-the-actual-input.jar"
        self.replace_evidence("invocation.json", json.dumps(data).encode())
        self.assert_unknown(self.check())

    def test_missing_or_foreign_manifest_files_do_not_read_arbitrary_paths(self):
        original = (self.output / "observation.json").read_bytes()
        for change in ("missing", "foreign"):
            with self.subTest(change=change):
                data = json.loads(original)
                if change == "missing": del data["evidence_sha256"]["class-index.json"]
                else: data["evidence_sha256"]["../../outside"] = "0" * 64
                (self.output / "observation.json").write_text(json.dumps(data))
                self.assert_unknown(self.check())

    def test_symlink_observation_and_evidence_are_unknown(self):
        for name in ("observation.json", "class-index.json"):
            with self.subTest(name=name):
                path = self.output / name
                saved = path.with_suffix(".saved")
                path.rename(saved)
                path.symlink_to(saved.name)
                self.assert_unknown(self.check())
                path.unlink()
                saved.rename(path)

    def test_empty_binding_or_replaced_JDK_binary_is_unknown(self):
        self.assert_unknown(self.check({}))
        self.tool.write_bytes(b"DIFFERENT_SYNTHETIC_TOOL")
        self.assert_unknown(self.check())

    def test_cli_reports_pass_blocked_unknown_as_0_1_2_and_persists_each(self):
        output = self.boot.parent / "private-ports.json"
        args = ["gate.py", "compiled-ports", "--source", str(self.root), "--artifact", str(self.boot),
                "--identity", str(self.identity), "--observation", str(self.output), "--output", str(output)]
        for expected in (0, 1, 2):
            if expected == 1: self.add_reference(self.foreign)
            if expected == 2: (self.output / "jdeps.stdout.txt").unlink()
            with patch.dict(os.environ, self.env, clear=True), patch.object(sys, "argv", args), redirect_stdout(io.StringIO()) as stream:
                self.assertEqual(expected, producer.shared.main())
            self.assertEqual(json.loads(stream.getvalue()), json.loads(output.read_text()))
            if expected == 2: self.assert_unknown(json.loads(output.read_text()))


class WorkflowWiringTest(unittest.TestCase):
    def test_producer_follows_bound_packaged_artifact_and_reuses_its_upload(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml").read_text()
        describe = workflow.index("      - name: Describe the tested Boot Jar")
        upload = workflow.index("      - name: Preserve tested Boot Jar for test VPS UAT", describe)
        next_step = workflow.index("\n      - ", describe + 1)
        text = workflow[describe:next_step]  # Producer must stay in Describe itself.
        self.assertLess(text.index("PYARTIFACT\n"), text.index("collect_jdeps.py"))
        for option in ("--source .", "--artifact chengyinhub-admin/target/chengyinhub-admin.jar",
                       "--identity chengyinhub-admin/target/chengyinhub-admin.build.json",
                       "--output chengyinhub-admin/target/architecture-jdeps"):
            self.assertIn(option, text)
        self.assertIn("chengyinhub-admin/target/architecture-jdeps/", workflow[upload:workflow.index("      - name: Stop MySQL", upload)])
        self.assertIn("backend-test-reports-${{ github.run_id }}-${{ github.run_attempt }}", workflow)
        self.assertIn("gate.py manual-base --source . --candidate", workflow)
        self.assertIn("max-parallel: 20", workflow)

    def test_describe_shell_parses_without_executing_java_or_any_workflow_step(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml").read_text()
        start = workflow.index("      - name: Describe the tested Boot Jar")
        start = workflow.index("        run: |\n", start) + len("        run: |\n")
        end = workflow.index("      - ", start)  # The next step may be root's post-build check.
        script = "\n".join(line[10:] for line in workflow[start:end].splitlines()) + "\n"
        result = subprocess.run(["bash", "-n"], input=script, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()

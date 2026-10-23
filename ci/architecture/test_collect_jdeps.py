"""ZIP/protocol fixtures only. No Java, javac, jdeps, database or network runs."""
import importlib.util
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
        self.root = Path(self.temp.name)
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


class WorkflowWiringTest(unittest.TestCase):
    def test_producer_follows_bound_packaged_artifact_and_reuses_its_upload(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml").read_text()
        describe = workflow.index("      - name: Describe the tested Boot Jar")
        upload = workflow.index("      - name: Preserve tested Boot Jar for test VPS UAT", describe)
        text = workflow[describe:upload]
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
        end = workflow.index("      - name: Preserve tested Boot Jar", start)
        script = "\n".join(line[10:] for line in workflow[start:end].splitlines()) + "\n"
        result = subprocess.run(["bash", "-n"], input=script, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()

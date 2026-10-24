"""Source/prerequisite controls only: NEVER execute Java or fabricate JAR/raw/class evidence."""
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SPEC = importlib.util.spec_from_file_location("real_compiled_controls", HERE / "compiled_controls.py")
controls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(controls)


class CompiledControlSourceTest(unittest.TestCase):
    def test_three_actual_source_fixtures_only_insert_one_uncalled_method(self):
        self.assertEqual({"sdk-router", "dto-facade", "common-framework"}, set(controls.CASES))
        for module, source, target, owner in controls.CASES.values():
            with self.subTest(owner=owner):
                original = (ROOT / module / "src/main/java" / (source.replace(".", "/") + ".java")).read_bytes()
                generated = controls.control_source(original, target)
                position = original.rfind(b"}")
                self.assertTrue(generated.startswith(original[:position]))
                self.assertTrue(generated.endswith(original[position:]))
                self.assertEqual(1, generated.count(controls.MARKER.encode()))
                self.assertIn(("return " + target + ".class;").encode(), generated)
                self.assertEqual(original, (ROOT / module / "src/main/java" / (source.replace(".", "/") + ".java")).read_bytes())

    def test_duplicate_changed_or_injected_control_input_is_refused(self):
        for data, target in [(b"not a final class body", "safe.Target"),
                             (b"class Test { __architectureNegativeControl }", "safe.Target"),
                             (b"class Test {}", "safe.Target; injected()")]:
            with self.subTest(data=data):
                with self.assertRaises(ValueError): controls.control_source(data, target)

    def test_without_actual_ci_prerequisites_it_writes_unknown_without_launching_any_tool(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "chengyinhub-admin/target/controls"
            argv = ["compiled_controls.py", "--source", str(root), "--artifact", str(root / "missing.jar"),
                    "--identity", str(root / "missing.json"), "--observation", str(root / "missing-raw"),
                    "--verifier", str(root / "missing-verifier"), "--output", str(output)]
            with patch.dict(os.environ, {}, clear=True), patch.object(sys, "argv", argv), \
                    patch.object(controls.subprocess, "run", side_effect=AssertionError("no Java/tool allowed")) as run, \
                    patch.object(controls.subprocess, "check_output", side_effect=AssertionError("no Git/tool allowed")) as check, \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(2, controls.main())
                run.assert_not_called(); check.assert_not_called()
            result = json.loads((output / "controls.json").read_text())
            self.assertEqual("UNKNOWN", result["verdict"])
            self.assertEqual({}, result["cases"])
            self.assertNotIn("evidence_sha256", result)
            self.assertEqual(["controls.json"], [p.name for p in output.iterdir()])

    def test_output_outside_owned_ignored_target_is_refused_before_any_tool(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "external"
            argv = ["compiled_controls.py", "--source", str(root), "--artifact", "missing", "--identity", "missing",
                    "--observation", "missing", "--verifier", "missing", "--output", str(output)]
            with patch.object(sys, "argv", argv), patch.object(controls.subprocess, "run") as run:
                with self.assertRaises(ValueError): controls.main()
                run.assert_not_called()
            self.assertFalse(output.exists())

    def test_wrong_repository_short_identity_and_invalid_attempt_are_not_ci_permission(self):
        good = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "liboyang42-cpu/hengyin",
                "GITHUB_SHA": "1" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
        controls.require_ci(good)  # Pure metadata check only; not evidence of a real CI/build.
        for key, value in [("GITHUB_ACTIONS", "false"), ("GITHUB_REPOSITORY", "unapproved/repo"),
                           ("GITHUB_SHA", "short"), ("GITHUB_RUN_ATTEMPT", "0")]:
            with self.subTest(key=key):
                with self.assertRaises(ValueError): controls.require_ci({**good, key: value})

    def test_leaf_only_without_ci_cannot_launch_tools_or_claim_an_application_verdict(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); output = root / "chengyinhub-admin/target/leaf"
            argv = ["compiled_controls.py", "--leaf-only", "--source", str(root), "--verifier", "missing", "--output", str(output)]
            with patch.dict(os.environ, {}, clear=True), patch.object(sys, "argv", argv), \
                    patch.object(controls.subprocess, "run", side_effect=AssertionError("no Java")) as run, \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(2, controls.main()); run.assert_not_called()
            result = json.loads((output / "controls.json").read_text())
            self.assertEqual("UNKNOWN", result["verdict"])
            self.assertEqual("REAL_JDK21_LEAF_ONLY_NOT_APPLICATION_ACCEPTANCE", result["kind"])
            self.assertEqual({}, result["cases"])

    def test_empty_class_and_interface_fixture_sources_have_no_business_calls_or_fabricated_edges(self):
        self.assertEqual({"architecture.leafcontrol.EmptyClass", "architecture.leafcontrol.EmptyInterface"}, set(controls.LEAF_SOURCES))
        for name, data in controls.LEAF_SOURCES.items():
            self.assertIn(name.rsplit(".", 1)[1].encode() + b" {}", data)
            self.assertNotIn(b"com.chengyinhub", data)
            self.assertNotIn(b".class", data)
        self.assertIn(b"public interface", controls.LEAF_SOURCES["architecture.leafcontrol.EmptyInterface"])

    def test_wrong_or_dirty_frozen_verifier_is_refused_before_loading_any_code(self):
        for wrong in ("refs/heads/master", "5caf6b288c8df612d8420e4007032e4d9568e18e", "bd33ed0be7fafaf89daaec2eb772c15b034d3ec0", "0" * 40):
            with self.subTest(wrong=wrong), patch.object(controls, "git", return_value=wrong.encode()), \
                    patch.object(controls.importlib.util, "spec_from_file_location", side_effect=AssertionError("must not load untrusted code")):
                with self.assertRaises(ValueError): controls.load_verifier(Path("unused"))
        with patch.object(controls, "git", side_effect=[controls.VERIFIER.encode(), b" M ci/architecture/owners.json"]), \
                patch.object(controls.importlib.util, "spec_from_file_location", side_effect=AssertionError("must not load dirty code")):
            with self.assertRaises(ValueError): controls.load_verifier(Path("unused"))


if __name__ == "__main__":
    unittest.main()

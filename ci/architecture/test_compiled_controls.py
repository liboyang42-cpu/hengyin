"""Source/prerequisite controls only: NEVER execute Java or fabricate JAR/raw/class evidence."""
import importlib.util
import io
import json
import os
from pathlib import Path
import re
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

    def test_exact_clean_verifier_matches_both_workflow_callers_and_loads_once(self):
        expected = "9782954919075c4a13d209f5f3ab4e3ff8f4e9e8"
        self.assertEqual(expected, controls.VERIFIER)
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertEqual([expected, expected], re.findall(r"(?m)^\s+ref: ([0-9a-f]{40})$", workflow))
        module = object()
        with patch.object(controls, "git", side_effect=[expected.encode(), b""]), \
                patch.object(controls.importlib.util, "spec_from_file_location") as spec, \
                patch.object(controls.importlib.util, "module_from_spec", return_value=module) as factory:
            self.assertIs(module, controls.load_verifier(Path("unused")))
            factory.assert_called_once_with(spec.return_value)
            spec.return_value.loader.exec_module.assert_called_once_with(module)

    def test_superseded_reviewed_verifiers_are_refused_before_loading_any_code(self):
        for wrong in ("2181ad835d2c7e6b5d5ca0f9692849c890c7d7d1", "92fd81c313766b07087858f02967f430115642ab"):
            with self.subTest(wrong=wrong), patch.object(controls, "git", return_value=wrong.encode()), \
                    patch.object(controls.importlib.util, "spec_from_file_location", side_effect=AssertionError("must not load superseded code")):
                with self.assertRaises(ValueError): controls.load_verifier(Path("unused"))

    def test_wrong_or_dirty_frozen_verifier_is_refused_before_loading_any_code(self):
        for wrong in ("refs/heads/master", "9637bbcb21b822afec83464f8309bef92ab2bc68", "d58288edc65e4c57bc98ef61253fb084f38642f1", "16ce2a260f59a377dfac337749435d04f1d72ab7", "4a14940614d6ebe8d6d28a8b5314fef366541bc9", "4255beb508b64bce639e25d4fc7f16742cd4946a", "b94428f0242be4a33332d05e29bb7d0a7e02c734", "c2db98326366c52e640c2b1c421b0fb6bde611fa", "0297c2cc9d093e8127103da26325e7da0f1f18e1", "3d3a63d4120c83eaecd8b2bf70a12b51d5f4988c", "5caf6b288c8df612d8420e4007032e4d9568e18e", "0e00ce639dea8e7f266dd326878532f45c59a032", "bd33ed0be7fafaf89daaec2eb772c15b034d3ec0", "f8531bfd6cca90d88f9c281d1904a170b635f990", "0979b03b594b5b5bbc4ad500c89bca6b3d353d70", "e9a9db4949e5bf8d51647ccb3d44ea5684960901", "8a5a781edeaec0dc7e24a460a0fdba1394252ded", "f15fcf2161868732a188d9c2892a4c31bba496b9", "20f0ed72f4dcdb1db73758095dd896a33f702c72", "5c2e2d8c46f17fec3a3e61dfe7e020577a187cc1", "beffa2023a68ffba2da5fce35f00e697252e72f4", "f35cf5cc49215017669061950e39071bbb5ce2fa", "f864f4f4311ef6106f78d15b4c0a2276688e3dfc", "e5b5818f2230eb9e81d2a825960c0470ac1640ec", "0" * 40):
            with self.subTest(wrong=wrong), patch.object(controls, "git", return_value=wrong.encode()), \
                    patch.object(controls.importlib.util, "spec_from_file_location", side_effect=AssertionError("must not load untrusted code")):
                with self.assertRaises(ValueError): controls.load_verifier(Path("unused"))
        with patch.object(controls, "git", side_effect=[controls.VERIFIER.encode(), b" M ci/architecture/owners.json"]), \
                patch.object(controls.importlib.util, "spec_from_file_location", side_effect=AssertionError("must not load dirty code")):
            with self.assertRaises(ValueError): controls.load_verifier(Path("unused"))


if __name__ == "__main__":
    unittest.main()

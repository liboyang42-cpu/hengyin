import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("native_design", ROOT / "scripts/native-design.py")
design = importlib.util.module_from_spec(spec)
spec.loader.exec_module(design)


class NativeDesignTests(unittest.TestCase):
    def test_reference_canvas_is_not_a_runtime_screen_conversion(self):
        self.assertEqual(design.points("--cy-space-4", {"--cy-space-4": "32rpx"}, 375), "16")
        self.assertEqual(design.points("x", {"x": "12rpx"}, 375), "6")
        with self.assertRaises(ValueError):
            design.points("x", {"x": "calc(32rpx * var(--scale))"}, 375)

    def test_only_plain_page_scope_is_exported_and_duplicates_are_rejected(self):
        source = "/* page { --cy-x: 99rpx; } */\npage { --cy-x: 8rpx; }\n.theme-light { --cy-x: 16rpx; }"
        self.assertEqual(design.root_tokens(source), {"--cy-x": "8rpx"})
        with self.assertRaises(ValueError):
            design.root_tokens("page { --cy-x: 8rpx; --cy-x: 12rpx; }")

    def test_real_tokens_keep_native_dynamic_type_colors_and_existing_accent(self):
        config = design.load(ROOT / "design/native/design-map.json")
        tokens = design.root_tokens((ROOT / "chengyinhub-xcx/style/tokens.wxss").read_text())
        output = design.render(config, tokens)
        for value in ["static let page: CGFloat = 16", "static let xl: CGFloat = 20", "static let between: CGFloat = 14", "static let content: CGFloat = 20", "Font.body", "Color.primary", ".secondarySystemGroupedBackground", "red:196.0/255,green:181.0/255,blue:253.0/255", "red:109.0/255,green:40.0/255,blue:217.0/255"]:
            self.assertIn(value, output)
        self.assertNotIn("Font.system(size:", output)
        tokens["--cy-page-x"] = "40rpx"
        self.assertIn("static let page: CGFloat = 20", design.render(config, tokens))

    def test_one_sided_or_unmapped_page_changes_fail(self):
        pairs = {"home": {"mini": ["pages/index/"], "ios": ["App/Home.swift"]}}
        self.assertTrue(design.pair_errors(pairs, ["pages/index/index.wxss"], []))
        self.assertTrue(design.pair_errors(pairs, ["pages/new/index.wxss"], ["App/Home.swift"]))
        self.assertEqual(design.pair_errors(pairs, ["pages/index/index.wxss"], ["App/Home.swift"]), [])

    def test_reviewed_shared_counterpart_does_not_activate_unaffected_groups(self):
        pairs = {
            "team-invitation": {"mini": ["pages/team/"], "ios": ["App/TicketWalletView.swift"]},
            "ticket-wallet": {"mini": ["subpackageMember/signup/"], "ios": ["App/TicketWalletView.swift"]},
        }
        wallet, team, shared = "subpackageMember/signup/index.js", "pages/team/join/index.js", "App/TicketWalletView.swift"
        self.assertEqual(design.pair_errors(pairs, [wallet], [], reviewed_ios_paths=[shared]), [])
        self.assertEqual(design.pair_errors(pairs, [team], [], reviewed_ios_paths=[shared]), [])
        self.assertTrue(any("ticket-wallet:" in error for error in design.pair_errors(pairs, [wallet], [])))
        # A real edit to the shared App file still activates both mapped groups.
        for mini, reviewed in [([], [wallet]), ([wallet], [])]:
            errors = design.pair_errors(pairs, mini, [shared], reviewed_mini_paths=reviewed)
            self.assertTrue(any("team-invitation:" in error for error in errors))
        self.assertEqual(design.pair_errors(pairs, [], [shared], reviewed_mini_paths=[wallet, team]), [])
        self.assertTrue(any("unmapped path" in error for error in
            design.pair_errors(pairs, [wallet], [], reviewed_ios_paths=["App/Unknown.swift"])))

    def test_hook_reads_staged_source_even_when_worktree_differs(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            (repo / "tokens").write_text("staged")
            subprocess.run(["git", "-C", str(repo), "add", "tokens"], check=True)
            (repo / "tokens").write_text("unstaged")
            self.assertEqual(design.staged_text(repo, "tokens"), "staged")
            with self.assertRaises(ValueError):
                design.staged_text(repo, "missing")

    def test_install_preserves_existing_hook_and_is_idempotent(self):
        spec = importlib.util.spec_from_file_location("hooks", ROOT / "scripts/install-native-design-hooks.py")
        hooks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hooks)
        original = "#!/bin/bash\nset -e\nexisting_security_check\nexit 0\n"
        result = hooks.hook_text(original, "token_check")
        self.assertEqual(result.replace(hooks.BEGIN + "token_check || exit $?\n" + hooks.END, ""), original)
        self.assertEqual(hooks.hook_text(result, "token_check"), result)
        with self.assertRaises(ValueError):
            hooks.hook_text("#!/usr/bin/env python3\nprint('keep')\n", "token_check")

    def test_foreign_git_reads_keep_the_callers_alternate_index(self):
        with tempfile.TemporaryDirectory() as directory:
            caller, foreign = [Path(directory) / name for name in ("caller", "foreign")]
            for repo in (caller, foreign):
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
                (repo / "tokens").write_text(repo.name + " staged")
                subprocess.run(["git", "-C", str(repo), "add", "tokens"], check=True)
            subprocess.run(["git", "-C", str(foreign), "-c", "user.name=Native fixture", "-c", "user.email=native-fixture@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "approved foreign source"], check=True)
            approved = subprocess.check_output(["git", "-C", str(foreign), "rev-parse", "HEAD"], text=True).strip()
            alternate = Path(directory) / "hook-index"
            alternate.write_bytes((caller / ".git/index").read_bytes())
            (caller / "tokens").write_text("caller alternate staged")
            environment = dict(os.environ, GIT_INDEX_FILE=str(alternate))
            subprocess.run(["git", "-C", str(caller), "add", "tokens"], env=environment, check=True)
            (caller / "tokens").write_text("caller unstaged")
            (foreign / "tokens").write_text("foreign unstaged")
            previous = Path.cwd()
            try:
                os.chdir(caller)
                with patch.dict(os.environ, {"GIT_DIR": str(caller / ".git"), "GIT_WORK_TREE": str(caller), "GIT_INDEX_FILE": str(alternate)}):
                    self.assertEqual(design.staged_text(caller, "tokens"), "caller alternate staged")
                    self.assertEqual(design.staged_text(foreign, "tokens"), "foreign staged")
                    design.verify_baseline(foreign, {"ref": "fixture", "commit": approved})
            finally:
                os.chdir(previous)

    def test_staged_checks_reject_a_repaired_worktree_with_bad_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "mini"
            ios = Path(directory) / "ios"
            for repo in [root, ios]:
                repo.mkdir()
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(ios), "-c", "user.name=Native fixture", "-c", "user.email=native-fixture@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-qm", "approved source"], check=True)
            approved = subprocess.check_output(["git", "-C", str(ios), "rev-parse", "HEAD"], text=True).strip()
            for relative in ["scripts/native-design.py", "design/native/design-map.json", "design/native/page-pairs.json", "chengyinhub-xcx/style/tokens.wxss"]:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text((ROOT / relative).read_text())
            (root / "design/native/baseline.json").write_text(json.dumps({"ref": "fixture", "commit": approved}))
            pairs = {"design-system": {"mini": ["style/tokens.wxss"], "ios": ["App/QuestifyPalette.swift"]}}
            (root / "design/native/page-pairs.json").write_text(json.dumps(pairs))
            (ios / "App").mkdir()
            (ios / "Questify.xcodeproj").mkdir()
            palette = ios / "App/QuestifyPalette.swift"
            palette.write_text(design.render(design.load(ROOT / "design/native/design-map.json"), design.root_tokens((ROOT / "chengyinhub-xcx/style/tokens.wxss").read_text())))
            for repo in [root, ios]:
                subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            command = [sys.executable, str(root / "scripts/native-design.py"), "check", "--ios-root", str(ios), "--staged-side"]
            self.assertEqual(subprocess.run(command + ["mini"], capture_output=True).returncode, 0)
            self.assertEqual(subprocess.run(command + ["ios"], capture_output=True).returncode, 0)
            good = palette.read_text()
            palette.write_text(good.replace("static let page: CGFloat = 16", "static let page: CGFloat = 17"))
            subprocess.run(["git", "-C", str(ios), "add", "."], check=True)
            palette.write_text(good)
            result = subprocess.run(command + ["ios"], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("drifted", result.stderr)
            with self.assertRaisesRegex(ValueError, "not based on approved"):
                design.verify_baseline(ios, {"ref": "wrong source", "commit": "0" * 40})
            design.verify_baseline(ios, {"ref": "fixture", "commit": approved})
            review = command[:-1] + ["--ios-path", "App/QuestifyPalette.swift", "--reviewed-mini-path", "style/tokens.wxss"]
            result = subprocess.run(review, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("require --review-note", result.stderr)
            result = subprocess.run(review + ["--review-note", "Mini already uses the shared token"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("not edits", result.stdout)
            result = subprocess.run(command[:-1] + ["--mini-path", "../outside.wxss"], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Not an existing counterpart", result.stderr)
            # A page-only commit must also detect an already-stale generated file in the index.
            pairs["home"] = {"mini": ["pages/index/"], "ios": ["App/Home.swift"]}
            (root / "design/native/page-pairs.json").write_text(json.dumps(pairs))
            home = root / "chengyinhub-xcx/pages/index/index.wxss"
            home.parent.mkdir(parents=True)
            home.write_text(".home { padding: 8rpx; }\n")
            native_home = ios / "App/Home.swift"
            native_home.write_text("struct Home {}\n")
            palette.write_text(good.replace("static let page: CGFloat = 16", "static let page: CGFloat = 17"))
            for repo in [root, ios]:
                subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
                subprocess.run(["git", "-C", str(repo), "-c", "user.name=Native fixture", "-c", "user.email=native-fixture@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "fixture baseline"], check=True)
            palette.write_text(good)
            native_home.write_text("struct Home { let value = 1 }\n")
            subprocess.run(["git", "-C", str(ios), "add", "App/Home.swift"], check=True)
            result = subprocess.run(command + ["ios"], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("drifted", result.stderr)


if __name__ == "__main__":
    unittest.main()

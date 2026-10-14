"""Temporary-directory failure must never change source files or their index."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TempSafetyTest(unittest.TestCase):
    def test_failed_empty_and_source_directory_are_rejected_without_writes(self):
        for output, status in (("", 1), ("", 0), ("source", 0), ("inside", 0)):
            with self.subTest(output=output, status=status), tempfile.TemporaryDirectory() as temp:
                base = Path(temp)
                repo = base / "source"
                script_dir = repo / "chengyinhub-xcx/scripts"
                script_dir.mkdir(parents=True)
                script = script_dir / "ds-hardcode-gate-selftest.sh"
                shutil.copyfile(ROOT / "chengyinhub-xcx/scripts/ds-hardcode-gate-selftest.sh", script)
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
                page = repo / "chengyinhub-xcx/pages/publish/fabu"
                page.mkdir(parents=True)
                for name in ("index.js", "index.wxss"):
                    (page / name).write_text("source canary\n")
                subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
                index = (repo / ".git/index").read_bytes()
                inside = repo / "cy-ds-selftest.injected"
                inside.mkdir()
                returned = str(inside if output == "inside" else repo) if output else ""
                fake_bin = base / "bin"
                fake_bin.mkdir()
                fake = fake_bin / "mktemp"
                fake.write_text("#!/bin/sh\nprintf '%s\\n' '" + returned + "'\nexit " + str(status) + "\n")
                fake.chmod(0o755)
                env = dict(os.environ, PATH=str(fake_bin) + os.pathsep + os.environ["PATH"])
                result = subprocess.run(["bash", str(script)], cwd=repo, env=env, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(index, (repo / ".git/index").read_bytes())
                for name in ("index.js", "index.wxss"):
                    self.assertEqual("source canary\n", (page / name).read_text())

    def test_xcx_check_rejects_unsafe_scratch_directory_before_running_gates(self):
        for output, status in (("", 1), ("", 0), ("source", 0), ("inside", 0)):
            with self.subTest(output=output, status=status), tempfile.TemporaryDirectory() as temp:
                base = Path(temp)
                repo = base / "source"
                (repo / "ci").mkdir(parents=True)
                script = repo / "ci/xcx-check.sh"
                shutil.copyfile(ROOT / "ci/xcx-check.sh", script)
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
                page = repo / "chengyinhub-xcx/pages/publish/fabu"
                page.mkdir(parents=True)
                (page / "index.js").write_text("source canary\n")
                subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
                index = (repo / ".git/index").read_bytes()
                inside = repo / "cy-xcx-check.injected"
                inside.mkdir()
                returned = str(inside if output == "inside" else repo) if output else ""
                fake_bin = base / "bin"
                fake_bin.mkdir()
                # node 被替换成记录调用的桩：任何子门禁一旦被执行即可从记录文件看出来。
                calls = base / "node-calls"
                (fake_bin / "node").write_text("#!/bin/sh\necho called >> '" + str(calls) + "'\nexit 0\n")
                (fake_bin / "mktemp").write_text("#!/bin/sh\nprintf '%s\\n' '" + returned + "'\nexit " + str(status) + "\n")
                for tool in ("node", "mktemp"):
                    (fake_bin / tool).chmod(0o755)
                env = dict(os.environ, PATH=str(fake_bin) + os.pathsep + os.environ["PATH"])
                result = subprocess.run(["bash", str(script)], cwd=repo, env=env, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(calls.exists(), "unsafe scratch directory must stop before any gate runs")
                self.assertEqual(index, (repo / ".git/index").read_bytes())
                self.assertEqual("source canary\n", (page / "index.js").read_text())
                self.assertEqual(["cy-xcx-check.injected"], sorted(p.name for p in inside.parent.iterdir() if p.name.startswith("cy-xcx-check")))
                self.assertEqual([], list(inside.iterdir()))


if __name__ == "__main__":
    unittest.main()

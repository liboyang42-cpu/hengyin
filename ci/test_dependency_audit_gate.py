#!/usr/bin/env python3
import json
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
GATE = ROOT / "ci" / "dependency-audit-gate.py"


class DependencyAuditGateTest(unittest.TestCase):
    def run_gate(self, report):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", encoding="utf-8") as output:
            json.dump(report, output)
            output.flush()
            return subprocess.run(
                ["python3", str(GATE), output.name],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

    def test_critical_and_high_findings_block(self):
        result = self.run_gate({"matches": [
            {"vulnerability": {"severity": "High"}},
            {"vulnerability": {"severity": "Critical"}},
        ]})
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_medium_finding_does_not_block_critical_high_gate(self):
        result = self.run_gate({"matches": [
            {"vulnerability": {"severity": "Medium"}},
        ]})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_missing_or_malformed_report_fails_closed(self):
        result = self.run_gate({"matches": [{"vulnerability": {}}]})
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_scanner_error_report_fails_closed(self):
        result = self.run_gate({"matches": [], "errors": ["vulnerability database unavailable"]})
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
import json
import pathlib
import re
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
GATE = ROOT / "ci" / "dependency-audit-gate.py"


class DependencyAuditGateTest(unittest.TestCase):
    def test_scan_includes_packaged_backend_dependencies(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        job = workflow.split("  dependency_audit:\n", 1)[1].split("\n  migration_layout:", 1)[0]
        build = job.index("mvn -B -q -pl chengyinhub-admin -am -DskipTests package")
        artifact = job.index("test -s chengyinhub-admin/target/chengyinhub-admin.jar")
        scan = job.index("- name: Generate SBOM (syft)")
        self.assertLess(build, artifact)
        self.assertLess(artifact, scan)
        self.assertIn("path: .", job[scan:])

    def test_backend_workflow_jdks_match_java21_target(self):
        for filename in ("ci.yml", "funds-mysql-it.yml", "deploy.yml"):
            workflow = (ROOT / ".github/workflows" / filename).read_text()
            versions = re.findall(r"java-version:\s*['\"]?(\d+)", workflow)
            self.assertTrue(versions, filename)
            self.assertEqual({"21"}, set(versions), filename)

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

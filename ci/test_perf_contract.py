#!/usr/bin/env python3
"""Contract tests for the P3-1D performance harness.

These tests are intentionally local-only: they execute the harness in dry-run or
validation modes and never open a network connection.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import sys
import unittest
import unittest.mock
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
PERF_DIR = REPO_ROOT / "ci" / "perf"
HARNESS = PERF_DIR / "perf_harness.py"
MANIFEST = PERF_DIR / "manifest.json"


class PerfHarnessContractTest(unittest.TestCase):
    def run_harness(self, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        process_env = os.environ.copy()
        process_env.update(env or {})
        process_env["PATH"] = process_env.get("PATH", "")
        return subprocess.run(
            [sys.executable, str(HARNESS), *args],
            cwd=REPO_ROOT,
            env=process_env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_requires_explicit_perf_base_url(self) -> None:
        result = self.run_harness("read", env={"PERF_BASE_URL": ""})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PERF_BASE_URL_REQUIRED", result.stderr)

    def test_rejects_production_targets_by_gate_name(self) -> None:
        blocked_targets = (
            "https://www.chengyinhub.com",
            "https://api.chengyinhub.com",
        )

        for target in blocked_targets:
            with self.subTest(target=target):
                result = self.run_harness("read", env={"PERF_BASE_URL": target})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("PRODUCTION_TARGET_BLOCKED", result.stderr)

        # Production IPs are not written in plaintext: the env denylist and the
        # SHA-256 path are exercised with a TEST-NET fixture host instead.
        fixture_host = "203.0.113.7"
        result = self.run_harness("read", env={
            "PERF_BASE_URL": f"http://{fixture_host}:8080",
            "CHENGYIN_FORBIDDEN_DB_HOSTS": f"198.51.100.1, {fixture_host}",
        })
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PRODUCTION_TARGET_BLOCKED", result.stderr)

        sys.path.insert(0, str(PERF_DIR))
        self.addCleanup(sys.path.remove, str(PERF_DIR))
        import perf_harness

        denylist = frozenset({hashlib.sha256(fixture_host.encode("utf-8")).hexdigest()})
        self.assertTrue(perf_harness.is_blocked_host(fixture_host, denylist))
        self.assertFalse(perf_harness.is_blocked_host(fixture_host, frozenset()))
        self.assertEqual(frozenset({
            "d8c695fe7dd5f6ffc048b72c96621f7e7d13d3ec23b76aab523514183e7474d9",
            "ce1a8527392b68bab1ab6a91238a5e6909cbbb937b9eb077d1025499a59f03fe",
        }), perf_harness.BLOCKED_HOST_SHA256)
        with unittest.mock.patch.object(perf_harness, "BLOCKED_HOST_SHA256", denylist), \
                unittest.mock.patch.dict(os.environ, {"PERF_BASE_URL": f"http://{fixture_host}:8080"}):
            with self.assertRaises(perf_harness.GateFailure) as raised:
                perf_harness.base_url_from_env()
        self.assertEqual("PRODUCTION_TARGET_BLOCKED", raised.exception.gate)

    def test_no_dry_run_is_locked_and_missing_tools_fail_closed(self) -> None:
        empty_path = tempfile.mkdtemp(prefix="perf-empty-path-")
        self.addCleanup(shutil.rmtree, empty_path)

        locked = self.run_harness(
            "write-k6",
            "--no-dry-run",
            env={"PERF_BASE_URL": "http://127.0.0.1:18080", "PATH": empty_path},
        )
        self.assertNotEqual(locked.returncode, 0)
        self.assertIn("DRY_RUN_LOCK", locked.stderr)

        missing_tool = self.run_harness(
            "write-k6",
            "--no-dry-run",
            env={
                "PERF_BASE_URL": "http://127.0.0.1:18080",
                "PERF_ALLOW_WRITE_LOAD": "I_UNDERSTAND_THIS_IS_NOT_PRODUCTION",
                "PERF_PAYMENT_MODE": "stub",
                "PATH": empty_path,
            },
        )
        self.assertNotEqual(missing_tool.returncode, 0)
        self.assertIn("TOOL_MISSING:k6", missing_tool.stderr)

        missing_wrk2 = self.run_harness(
            "read",
            "--no-dry-run",
            env={
                "PERF_BASE_URL": "http://127.0.0.1:18080",
                "PERF_WRK_THREADS": "1",
                "PERF_WRK_CONNECTIONS": "1",
                "PERF_DURATION": "1s",
                "PERF_RATE_RPS": "1",
                "PATH": empty_path,
            },
        )
        self.assertNotEqual(missing_wrk2.returncode, 0)
        self.assertIn("TOOL_MISSING:wrk2", missing_wrk2.stderr)

    def test_manifest_contains_only_approved_thresholds(self) -> None:
        result = self.run_harness("validate-manifest", env={"PERF_BASE_URL": "http://127.0.0.1:18080"})

        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.assertEqual(data["thresholds"]["read"]["p95_ms"], 300)
        self.assertEqual(data["thresholds"]["read"]["p99_ms"], 800)
        self.assertEqual(data["thresholds"]["write_transaction"]["p95_ms"], 800)
        self.assertEqual(data["thresholds"]["write_transaction"]["p99_ms"], 2000)
        self.assertEqual(data["thresholds"]["payment_callback"]["p95_ms"], 2000)
        self.assertEqual(data["thresholds"]["payment_callback"]["p99_ms"], 5000)
        self.assertEqual(data["thresholds"]["upload"]["p95_ms"], 1500)
        self.assertEqual(data["thresholds"]["upload"]["p99_ms"], 3000)
        self.assertEqual(data["thresholds"]["business_error_rate"]["max_ratio"], 0.005)
        self.assertEqual(data["thresholds"]["dependency_error_rate_alert"]["warn_ratio_per_5m"], 0.01)
        self.assertNotIn("critical_ratio_per_5m", data["thresholds"]["dependency_error_rate_alert"])
        self.assertTrue(data["thresholds"]["dependency_error_rate_alert"]["alert_only"])

        with tempfile.TemporaryDirectory(prefix="perf-manifest-") as tmp:
            mutated = Path(tmp) / "manifest.json"
            data["thresholds"]["read"]["p95_ms"] = 301
            mutated.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            drift = self.run_harness(
                "validate-manifest",
                "--manifest",
                str(mutated),
                env={"PERF_BASE_URL": "http://127.0.0.1:18080"},
            )

        self.assertNotEqual(drift.returncode, 0)
        self.assertIn("THRESHOLD_BASELINE_DRIFT:read.p95_ms", drift.stderr)

    def test_observed_threshold_exceedance_fails_with_specific_gate(self) -> None:
        with tempfile.TemporaryDirectory(prefix="perf-result-") as tmp:
            observed = Path(tmp) / "result.json"
            observed.write_text(
                json.dumps({"read": {"p95_ms": 301, "p99_ms": 700}, "business_error_rate": 0.001}),
                encoding="utf-8",
            )
            result = self.run_harness(
                "validate-result",
                "--result",
                str(observed),
                env={"PERF_BASE_URL": "http://127.0.0.1:18080"},
            )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("THRESHOLD_EXCEEDED:read.p95_ms", result.stderr)

    def test_validate_result_requires_complete_metrics_for_present_group(self) -> None:
        with tempfile.TemporaryDirectory(prefix="perf-result-") as tmp:
            observed = Path(tmp) / "result.json"
            observed.write_text(json.dumps({"read": {"p95_ms": 250}}), encoding="utf-8")
            result = self.run_harness(
                "validate-result",
                "--result",
                str(observed),
                env={"PERF_BASE_URL": "http://127.0.0.1:18080"},
            )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RESULT_SCHEMA_ERROR:read.p99_ms", result.stderr)

    def test_validate_result_requires_at_least_one_latency_group(self) -> None:
        with tempfile.TemporaryDirectory(prefix="perf-result-") as tmp:
            observed = Path(tmp) / "result.json"
            observed.write_text(json.dumps({"business_error_rate": 0.001}), encoding="utf-8")
            result = self.run_harness(
                "validate-result",
                "--result",
                str(observed),
                env={"PERF_BASE_URL": "http://127.0.0.1:18080"},
            )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RESULT_SCHEMA_ERROR:latency_group", result.stderr)

    def test_validate_result_accepts_single_complete_latency_group(self) -> None:
        with tempfile.TemporaryDirectory(prefix="perf-result-") as tmp:
            observed = Path(tmp) / "result.json"
            observed.write_text(
                json.dumps({"write_transaction": {"p95_ms": 700, "p99_ms": 1500}}),
                encoding="utf-8",
            )
            result = self.run_harness(
                "validate-result",
                "--result",
                str(observed),
                env={"PERF_BASE_URL": "http://127.0.0.1:18080"},
            )

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_dry_run_prints_command_without_requiring_wrk2(self) -> None:
        empty_path = tempfile.mkdtemp(prefix="perf-empty-path-")
        self.addCleanup(shutil.rmtree, empty_path)

        result = self.run_harness(
            "read",
            env={"PERF_BASE_URL": "http://127.0.0.1:18080", "PATH": empty_path},
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DRY_RUN", result.stdout)
        self.assertIn("read-wrk2: wrk2", result.stdout)
        self.assertNotIn("TOOL_MISSING", result.stderr)


if __name__ == "__main__":
    unittest.main()

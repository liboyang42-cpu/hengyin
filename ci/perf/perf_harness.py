#!/usr/bin/env python3
"""Fail-closed P3-1D performance harness entrypoint.

The harness builds reproducible command lines and validates local result files.
It never opens a network connection in dry-run mode, which is the default.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = Path(__file__).with_name("manifest.json")
ALLOW_WRITE_TOKEN = "I_UNDERSTAND_THIS_IS_NOT_PRODUCTION"
BLOCKED_HOSTS = {
    "chengyinhub.com",
    "www.chengyinhub.com",
    "api.chengyinhub.com",
    "47.103.124.64",
    "47.100.84.122",
}
EXPECTED_THRESHOLDS: dict[str, dict[str, Any]] = {
    "read": {"p95_ms": 300, "p99_ms": 800},
    "write_transaction": {"p95_ms": 800, "p99_ms": 2000},
    "payment_callback": {"p95_ms": 2000, "p99_ms": 5000},
    "upload": {"p95_ms": 1500, "p99_ms": 3000},
    "business_error_rate": {"max_ratio": 0.005},
    "dependency_error_rate_alert": {
        "warn_ratio_per_5m": 0.01,
        "alert_only": True,
    },
}
CAPACITY_KEY_FRAGMENTS = ("qps", "rps", "capacity", "concurrency", "users", "vus")


class GateFailure(Exception):
    """A named safety gate rejected the run."""

    def __init__(self, gate: str, detail: str) -> None:
        super().__init__(f"{gate}:{detail}")
        self.gate = gate
        self.detail = detail


def fail(gate: str, detail: str) -> None:
    raise GateFailure(gate, detail)


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail("MANIFEST_MISSING", str(path))
    except json.JSONDecodeError as exc:
        fail("MANIFEST_PARSE_ERROR", f"{path}:{exc.lineno}:{exc.msg}")
    if not isinstance(data, dict):
        fail("MANIFEST_PARSE_ERROR", "root must be an object")
    return data


def iter_keys(value: Any, prefix: str = ""):
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{prefix}.{key}" if prefix else str(key)
            yield child_path
            yield from iter_keys(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from iter_keys(child, f"{prefix}[{index}]")


def validate_threshold_manifest(path: Path) -> dict[str, Any]:
    data = load_manifest(path)
    thresholds = data.get("thresholds")
    if not isinstance(thresholds, dict):
        fail("MANIFEST_SCHEMA_ERROR", "missing thresholds object")

    for key_path in iter_keys(data):
        lower = key_path.lower()
        if any(fragment in lower for fragment in CAPACITY_KEY_FRAGMENTS):
            fail("UNAPPROVED_CAPACITY_NUMBER", key_path)

    for group, expected_values in EXPECTED_THRESHOLDS.items():
        actual_group = thresholds.get(group)
        if not isinstance(actual_group, dict):
            fail("THRESHOLD_BASELINE_DRIFT", group)
        for metric, expected in expected_values.items():
            actual = actual_group.get(metric)
            if actual != expected:
                fail("THRESHOLD_BASELINE_DRIFT", f"{group}.{metric}")

    return data


def base_url_from_env() -> str:
    raw = os.environ.get("PERF_BASE_URL", "").strip()
    if not raw:
        fail("PERF_BASE_URL_REQUIRED", "set PERF_BASE_URL to a non-production target")
    parsed = urlparse(raw)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        fail("PERF_BASE_URL_INVALID", "must be an absolute http(s) URL")
    host = (parsed.hostname or "").lower().strip("[]")
    if host in BLOCKED_HOSTS or host.endswith(".chengyinhub.com"):
        fail("PRODUCTION_TARGET_BLOCKED", host)
    return raw.rstrip("/")


def ensure_tool(binary: str) -> None:
    if shutil.which(binary) is None:
        fail("TOOL_MISSING", f"{binary} not found; install it or keep the harness in dry-run")


def ensure_explicit_load_shape(names: tuple[str, ...]) -> None:
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        fail("LOAD_SHAPE_REQUIRED", ",".join(missing))


def ensure_write_safety(no_dry_run: bool) -> None:
    if not no_dry_run:
        return
    if os.environ.get("PERF_ALLOW_WRITE_LOAD") != ALLOW_WRITE_TOKEN:
        fail("DRY_RUN_LOCK", "write load requires PERF_ALLOW_WRITE_LOAD=I_UNDERSTAND_THIS_IS_NOT_PRODUCTION")
    if os.environ.get("PERF_PAYMENT_MODE") != "stub":
        fail("PAYMENT_STUB_REQUIRED", "set PERF_PAYMENT_MODE=stub; real payment load is forbidden")


def shell_join(parts: list[str]) -> str:
    return " ".join(subprocess.list2cmdline([part]) for part in parts)


def print_plan(kind: str, dry_run: bool, command: list[str]) -> None:
    state = "DRY_RUN" if dry_run else "EXECUTE"
    print(f"{state} {kind}: {shell_join(command)}")
    if dry_run:
        print("DRY_RUN guard: command was not executed; no network connection opened.")


def maybe_exec(command: list[str], dry_run: bool) -> int:
    if dry_run:
        return 0
    return subprocess.call(command, cwd=ROOT)


def command_read(args: argparse.Namespace) -> int:
    validate_threshold_manifest(args.manifest)
    base_url = base_url_from_env()
    dry_run = not args.no_dry_run
    command = [
        "wrk2",
        "--latency",
        "-t", os.environ.get("PERF_WRK_THREADS", "${PERF_WRK_THREADS:?set intentionally}"),
        "-c", os.environ.get("PERF_WRK_CONNECTIONS", "${PERF_WRK_CONNECTIONS:?set intentionally}"),
        "-d", os.environ.get("PERF_DURATION", "${PERF_DURATION:?set intentionally}"),
        "-R", os.environ.get("PERF_RATE_RPS", "${PERF_RATE_RPS:?set intentionally}"),
        f"{base_url}{os.environ.get('PERF_READ_PATH', '/')}",
    ]
    print_plan("read-wrk2", dry_run, command)
    if not dry_run:
        ensure_explicit_load_shape(("PERF_WRK_THREADS", "PERF_WRK_CONNECTIONS", "PERF_DURATION", "PERF_RATE_RPS"))
        ensure_tool("wrk2")
    return maybe_exec(command, dry_run)


def command_write_k6(args: argparse.Namespace) -> int:
    validate_threshold_manifest(args.manifest)
    base_url = base_url_from_env()
    dry_run = not args.no_dry_run
    ensure_write_safety(args.no_dry_run)
    script = Path(__file__).with_name("k6_write_entry.js")
    command = [
        "k6",
        "run",
        str(script.relative_to(ROOT)),
        "-e", f"PERF_BASE_URL={base_url}",
        "-e", f"PERF_DRY_RUN={'true' if dry_run else 'false'}",
        "-e", f"PERF_PAYMENT_MODE={os.environ.get('PERF_PAYMENT_MODE', 'stub-dry-run')}",
    ]
    print_plan("write-k6", dry_run, command)
    if not dry_run:
        ensure_tool("k6")
    return maybe_exec(command, dry_run)


def command_write_jmeter(args: argparse.Namespace) -> int:
    validate_threshold_manifest(args.manifest)
    base_url = base_url_from_env()
    dry_run = not args.no_dry_run
    ensure_write_safety(args.no_dry_run)
    plan = Path(__file__).with_name("jmeter-write-entry.jmx")
    command = [
        "jmeter",
        "-n",
        "-t", str(plan.relative_to(ROOT)),
        f"-JPERF_BASE_URL={base_url}",
        f"-JPERF_DRY_RUN={'true' if dry_run else 'false'}",
        f"-JPERF_PAYMENT_MODE={os.environ.get('PERF_PAYMENT_MODE', 'stub-dry-run')}",
    ]
    print_plan("write-jmeter", dry_run, command)
    if not dry_run:
        ensure_tool("jmeter")
    return maybe_exec(command, dry_run)


def validate_latency_group(observed: Mapping[str, Any], thresholds: Mapping[str, Any], group: str) -> bool:
    if group not in observed:
        return False
    group_value = observed[group]
    if not isinstance(group_value, Mapping):
        fail("RESULT_SCHEMA_ERROR", group)
    for metric in ("p95_ms", "p99_ms"):
        if metric not in group_value:
            fail("RESULT_SCHEMA_ERROR", f"{group}.{metric}")
        actual = group_value[metric]
        if not isinstance(actual, (int, float)):
            fail("RESULT_SCHEMA_ERROR", f"{group}.{metric}")
        if actual > thresholds[group][metric]:
            fail("THRESHOLD_EXCEEDED", f"{group}.{metric}")
    return True


def command_validate_result(args: argparse.Namespace) -> int:
    manifest = validate_threshold_manifest(args.manifest)
    base_url_from_env()
    try:
        observed = json.loads(args.result.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail("RESULT_MISSING", str(args.result))
    except json.JSONDecodeError as exc:
        fail("RESULT_PARSE_ERROR", f"{args.result}:{exc.lineno}:{exc.msg}")
    if not isinstance(observed, dict):
        fail("RESULT_SCHEMA_ERROR", "root must be an object")

    thresholds = manifest["thresholds"]
    latency_groups = 0
    for group in ("read", "write_transaction", "payment_callback", "upload"):
        if validate_latency_group(observed, thresholds, group):
            latency_groups += 1
    if latency_groups == 0:
        fail("RESULT_SCHEMA_ERROR", "latency_group")

    business_rate = observed.get("business_error_rate")
    if isinstance(business_rate, (int, float)) and business_rate > thresholds["business_error_rate"]["max_ratio"]:
        fail("THRESHOLD_EXCEEDED", "business_error_rate")

    dependency_rate = observed.get("dependency_error_rate_per_5m")
    if isinstance(dependency_rate, (int, float)):
        alert = thresholds["dependency_error_rate_alert"]
        if dependency_rate > alert["warn_ratio_per_5m"]:
            print("ALERT_ONLY:dependency_error_rate_per_5m warn", file=sys.stderr)

    print("PERF_RESULT_CONTRACT:PASS")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="P3-1D fail-closed performance harness")
    parser.set_defaults(manifest=DEFAULT_MANIFEST)
    parser.add_argument("--manifest", type=Path, default=argparse.SUPPRESS)
    sub = parser.add_subparsers(dest="command", required=True)
    def add_manifest_argument(command_parser: argparse.ArgumentParser) -> None:
        command_parser.add_argument("--manifest", type=Path, default=argparse.SUPPRESS)

    read = sub.add_parser("read", help="dry-run or execute the read-only wrk2 path")
    add_manifest_argument(read)
    read.add_argument("--no-dry-run", action="store_true")
    read.set_defaults(func=command_read)

    k6 = sub.add_parser("write-k6", help="dry-run or execute the guarded k6 write entry")
    add_manifest_argument(k6)
    k6.add_argument("--no-dry-run", action="store_true")
    k6.set_defaults(func=command_write_k6)

    jmeter = sub.add_parser("write-jmeter", help="dry-run or execute the guarded JMeter write entry")
    add_manifest_argument(jmeter)
    jmeter.add_argument("--no-dry-run", action="store_true")
    jmeter.set_defaults(func=command_write_jmeter)

    validate_manifest = sub.add_parser("validate-manifest", help="validate frozen threshold manifest")
    add_manifest_argument(validate_manifest)
    validate_manifest.set_defaults(func=lambda args: (base_url_from_env(), validate_threshold_manifest(args.manifest), print("PERF_MANIFEST_CONTRACT:PASS"), 0)[-1])

    validate_result = sub.add_parser("validate-result", help="validate an observed metrics JSON file")
    add_manifest_argument(validate_result)
    validate_result.add_argument("--result", type=Path, required=True)
    validate_result.set_defaults(func=command_validate_result)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except GateFailure as exc:
        print(f"{exc.gate}:{exc.detail}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

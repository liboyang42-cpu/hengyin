#!/usr/bin/env python3
"""Read-only registration readiness for existing monitoring jobs on an exact test database."""
import argparse
import importlib.util
import json
from pathlib import Path


CLIENT_PATH = Path(__file__).with_name("db-backup-restore.py")
SPEC = importlib.util.spec_from_file_location("verified_test_mysql", CLIENT_PATH)
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)
REQUIRED = {
    "metricsProbeTask.run()": "0 0/5 * * * ?",
    "auditBacklogMonitorTask.run()": "0 0/30 * * * ?",
    "withdrawReconciliationTask.run()": "0 0 4 * * ?",
    "settlementReconciliationTask.run()": "0 10 3 * * ?",
}


def inspect_registration(args):
    if not args.confirm_test_target:
        raise ValueError("Explicit test target confirmation is required")
    CLIENT.identify(args)
    names = ",".join("'" + name + "'" for name in REQUIRED)
    raw = CLIENT.mysql(args, "SELECT HEX(invoke_target), HEX(cron_expression), status, concurrent, "
                       "misfire_policy FROM sys_job WHERE invoke_target IN (" + names + ")")
    rows = {name: [] for name in REQUIRED}
    for line in raw.splitlines():
        values = line.split("\t")
        if len(values) != 5:
            raise ValueError("Unexpected monitoring registration readback shape")
        target, cron = (bytes.fromhex(value).decode() for value in values[:2])
        if target not in rows:
            raise ValueError("Unexpected monitoring target returned")
        rows[target].append((cron, *values[2:]))
    checks = []
    for target, expected_cron in REQUIRED.items():
        current = rows[target]
        failures = []
        if not current:
            failures.append("missing")
        elif len(current) != 1:
            failures.append("duplicate")
        else:
            cron, status, concurrent, misfire = current[0]
            if status != "0":
                failures.append("paused-or-invalid-status")
            if cron != expected_cron:
                failures.append("cron-differs")
            if concurrent != "1":
                failures.append("concurrent-execution-allowed")
            if misfire != "3":
                failures.append("misfire-policy-differs")
        checks.append({"invokeTarget": target, "ready": not failures, "problems": failures})
    return {"readOnly": True, "ready": all(item["ready"] for item in checks), "jobs": checks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--defaults-file", required=True, type=Path)
    parser.add_argument("--database", required=True)
    parser.add_argument("--server-uuid", required=True)
    parser.add_argument("--confirm-test-target", action="store_true")
    args = parser.parse_args()
    args.defaults_file = args.defaults_file.resolve()
    try:
        report = inspect_registration(args)
    except (ValueError, OSError, UnicodeError):
        parser.exit(1, "Monitoring readback rejected: check exact test identity and private client configuration\n")
    print(json.dumps(report, indent=2))
    return 0 if report["ready"] else 2


if __name__ == "__main__":
    raise SystemExit(main())

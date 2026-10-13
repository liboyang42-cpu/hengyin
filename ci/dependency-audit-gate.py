#!/usr/bin/env python3
"""Fail closed when a Grype JSON report contains Critical or High findings."""

import json
import pathlib
import sys


BLOCKING_SEVERITIES = {"critical", "high"}
KNOWN_SEVERITIES = BLOCKING_SEVERITIES | {"medium", "low", "negligible", "unknown"}


def read_matches(report_path):
    try:
        content = pathlib.Path(report_path).read_text(encoding="utf-8")
        report = json.loads(content)
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("无法读取 Grype JSON 报告") from error

    if not isinstance(report, dict):
        raise ValueError("Grype JSON 报告必须是对象")
    if report.get("error") or report.get("errors"):
        raise ValueError("Grype 报告包含 scanner error")

    matches = report.get("matches")
    if not isinstance(matches, list):
        raise ValueError("Grype JSON 报告缺少 matches 数组")
    return matches


def severity_of(match):
    vulnerability = match.get("vulnerability") if isinstance(match, dict) else None
    severity = vulnerability.get("severity") if isinstance(vulnerability, dict) else None
    if not isinstance(severity, str) or severity.lower() not in KNOWN_SEVERITIES:
        raise ValueError("Grype finding 缺少可识别的 vulnerability.severity")
    return severity.lower()


def main(argv):
    if len(argv) != 2:
        print("用法: dependency-audit-gate.py <grype-report.json>", file=sys.stderr)
        return 2

    try:
        severities = [severity_of(match) for match in read_matches(argv[1])]
    except ValueError as error:
        print("❌ 依赖漏洞门禁无法验证，按 fail-closed 处理: " + str(error), file=sys.stderr)
        return 2

    blocking = [severity for severity in severities if severity in BLOCKING_SEVERITIES]
    if blocking:
        print("❌ 依赖漏洞门禁阻断: Critical/High=" + str(len(blocking)))
        return 1

    print("✅ 依赖漏洞门禁通过: Critical/High=0，已检查 finding=" + str(len(severities)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

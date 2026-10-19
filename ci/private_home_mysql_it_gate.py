#!/usr/bin/env python3
"""Exact, additive report gate for owner-home and retention MySQL suites."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

EXPECTED = {"PrivateHomeMySqlIT": 31, "PrivateHomeRetentionMySqlIT": 28}
PREFIX = "com.chengyinhub.business.home."


def enforce(directory):
    errors = []
    for name, count in EXPECTED.items():
        path = Path(directory) / ("TEST-" + PREFIX + name + ".xml")
        try:
            root = ET.parse(path).getroot()
            actual = {key: int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            if root.attrib.get("name") != PREFIX + name:
                errors.append(name + ": wrong suite identity")
            if actual != {"tests": count, "failures": 0, "errors": 0, "skipped": 0}:
                errors.append(name + ": expected exact count with zero failures/errors/skips; got " + str(actual))
        except (OSError, ET.ParseError, ValueError, KeyError) as failure:
            errors.append(name + ": missing or invalid report (" + type(failure).__name__ + ")")
    return errors


if __name__ == "__main__":
    problems = enforce(sys.argv[1] if len(sys.argv) > 1 else "chengyinhub-admin/target/surefire-reports")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        sys.exit(1)
    print("W19 real MySQL: home31 + retention28, zero failures/errors/skips")

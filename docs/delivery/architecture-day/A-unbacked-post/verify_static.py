#!/usr/bin/env python3
"""Verify the one H negative-control addition; no Java, SQL, database or CI execution."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASE = "05f4aa69e61752dcafb0bec73ca9ae9b2ff7a7f5"
H = "c75ab442b572700df1d0289ba636f6f541168e4a"
TEST = "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationFundsMybatisIntegrationTest.java"
PATCH = "docs/delivery/architecture-day/H/a-de31-recheck/unbacked-post.review.patch"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def main():
    before = git("show", BASE + ":" + TEST)
    patch = git("show", H + ":" + PATCH)
    addition = b"".join(line[1:] for line in patch.splitlines(keepends=True)
                        if line.startswith(b"+") and not line.startswith(b"+++"))
    after = (ROOT / TEST).read_bytes()
    assert len(addition.splitlines()) == 51
    assert after.count(addition) == 1
    assert after.replace(addition, b"", 1) == before, "original 94 tests/fixture/helpers changed"
    assert len(re.findall(rb"@Test\b", before)) == 94
    assert len(re.findall(rb"@Test\b", after)) == 95
    assert b"bareAdminPostCannotCreateUnbackedPointsOrInventoryToRelease" in addition
    assert b"registrationService.createRegistration(" in addition
    assert b"handler.add(forged)" in addition and b"registrationService.cancelRegistration(" in addition
    print(json.dumps({"baseline": BASE, "h_review": H, "test_path": TEST,
                      "baseline_blob": git("rev-parse", BASE + ":" + TEST).decode().strip(),
                      "baseline_sha256": hashlib.sha256(before).hexdigest(),
                      "candidate_sha256": hashlib.sha256(after).hexdigest(),
                      "h_patch_sha256": hashlib.sha256(patch).hexdigest(),
                      "h_patch_exactly_applied": True, "added_lines": 51,
                      "baseline_tests_and_fixture_byte_preserved": 94, "candidate_source_tests": 95,
                      "new_java_tests_executed": 0, "java_compile": "NOT_RUN", "mysql": "NOT_RUN",
                      "expected_pre_fix_outcome": "negative control expected to fail the conservation assertion; NOT_EXECUTED",
                      "production_fix": "PENDING user choice; no production change in this candidate"},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

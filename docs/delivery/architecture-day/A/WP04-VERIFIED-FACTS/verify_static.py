#!/usr/bin/env python3
"""Candidate source checks only; no Java, database, provider or new CI gate."""
import hashlib
import json
from pathlib import Path
import re
import runpy
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[5]
DOCS = Path(__file__).parent
BASE = "b681cf0604d2e039c59ee1ca5034a71ec4525801"
B = "041534c7681e480f80eb8afc911f65bf1ed13eda"
PROD = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
TEST = "chengyinhub-admin/src/test/java/com/chengyinhub/business/"


def old(path, commit=BASE):
    return subprocess.check_output(["git", "show", commit + ":" + path], cwd=ROOT).decode()


def now(path):
    return (ROOT / path).read_text()


def methods(source):
    # Existing suite convention: method closing brace at four spaces. Compare original bytes.
    return {match[1]: match[0] for match in re.finditer(
        r"    @Test\s+void (\w+)\([^)]*\)[^{]*\{.*?\n    \}", source, re.S)}


scan = runpy.run_path(str(ROOT / "ci/real_storage_it.py"))["scan_java"]
expected = {TEST + "it/RegistrationTopicFenceMySqlIT.java": 20,
            TEST + "service/impl/VerifiedRegistrationReceiptGuardTest.java": 9,
            TEST + "service/RegistrationFundsMybatisIntegrationTest.java": 96,
            TEST + "service/PaymentCallbackFenceEntryTest.java": 10,
            TEST + "service/RegistrationPaymentAtomicityContractTest.java": 1}
counts = {path: scan(now(path)) for path in expected}
for path, number in expected.items():
    assert counts[path]["declared_tests"] == number, (path, counts[path])
    assert not counts[path]["dynamic"] and not counts[path]["nested"] and not counts[path]["conditions"]

funds = TEST + "service/RegistrationFundsMybatisIntegrationTest.java"
before, after = methods(old(funds)), methods(now(funds))
assert len(before) == len(after) == 96
unsafe_old = before.pop("concurrentRefundCallbacksReturnDeductedPointsExactlyOnce")
unsafe_new = after.pop("concurrentUnverifiedRefundObservationsCannotReturnDeductedPoints")
assert before == after and len(before) == 95
assert unsafe_old.count("assertEquals(") == unsafe_new.count("assertEquals(") == 6
assert unsafe_old.count("pool.submit(") == unsafe_new.count("pool.submit(") == 2
it = TEST + "it/RegistrationTopicFenceMySqlIT.java"
assert all(methods(now(it)).get(key) == value for key, value in methods(old(it)).items())

cash, refund = PROD + "CmsRegistrationServiceImpl.java", PROD + "RefundServiceImpl.java"
notice_start = "    private void registerPaymentAfterCommit("
assert now(cash)[now(cash).index(notice_start):now(cash).index("    private WechatVerifiedPaymentFactService.Stored checkedVerifiedPayment")].rstrip() == old(cash)[old(cash).index(notice_start):old(cash).rfind("\n}")].rstrip()
financial_start = "    private void markRegistrationRefunded("
assert now(refund)[now(refund).index(financial_start):now(refund).index("    private WechatVerifiedPaymentFactService.Stored checkedVerifiedRefund")].rstrip() == old(refund)[old(refund).index(financial_start):old(refund).rfind("\n}")].rstrip()
assert "!fact.transactionId().equals(registration.getTransactionId())" in now(refund)
assert 'refund_requires_verified_original_refund' in now(cash)
gateway_field = next(line for line in old(cash).splitlines() if "paymentAttemptGateway;" in line)
assert gateway_field in now(cash)  # Root merges these hunks over B's newer @Qualifier/provider field.

changed = subprocess.check_output(["git", "diff", "--name-only", BASE], cwd=ROOT).decode().splitlines()
assert sorted(path for path in changed if "/src/main/" in path) == sorted([cash, refund])
protected = [PROD + name + ".java" for name in ["WechatPayServiceImpl", "WxPaymentCallbackMutationService",
    "TeamGroupServiceImpl", "ClubEventOpsServiceImpl", "CoopSettlementClaimServiceImpl", "SettlementEntryServiceImpl"]]
protected += ["ci/real_storage_it.py", "ci/real-storage-it-registry.json", "pom.xml"]
for path in protected:
    assert now(path) == old(path), path
reader = PROD + "WechatVerifiedPaymentFactService.java"
metadata = json.loads((DOCS / "B-reader-base.json").read_text())
assert metadata["source"] == B and metadata["sha256"] == hashlib.sha256(old(reader, B).encode()).hexdigest()
assert "public Stored requireVerified(Reference reference)" in old(reader, B)
assert "readOnly=true" not in old(reader, B)
with tempfile.TemporaryDirectory(prefix="a-b-reader-review-") as temporary:
    target = Path(temporary) / reader
    target.parent.mkdir(parents=True)
    target.write_text(old(reader, B))
    subprocess.run(["git", "apply", "--check", str(DOCS / "required-B-current-reader.review.patch")], cwd=temporary, check=True)
subprocess.run(["git", "apply", "--check", str(DOCS / "G-typed-refund-callsite.review.patch")], cwd=ROOT, check=True)
subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
files = [path for path in changed if path.endswith(".java")] + [TEST + "service/impl/VerifiedRegistrationReceiptGuardTest.java"]
print(json.dumps({"status": "STATIC_ONLY", "parent": BASE, "B_dependency": B,
    "executed_java_mysql_tests": 0, "counts": counts, "preserved_funds_test_bodies": 95,
    "unverified_refund_observation_test": "2 concurrent calls and 6 assertions retained; rejection expected",
    "preserved_topic_fence_test_bodies": 13, "protected_equal_to_parent": protected,
    "review_patches": "Context checks only; B reader and G interface are NOT applied",
    "sha256": {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in sorted(set(files))},
    "not_run": ["Java/test compilation", "JUnit", "MySQL/rollback/concurrency/cleanup", "CI", "SDK verification", "G allocation reversal"],
    "remaining": ["B immutable fresh-reader transaction patch", "inline original payment Reference persistence/readback and shared lock contract", "G real typed terminal port", "D-owned notification fixture adaptation"]}, ensure_ascii=False, indent=2))

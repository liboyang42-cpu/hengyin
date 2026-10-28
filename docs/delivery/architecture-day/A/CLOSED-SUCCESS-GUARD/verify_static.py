"""Read-only source checks. Never invokes Java, SQL, Docker, CI, or a provider."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT))
from ci.real_storage_it import scan_java

BASE = "2454c644508ca9e4ef1b2c1565d271bd56c1cd92"
PREFIX = "chengyinhub-admin/src/test/java/com/chengyinhub/business/"
FILES = [
    "it/RegistrationTopicFenceMySqlIT.java",
    "service/RegistrationFundsMybatisIntegrationTest.java",
    "service/PaymentCallbackFenceEntryTest.java",
    "service/RefundNotificationMybatisIntegrationTest.java",
    "service/impl/RefundManualPayoutTest.java",
    "service/impl/PayingSettlementSingleImplementationContractTest.java",
    "task/OrderClosePayingReconcileTaskContractTest.java",
]

def old(path):
    return subprocess.check_output(["git", "show", f"{BASE}:{path}"], cwd=ROOT).decode()

def section(source, signature):
    start = source.index(signature)
    end = source.index("\n    }", start)
    return source[start:end + 6]

def ordinary(source):
    # Only compare existing fixed @Test methods; runtime selection uses the repository scanner below.
    found = {}
    for match in re.finditer(r"@Test\s+void\s+(\w+)\([^)]*\)(?:\s+throws\s+[^\{]+)?\s*\{", source):
        end = source.index("\n    }", match.end())
        found[match.group(1)] = source[match.start():end + 6]
    return found

counts, preserved = {}, {}
for rel in FILES:
    path = PREFIX + rel
    before, after = old(path), (ROOT / path).read_text()
    counts[path] = {"before": scan_java(before), "after": scan_java(after)}
    before_tests, after_tests = ordinary(before), ordinary(after)
    assert before_tests.keys() <= after_tests.keys(), path
    # Two wiring contracts change call shape; all other original case bodies remain exact after
    # replacing only the old guard symbol in existing fixture stubs. No assertions are removed.
    if rel not in {"task/OrderClosePayingReconcileTaskContractTest.java",
                    "service/impl/PayingSettlementSingleImplementationContractTest.java"}:
        for name, body in before_tests.items():
            assert body.replace(".lockPaymentAttemptForUpdate(", ".lockClosablePaymentAttemptForUpdate(") == after_tests[name], (path, name)
        preserved[path] = len(before_tests)
    else:
        preserved[path] = {"all_original_case_names_retained": len(before_tests), "wiring_assertions_manually_reviewed": True}
    assert not counts[path]["after"]["dynamic"], path

prod = "chengyinhub-system/src/main/java/com/chengyinhub/business/"
reg_path = prod + "service/impl/CmsRegistrationServiceImpl.java"
refund_path = prod + "service/impl/RefundServiceImpl.java"
reg = (ROOT / reg_path).read_text()
refund = (ROOT / refund_path).read_text()
for signature in ["private String completePaymentSuccess", "private void recordPaymentConsumption",
                  "private void returnPointsToUser", "public boolean settlePayingOne",
                  "private void releaseHeldResources", "private void registerPaymentAfterCommit",
                  "private void signupNoticeExtra"]:
    # Names of private notification overloads differ across public composition heads: compare only
    # actual declarations present in this exact source; no inherited/future source is assumed.
    if signature in old(reg_path):
        assert section(old(reg_path), signature) == section(reg, signature), signature
for signature in ["public int reviewRefund", "public int verifyManualPayout", "private boolean applyRefundPayoutEvent",
                  "private boolean updatePayoutTerminal", "private void notifyRefundAfterCommit"]:
    assert section(old(refund_path), signature) == section(refund, signature), signature
close = section(reg, "public boolean settleClosedPayingOne")
assert close.index("RegistrationTopicFence.lock(") < close.index("lockClosablePaymentAttemptForUpdate(") < close.index("return settlePayingOne(")
assert "@Transactional(rollbackFor = Exception.class)\n    public boolean settleClosedPayingOne" in reg
for rel in ["service/impl/TeamGroupServiceImpl.java", "task/OrderCloseTask.java"]:
    text = (ROOT / (prod + rel)).read_text()
    assert "cmsRegistrationService.settleClosedPayingOne(" in text
    assert ".lockPaymentAttemptForUpdate(" not in text
    assert "cmsRegistrationService.settlePayingOne(" not in text
assert "&& mayApplyRefundFailureProjection(current, refundStatus, originalFacts)" in refund
check = section(refund, "private boolean mayApplyRefundFailureProjection")
assert 'findRefundSuccess(facts.get("mchid"), refund.getRefundNo(),' in check
assert "catch" not in check and "SUCCESS\".equals" not in check

changed = subprocess.check_output(["git", "diff", "--name-only", BASE], cwd=ROOT).decode().splitlines()
protected = [path for path in changed if path.startswith(("ci/", ".github/", "chengyinhub-admin/sql/"))
             or path.endswith("pom.xml") or any(name in path for name in
             ["WechatPayServiceImpl.java", "WechatVerifiedPaymentFactService.java", "WechatPaymentSdkGateway.java",
              "CoopSettlementClaimServiceImpl.java", "ClubMembershipServiceImpl.java", "MerchantCommerceService.java"])]
assert not protected, protected
added = subprocess.check_output(["git", "diff", "--unified=0", BASE], cwd=ROOT).decode()
for marker in [r"AKIA[0-9A-Z]{16}", r"ghp_[A-Za-z0-9]{30,}", r"github_pat_[A-Za-z0-9_]{30,}", r"-----BEGIN (?:RSA |EC )?PRIVATE KEY-----"]:
    assert not re.search(marker, added), "secret-shaped diff rejected"
result = {"status": "STATIC_ONLY", "construction_parent": BASE,
          "B_source_donor_commit": "13becff8bb4099ee1ee2a53e3eef280d7f2f18b3",
          "source_counts": counts, "preserved_original_cases": preserved,
          "economic_CAS_release_and_terminal_methods": "unchanged",
          "business_lock_before_attempt_guard_before_original_release": True,
          "production_B_G_CLM_commerce_CI_DDL_changes": protected,
          "executed_Java_MySQL_CI_DDL_SDK": False,
          "sha256": {path: hashlib.sha256((ROOT/path).read_bytes()).hexdigest() for path in changed if path != str(Path(__file__).relative_to(ROOT).parent / "static-result.json")}}
(Path(__file__).parent / "static-result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
print(json.dumps({"status": result["status"], "test_sources": {path.split('/')[-1]:
    [value["before"]["declared_tests"], value["after"]["declared_tests"]] for path, value in counts.items()},
    "Java_MySQL_CI_DDL_SDK": "NOT_RUN"}, ensure_ascii=False))

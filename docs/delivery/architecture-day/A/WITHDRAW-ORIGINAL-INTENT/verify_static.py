"""Withdrawal candidate provenance/shape checks only. Does not compile Java or execute SQL/tests."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
BASE = "ae7b93f62bb62385bed8dc4110a85e4c0f315a8b"
PRIOR = "986b88d9640bf45900bd807675bed1d6a06c5938"
JAVA = "chengyinhub-system/src/main/java/com/chengyinhub/business/"
TEST = "chengyinhub-admin/src/test/java/com/chengyinhub/business/"
SQL = "chengyinhub-admin/sql/"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode()


def read(path):
    return (ROOT / path).read_text()


def mask(source):
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/', "", source)


gateway = [JAVA + "service/IWechatPayService.java", JAVA + "service/impl/WechatPayServiceImpl.java"]
for path in gateway:
    assert read(path) == git("show", f"{BASE}:{path}"), path
dto = read(JAVA + "domain/dto/CashWithdrawTransferIntent.java")
assert "public record CashWithdrawTransferIntent" in dto
assert "public String canonicalRequestHash()" in dto and "public void requireValid()" in dto
assert "@Override public String toString()" in dto
assert "doubleValue" not in dto and "longValueExact()" in dto
service = read(JAVA + "service/impl/CashWithdrawServiceImpl.java")
assert service.count("wechatPayService.transferToBalance(") == service.count("wechatPayService.queryTransferResult(") == 1
assert "transferToBalance(submission)" in service and "queryTransferResult(query.intent())" in service
assert 'recordSubmissionIntent(id, request)' in service
assert '!"false".equals(res.get("ambiguous"))' in service
assert "requireRefundEvidence(original)" in service and "boundToIntent(f, intent)" in service
assert all(f'intent.{name}().equals(observation.get("{key}"))' in service for name, key in
           [("mchid", "mchid"), ("appid", "appid"), ("outBatchNo", "outBatchNo"), ("outDetailNo", "outDetailNo"), ("openid", "openid")])
assert "Long.parseLong(observation.get(\"transferAmount\"))" in service
assert "originalBatchId(w, facts)" in service and "expectedIntentId.equals(intent.submissionIntentId())" in service
assert '.setScale(2, RoundingMode.DOWN)' in service  # Original amount policy unchanged; DTO fen conversion is exact.
old_ddl = SQL + "migration_20261023_cash_withdraw_fund_fact.sql"
assert read(old_ddl) == git("show", f"{PRIOR}:{old_ddl}")
upgrade = read(SQL + "migration_20261024_cash_withdraw_transfer_intent.sql")
assert "ALTER TABLE member_cash_withdraw_fund_fact" in upgrade
assert not re.search(r'\b(DROP|UPDATE|DELETE|TRUNCATE)\b', mask(upgrade), re.I)
mapper = ET.fromstring(read("chengyinhub-system/src/main/resources/mapper/business/MemberCashWithdrawMapper.xml"))
insert = "".join(mapper.find("insert[@id='insertFundFact']").itertext())
cols = [v.strip() for v in re.search(r'\((.*?)\)\s*select', insert, re.S).group(1).split(",")]
values = re.search(r'\bselect\s+(.*?)\s+from', insert, re.S).group(1).split(",")
assert len(cols) == len(set(cols)) == len(values) == 22
renames = {"sync_fail_refundClaimLost_noBalanceNoAlarm": "sync_fail_refundClaimLost_failsClosedAndAlarms",
           "manualRefund_refundThrows_backToPendingReview": "manualRefund_refundThrows_escapesForWholeTransactionRollback"}
paths = set(git("diff", "--name-only", BASE).splitlines()) | set(git("ls-files", "--others", "--exclude-standard").splitlines())
for path in sorted(paths):
    if path.endswith(".java") and (ROOT / path).is_file():
        stack = []
        for char in mask(read(path)):
            if char in "([{":
                stack.append(char)
            elif char in ")]}":
                assert stack and stack.pop() == {")": "(", "]": "[", "}": "{"}[char], path
        assert not stack, path
    if path.startswith(TEST) and path.endswith(".java") and path in git("ls-tree", "-r", "--name-only", BASE).splitlines():
        old = git("show", f"{BASE}:{path}"); new = read(path)
        methods = lambda source: set(re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(', source))
        assert {renames.get(name, name) for name in methods(old)} <= methods(new), path
        assert len(re.findall(r'\bassert\w*\s*\(', new)) >= len(re.findall(r'\bassert\w*\s*\(', old)), path
assert not any(path.startswith(("ci/", ".github/")) or path.endswith("pom.xml") for path in paths)
owned_main = {JAVA + p for p in ("domain/dto/CashWithdrawTransferIntent.java", "domain/CashWithdrawFundFact.java",
              "mapper/MemberCashWithdrawMapper.java", "service/impl/CashWithdrawServiceImpl.java",
              "service/support/WithdrawalBalanceAdmissionService.java")}
owned_main.add("chengyinhub-system/src/main/resources/mapper/business/MemberCashWithdrawMapper.xml")
assert all(path in owned_main for path in paths if "/src/main/" in path), paths - owned_main
it = read(TEST + "service/impl/CashWithdrawFundEvidenceMySqlIT.java")
assert it.count("@Test void") == 29 and it.count("@ParameterizedTest") == 4
# Independent reference calculation, not execution of the Java DTO.
wire = bytearray(struct.pack(">i", 1))
def field(value):
    encoded = value.encode("utf-16-be", errors="surrogatepass")
    wire.extend(struct.pack(">i", len(encoded) // 2)); wire.extend(encoded)
for value in ("WECHAT_BATCH_TRANSFER", "merchant", "wx-origin", "CW500", "城瘾分润提现", "提现"):
    field(value)
wire.extend(struct.pack(">q", 3000)); wire.extend(struct.pack(">i", 1)); field("CD500")
wire.extend(struct.pack(">q", 3000)); field("openid-origin"); field("提现")
reference_hash = hashlib.sha256(wire).hexdigest()
assert reference_hash == "3d2a774a7a3aad5a49c9ef9f9cbefe82e53d1ed465af3a752b5d4b96cd26d18a"
git("diff", "--check")
print(json.dumps({"baseline": BASE, "prior_withdrawal_source": PRIOR, "status": "STATIC_ONLY_PASS",
                  "java_junit_mysql_ci": "NOT_RUN", "fund_mysql_cases_authored": 51,
                  "dto_unit_cases_authored": 5, "gateway_overloads": "B REVIEW PATCH REQUIRED; NOT APPLIED HERE",
                  "python_reference_hash": reference_hash,
                  "files_sha256": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in sorted(paths)
                                   if (ROOT / p).is_file() and not p.endswith("/static-result.json")}},
                 ensure_ascii=False, indent=2))

"""Lightweight source checks only: no Java, JVM, DB, network, build, or dependency install."""
from pathlib import Path
import hashlib
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[4]
system = root / "chengyinhub-system/src/main"
java = system / "java/com/chengyinhub/business"
xml_path = system / "resources/mapper/business/MemberCashWithdrawMapper.xml"
mapping = ET.parse(xml_path).getroot()
ids = [node.attrib["id"] for node in mapping if node.tag in {"select", "insert", "update", "delete"}]
assert len(ids) == len(set(ids)), "Duplicate SQL statement id"
interface = (java / "mapper/MemberCashWithdrawMapper.java").read_text()
methods = re.findall(r"\b(?:int|MemberCashWithdraw|List<[^>]+>)\s+(\w+)\s*\(", interface)
assert set(ids) == set(methods), (set(ids) - set(methods), set(methods) - set(ids))
fact = (java / "domain/CashWithdrawFundFact.java").read_text()
for field in mapping.find("resultMap[@id='FundFactResult']"):
    prop = field.attrib["property"]
    assert "get" + prop[0].upper() + prop[1:] + "()" in fact, prop
for node in mapping:
    if node.tag in {"update", "delete"}:
        assert not re.search(r"(?:update|delete\s+from)\s+member_cash_withdraw_fund_fact\b", " ".join(node.itertext()), re.I)
generic = mapping.find("update[@id='updateMemberCashWithdraw']")
assert "balance_deduct_id =" not in " ".join(generic.itertext())
assert "balance_refund_id =" not in " ".join(generic.itertext())
print("PASS: Mapper XML well-formed; %d statement IDs match Java interface; fact properties mapped" % len(ids))
print("PASS: no fact UPDATE/DELETE statements; generic receipt writer cannot replace evidence pointers")

base = "2ec68899099a1e250df0949a8383bf85b5d3ffe0"
protected = ["pom.xml", ".github", "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WechatPayServiceImpl.java",
             "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/UmsMemberServiceImpl.java",
             "chengyinhub-system/src/main/java/com/chengyinhub/business/service/IWechatPayService.java", "sql"]
protected_changes = subprocess.check_output(["git", "diff", "--name-only", base, "--", *protected], cwd=root, text=True).splitlines()
assert set(protected_changes) <= {"chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql"}, protected_changes
assert not subprocess.check_output(["git", "ls-tree", base, "--", "chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql"], cwd=root)
subprocess.run(["git", "diff", "--check", base], cwd=root, check=True, capture_output=True)
print("PASS: B/shared production/CI and existing migrations unchanged; only the new authorized schema candidate allowed")

def method(source, name):
    start = re.search(r"\b" + re.escape(name) + r"\s*\([^)]*\)\s*(?:throws[^\{]+)?\{", source).end()
    depth = 1
    end = start
    while depth:
        if source[end] == "{": depth += 1
        if source[end] == "}": depth -= 1
        end += 1
    return source[start:end]

for relative, names in {
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/longrun/OWN_C04_21Test.java": [
        "currentFreezeReadFollowsBalanceLockAndBlocksNewlyFrozenMoney", "successfulDebitReturnsTheLockedBeforeAndAfterAndExactFloor",
        "currentFreezeReadFailureNeverBecomesZeroOrReachesDebit", "bountyFailureRollsBackItsNestedBoundaryAfterMemberThenBountyLock", "bankFixture"],
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/longrun/OWN_C04_21MySqlIT.java": [
        "bankAdmissionUsesCommittedBalanceAndBothFreezesAfterOuterOldRrSnapshot"],
    "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/FundIdempotencyConcurrencyTest.java": [
        "concurrentPointsOnlyRefundClaimsRegistrationBeforeRestoringFundsAndInventory"]
}.items():
    old = subprocess.check_output(["git", "show", base + ":" + relative], cwd=root, text=True)
    current = (root / relative).read_text()
    for name in names: assert method(old, name) == method(current, name), name
print("PASS: seven bank/freeze/bounty/points methods remain byte-for-byte unchanged")
tests = root / "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl"
for name in ["CashWithdrawFundEvidenceTest.java", "CashWithdrawFundEvidenceMySqlIT.java"]:
    source = (tests / name).read_text()
    print("SOURCE ONLY: %s has %d @Test methods; NOT EXECUTED" % (name, len(re.findall(r"@Test\b", source))))
    assert not re.search(r"@(Disabled|EnabledIf|DisabledIf)|Assumptions\.|assumeTrue\(", source)
print("PASS: dedicated new tests contain no conditional skip or H2 fallback")

changed = subprocess.check_output(["git", "diff", "--name-only", base], cwd=root, text=True).splitlines()
changed += subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=root, text=True).splitlines()
files = sorted(set(Path(p) for p in changed if Path(p).parts[0] in {"chengyinhub-admin", "chengyinhub-system", "chengyinhub-ui", "sql"}))
digest = hashlib.sha256()
for relative in files:
    data = (root / relative).read_bytes()
    digest.update(relative.as_posix().encode() + b"\0" + data + b"\0")
    print("SOURCE " + relative.as_posix())
print("SOURCE_SHA256 " + digest.hexdigest())
print("NOT RUN: Java compilation/unit tests, H2 tests, MySQL tests, Redis/backend/WeChat integration")

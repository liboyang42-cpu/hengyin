#!/usr/bin/env python3
"""Candidate-scoped read-only checks; not a new CI gate or Java execution."""
import hashlib
import json
from pathlib import Path
import re
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[5]
BASE = "06a571b1247620a9bdcea068be0ded973671d17c"
PROD = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
ADMIN = "chengyinhub-admin/src/test/java/com/chengyinhub/business/"
FILES = [PROD + name + ".java" for name in ["CmsRegistrationServiceImpl", "RefundServiceImpl", "WxPaymentCallbackMutationService", "RegistrationTopicFence"]]
FILES += [ADMIN + name + ".java" for name in ["service/RegistrationFundsMybatisIntegrationTest", "service/impl/RegistrationTopicFenceTest", "it/RegistrationTopicFenceMySqlIT"]]

def original(path):
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=ROOT).decode()

def text(path):
    return (ROOT / path).read_text()

funds = ADMIN + "service/RegistrationFundsMybatisIntegrationTest.java"
marker = "    @Test\n"
assert text(funds)[text(funds).index(marker):] == original(funds)[original(funds).index(marker):]
cash = PROD + "CmsRegistrationServiceImpl.java"
marker = "    private void registerPaymentAfterCommit("
assert text(cash)[text(cash).index(marker):] == original(cash)[original(cash).index(marker):]
marker = "    private String applyWechatPayCallback("
assert text(cash)[:text(cash).index(marker)] == original(cash)[:original(cash).index(marker)]
refund = PROD + "RefundServiceImpl.java"
marker = "    private void markRegistrationRefunded("
assert text(refund)[text(refund).index(marker):] == original(refund)[original(refund).index(marker):]
protected = [PROD + name + ".java" for name in ["WechatPayServiceImpl", "TeamGroupServiceImpl", "ClubEventOpsServiceImpl", "SettlementEntryServiceImpl", "SettlementServiceImpl", "CoopSettlementClaimServiceImpl"]]
protected += ["ci/real_storage_it.py", "ci/real-storage-it-registry.json", "pom.xml"]
for path in protected:
    assert text(path) == original(path), path
scan = runpy.run_path(str(ROOT / "ci/real_storage_it.py"))["scan_java"]
counts = {}
for path, count in [(funds, 96), (ADMIN + "service/impl/RegistrationTopicFenceTest.java", 13), (ADMIN + "it/RegistrationTopicFenceMySqlIT.java", 9)]:
    counts[path] = scan(text(path))
    assert counts[path]["declared_tests"] == count
    assert not counts[path]["dynamic"] and not counts[path]["nested"] and not counts[path]["conditions"]
# Check the real mapper base projections against the reused snapshot + explicit IT-only column additions.
fixture = text(ADMIN + "it/RegistrationTopicFenceMySqlIT.java")
snapshot = text("sql/base_schema_20260818.sql")
for table, mapper in [("cms_topic", "CmsTopic"), ("cms_registration", "CmsRegistration"), ("refund_application", "RefundApplication")]:
    block = snapshot[snapshot.index("CREATE TABLE `" + table + "` ("):]
    block = block[:block.index("\n) ENGINE")]
    columns = set(re.findall(r"^\s+`(\w+)`", block, re.M))
    xml = text("chengyinhub-system/src/main/resources/mapper/business/" + mapper + "Mapper.xml")
    selected = re.search(r'<sql id="select\w+Vo">\s*select (.*?) from ' + table, xml, re.S).group(1)
    for column in selected.split(","):
        column = column.strip()
        assert column in columns or re.search(r"ADD COLUMN " + re.escape(column) + r"\s", fixture), (table, column)
for name in ["outer-owner.review.patch", "g-terminal-hook.review.patch"]:
    subprocess.run(["git", "apply", "--check", str(Path(__file__).parent / name)], cwd=ROOT, check=True)
print(json.dumps({
    "baseline": BASE, "status": "STATIC_ONLY", "executed_java_mysql_tests": 0,
    "counts": counts, "unchanged": protected,
    "sha256": {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in FILES},
    "not_run": ["Java compilation", "JUnit", "MySQL", "CI", "cleanup", "G frozen allocation/reversal/payout", "outer-owner lock graph"],
}, ensure_ascii=False, indent=2))

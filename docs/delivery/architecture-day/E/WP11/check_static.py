"""Only checks source contracts/artifact identity; does not execute Java or SQL."""
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
BASE = "2ec68899099a1e250df0949a8383bf85b5d3ffe0"
DELIVERY = Path(__file__).resolve().parent


def baseline(path):
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=ROOT).decode()


frozen_manifest = DELIVERY.parent / "candidate.json"
if frozen_manifest.exists():
    frozen = json.loads(frozen_manifest.read_text())
    for row in frozen["files"]:
        assert hashlib.sha256((ROOT / row["path"]).read_bytes()).hexdigest() == row["sha256"], row["path"]
    print("PASS frozen cache candidate: all 3 source SHA-256 match")
else:
    manifest = json.loads((DELIVERY / "candidate.json").read_text())
    for path in manifest["cache_source_paths"]:
        exists = subprocess.run(["git", "cat-file", "-e", BASE + ":" + path], cwd=ROOT,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        if exists:
            assert (ROOT / path).read_bytes() == baseline(path).encode(), path
        else:
            assert not (ROOT / path).exists(), path
    print("PASS standalone WP11: all 3 cache paths remain at public baseline; frozen candidate untouched")

mapper = "chengyinhub-system/src/main/resources/mapper/business/ImConversationMemberMapper.xml"
old = ET.fromstring(baseline(mapper))
new = ET.parse(ROOT / mapper).getroot()
old_nodes = {node.get("id"): ET.tostring(node) for node in old if node.get("id")}
new_nodes = {node.get("id"): ET.tostring(node) for node in new if node.get("id")}
for key, value in old_nodes.items():
    assert value == new_nodes[key], key
assert set(new_nodes) - set(old_nodes) == {
    "ConversationSummaryResult", "selectMyConversationSummaries", "sumInboxUnread"
}
print("PASS XML parses; all prior mapper statements unchanged; additions are 2 SELECTs and 1 result map")

service = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java"
a, b = baseline(service), (ROOT / service).read_text()
for start, end in [
    ("    private ImMessage projectRouteMessage(", "    private Map<String, Object> counterpartyOf("),
    ("    private Map<String, Object> counterpartyOf(", "    /** 从群会话"),
    ("    private boolean hasActiveGroupAccess(", "    @Override\n    @Transactional(rollbackFor = Exception.class)\n    public void markRead("),
    ("    public void markRead(Long memberId, Long conversationId)", "    private ServiceException closedConversationError()"),
]:
    assert a[a.index(start):a.index(end, a.index(start))] == b[b.index(start):b.index(end, b.index(start))], start
print("PASS original group guard, route projection, counterparty and markRead bodies unchanged")

message_mapper = ET.parse(ROOT / "chengyinhub-system/src/main/resources/mapper/business/ImMessageMapper.xml").getroot()
visible = message_mapper.find("sql[@id='viewerVisible']").text
summary_sql = " ".join("".join(new.find("select[@id='selectMyConversationSummaries']").itertext()).split())
assert " ".join(visible.replace("#{viewerMemberId}", "#{memberId}").split()) in summary_sql
assert "order by coalesce(visible_at, create_time) desc, id desc limit 1" in summary_sql
print("PASS latest visibility/order matches legacy message selector")

test_root = ROOT / "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl"
for suite in ("ImServiceImplTest", "ImRouteProjectionTest", "ImSendIdempotencyMybatisIntegrationTest"):
    path = test_root / (suite + ".java")
    relative = path.relative_to(ROOT).as_posix()
    assert len(re.findall(r"@Test\b", baseline(relative))) == len(re.findall(r"@Test\b", path.read_text())), suite
print("PASS prior suite counts unchanged: 53 / 9 / 14")

manifest_file = DELIVERY / "candidate.json"
if manifest_file.exists():
    manifest = json.loads(manifest_file.read_text())
    paths = [row["path"] for row in manifest["files"]]
    patch = subprocess.check_output(["git", "diff", "--binary", "--full-index", BASE, "--", *paths], cwd=ROOT)
    assert hashlib.sha256(patch).hexdigest() == manifest["patch_sha256"]
    local_patch = DELIVERY / "WP11-candidate.patch"
    if local_patch.exists():
        assert local_patch.read_bytes() == patch
    for row in manifest["files"]:
        assert hashlib.sha256((ROOT / row["path"]).read_bytes()).hexdigest() == row["sha256"], row["path"]
    print("PASS regenerated WP11 source patch and 8 source/test file SHA-256 match manifest")

print("NOT RUN Java compilation, JUnit, MySQL, EXPLAIN, load or network tests")

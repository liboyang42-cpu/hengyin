#!/usr/bin/env python3
"""Git/Python static scope check. Never runs Java, storage, HTTP or CI."""
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess

BASE = "4197f6a6b11d62cb0a742aae73d7167c93114585"
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
PROD = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AiCreatorServiceImpl.java"
TEST = "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorServiceImplTest.java"
NEW = "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorPlayerDraftQuotaTest.java"
DOC = "docs/delivery/architecture-day/A-player-draft-display/"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def before(path):
    return git("show", f"{BASE}:{path}").decode()


def now(path):
    return (ROOT / path).read_text()


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def main():
    old, new = before(PROD), now(PROD)
    expected = old.replace(
        "// player 走独立的 theme_draft 专项额度(比 gen 池更紧),两者取更紧的那个。",
        "// 玩家起草只受独立专项额度限制，展示与实际预占、成功回包使用同一池。", 1).replace(
        '        // 网关 gen 池与其它创作类共用,被用光时专项额度再有剩余也生成不了。\n'
        '        int gatewayRemaining = Math.max(0, gateway.remainingQuota(memberId, "theme_draft"));\n',
        '', 1).replace('resp.setRemaining(Math.min(playerRemaining, gatewayRemaining));',
                      'resp.setRemaining(playerRemaining);', 1)
    assert expected != old and new == expected, "unexpected production delta"
    old_test, new_test = before(TEST), now(TEST)
    expected_test = old_test.replace(
        '        assertNotNull(resp.getDraft());\n        assertEquals(1, reserved.size());',
        '        assertNotNull(resp.getDraft());\n'
        '        assertEquals(Integer.valueOf(2), resp.getRemainingQuota());\n'
        '        assertEquals(1, reserved.size());', 1).replace(
        '    void playerThemeDraftQuotaReportsZeroWhenSharedGatewayPoolIsExhausted() {\n',
        '    void playerThemeDraftQuotaReportsZeroWhenSharedGatewayPoolIsExhausted() {\n'
        '        // 保留历史用例标识；人类已裁决 player 只受专用起草额度限制，旧0预期改为3。\n', 1).replace(
        '        assertEquals(Integer.valueOf(0), resp.getRemaining());',
        '        assertEquals(Integer.valueOf(3), resp.getRemaining());\n'
        '        verify(gateway, never()).remainingQuota(MEMBER_ID, "theme_draft");', 1)
    assert expected_test == new_test, "unexpected change to original tests"
    assert re.findall(r"void (\w+)\(", old_test) == re.findall(r"void (\w+)\(", new_test)
    assert len(re.findall(r"^\s*@Test\s*$", old_test, re.M)) == 35
    assert old_test.count("@org.junit.jupiter.params.ParameterizedTest") == 1
    assert "@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})" in new_test
    assert digest(old_test) == "cfe5a151b39ce394531b6eb6fe5856cec60d57cc98060a633617a90ed571638c"

    changed = set(git("diff", "--name-only", BASE).decode().splitlines())
    untracked = set(git("ls-files", "--others", "--exclude-standard").decode().splitlines())
    assert all(p in {PROD, TEST, NEW} or p.startswith(DOC) for p in changed | untracked)
    git("diff", "--check", BASE)
    extra = now(NEW)
    assert extra.count("@ParameterizedTest") == 1
    assert "@ValueSource(booleans = {false, true})" in extra
    assert "@Disabled" not in extra and "Assumptions." not in extra
    assert "new AiNotSentGatewayFixture(MEMBER)" in extra and "new AiCreatorServiceImpl()" in extra
    assert "member.setRole(\"player\")" in extra
    assert "creator.themeDraftPrepared(" in extra and "creator.themeDraft(" in extra
    assert "genUsed.get()" in extra and "playerUsed.get()" in extra
    # Lexical delimiter check only: it does not establish Java type checking or JUnit execution.
    stripped = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/', '', extra)
    stack = []
    for char in stripped:
        if char in "({[":
            stack.append(char)
        elif char in ")}]":
            assert stack.pop() == {")": "(", "}": "{", "]": "["}[char]
    assert not stack
    ast.parse(Path(__file__).read_text())
    evidence = {
        "base": BASE,
        "production_scope": "only themeDraftQuota player projection; all other production bytes unchanged",
        "old_cases": "37 authored, all original methods preserved; one policy assertion 0 -> 3; NOT_RUN",
        "new_cases": "2 authored legacy/prepared real Creator+Gateway with unit doubles; NOT_RUN",
        "source_hashes": {p: {"before": digest(before(p)) if p != NEW else None,
                              "candidate": digest(now(p))} for p in (PROD, TEST, NEW)},
        "checks": "PASS: exact source/test delta, scope, method/case preservation, lexical delimiters, git diff --check",
        "not_run": ["Java compile", "JUnit", "MySQL", "Redis", "CI", "HTTP/provider", "real role change"],
    }
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

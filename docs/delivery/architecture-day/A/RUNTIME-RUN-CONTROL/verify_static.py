"""Finite extraction checks, not a Java compiler or behavioral/transaction acceptance."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import textwrap

ROOT = Path(__file__).resolve().parents[5]
BASE = "d49c12b820419308236010e0710f96d7bf48c4c9"
IMPL = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
PARENT = IMPL + "GameSessionRuntimeServiceImpl.java"
CHILD = IMPL + "GameSharedRunControl.java"
API = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/IGameSessionRuntimeService.java"
TEST = "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/GameSharedRouteQueryTest.java"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode()


def mask(source):
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',
                  lambda m: "".join("\n" if c == "\n" else " " for c in m.group()), source)


def methods(source):
    # Only four-space class methods, preserving literal bodies for exact comparison.
    masked = mask(source)
    pattern = r'^    (?![@ ])(?:(?:public|private|protected)\s+)?[^;\n={}@]+?\b(\w+)\s*\([^;{}]*\)\s*(?:throws[^{]+)?\{'
    result = {}
    for match in re.finditer(pattern, masked, re.M):
        depth, end = 1, match.end()
        while depth:
            depth += (masked[end] == "{") - (masked[end] == "}")
            end += 1
        result.setdefault(match.group(1), []).append(source[match.end():end - 1])
    return result


old = git("show", f"{BASE}:{PARENT}")
parent, child, api, test = [(ROOT / p).read_text() for p in (PARENT, CHILD, API, TEST)]
om, pm, cm = [methods(s) for s in (old, parent, child)]
protected = ("command", "receipt", "recordFailure", "preauthorize", "dispatch", "bumpSession",
             "receiptClaim", "replay", "toReceiptView", "sharedRouteSnapshot", "refreshZeroHpRead")
for name in protected:
    assert pm[name] == om[name], name
assert re.findall(r'@Transactional\([^\n]+', old) == re.findall(r'@Transactional\([^\n]+', parent)
assert re.findall(r'@Autowired[^\n]*', old) == re.findall(r'@Autowired[^\n]*', parent)

exact_moved = sorted(set(om) & set(cm) - {"anchorSharedRun", "beginManual"})
for name in exact_moved:
    assert cm[name] == om[name], name
for name in ("bindBasicArrivalProof", "routeActionSummary", "sharedRoutePlayer"):
    expected = [body.replace("TopicRouteRuntimeServiceImpl.gameplayAddon", "GameplaySupportMatrix.addon")
                .replace("TopicRouteRuntimeServiceImpl.gameplayActionSummary", "GameplayActionProjection.gameplayActionSummary")
                .replace("TopicRouteRuntimeServiceImpl.gameplayChallengeSummary", "GameplayActionProjection.gameplayChallengeSummary")
                for body in om[name]]
    assert pm[name] == expected, name
assert "TopicRouteRuntimeServiceImpl." not in parent
assert not (set(re.findall(r'(?<![\w.])([a-zA-Z]\w+)\s*\(', mask(child))) & set(om) - set(cm))
for first, following in (("RUN_PAUSE", "ARRIVAL_ISSUE"), ("TEAM_EXIT", "MANUAL_BEGIN"),
                         ("MANUAL_REVIEW", None), ("ARRIVAL_REVOKE", "ACT_UNLOCK"),
                         ("ACT_UNLOCK", "QUEST_GUIDE"), ("LOCATION_REBIND", "TEAM_EXIT")):
    # Longer lifecycle sections include these smaller sections; literal cases remain byte-identical.
    body = om["runControlAction"][0]
    start = body.index(f'            case "{first}":')
    if following:
        end = body.index(f'            case "{following}":', start)
    else:
        end = body.index("            default:", start)
    section = body[start:end]
    # The original long review section does not include the earlier arrival/location cases.
    assert section in cm["applyLifecycle"][0], first

original_import = om["importManual"][0]
replay_body = pm["importManual"][0]
critical = original_import[original_import.index("        preauthorize("):original_import.index("        run = runControl(session);")]
assert critical in replay_body
assert replay_body.index("if (replay.alreadyApplied())") < replay_body.index("preauthorize(")
assert 'if (signature.path("used").asBoolean()) return new ManualReplay(key, signature, null, null);' in child
fallback = om["runControlAction"][0]
start = fallback.index('                runPayload(command, "reason");')
end = fallback.index("                bumpSession(session, session.getStatus()", start)
assert textwrap.dedent(fallback[start:end]).strip() in textwrap.dedent(cm["closeFallback"][0])
assert "GameSessionRuntimeServiceImpl" not in child
assert not re.search(r'@(?:Service|Component|Autowired|Transactional)|java\.util\.function|Executor', child)
for field in ("operationStatus", "authorityMode", "pausedAt"):
    assert not re.search(r'\.put\("' + field + '"', parent), field

query = pm["querySharedRouteState"][0]
for forbidden in ("sharedRouteSnapshot", "refreshZeroHpRead", "ForUpdate", "clock.", "save", "insert", "update", "initialize", "bump"):
    assert forbidden not in query, forbidden
assert 'return owner == null ? null : readObject(owner.getVariablesJson());' in cm["persistedRunControl"][0]
assert pm["projectSharedRouteState"][0].strip() == om["sharedRouteState"][0][om["sharedRouteState"][0].index("        com.chengyinhub.business.domain.route.TopicRouteState view"):].strip()
assert "querySharedRouteState(Long memberId, Long topicId, Long activityId);" in api
declaration = ('    /** Persisted shared run/TEAM projection only; never initializes or refreshes gameplay state. */\n'
               '    com.chengyinhub.business.domain.route.TopicRouteState querySharedRouteState(Long memberId, Long topicId, Long activityId);\n')
assert api.replace(declaration, "", 1) == git("show", f"{BASE}:{API}")
assert "verifyNoMoreInteractions" in test and "query must not evaluate or refresh gameplay time" in test
assert test.count("@Test ") == 7 and test.count("@ParameterizedTest") == 1  # seven + two parameter values
for source in (parent, child, api, test):
    stack = []
    for char in mask(source):
        if char in "([{":
            stack.append(char)
        elif char in ")]}":
            assert stack and stack.pop() == {")": "(", "]": "[", "}": "{"}[char]
    assert not stack  # Lexical delimiters only, not Java syntax/type checking.

changed = set(git("diff", "--name-only", BASE).splitlines())
assert changed <= {PARENT, CHILD, API, TEST} | {p for p in changed if p.startswith("docs/delivery/architecture-day/A/RUNTIME-RUN-CONTROL/")}, changed
git("diff", "--check")
output = {"baseline": BASE, "status": "STATIC_ONLY_PASS", "java_mysql_ci": "NOT_RUN",
          "parent_lines": [len(old.splitlines()), len(parent.splitlines())],
          "collaborator_lines": len(child.splitlines()), "unchanged_entry_methods": list(protected),
          "exact_reused_method_groups_including_pure_selectors": exact_moved, "new_unit_cases_authored": 9,
          "files_sha256": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in (PARENT, CHILD, API, TEST)}}
print(json.dumps(output, ensure_ascii=False, indent=2))

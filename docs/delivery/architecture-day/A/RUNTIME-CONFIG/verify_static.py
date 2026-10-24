"""This candidate only: exact extraction/adapter checks, not compilation or regression."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[5]
BASE = "062c6cb71ffa0ab6b005ee622e8a2045e4c496cd"
PREFIX = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
PARENT, CHILD = PREFIX + "GameSessionRuntimeServiceImpl.java", PREFIX + "GameSessionConfiguration.java"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode()


def mask(source):
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',
                  lambda m: "".join("\n" if c == "\n" else " " for c in m.group()), source)


def methods(source):
    masked, result = mask(source), {}
    pattern = r'^    (?![@ ])(?:(?:public|private|protected)\s+)?[^;\n={}@]+?\b(\w+)\s*\([^;{}]*\)\s*(?:throws[^{]+)?\{'
    for match in re.finditer(pattern, masked, re.M):
        depth, end = 1, match.end()
        while depth:
            depth += (masked[end] == "{") - (masked[end] == "}")
            end += 1
        result.setdefault(match.group(1), []).append(source[match.start():end])
    return result


old = git("show", f"{BASE}:{PARENT}")
parent, child = [(ROOT / p).read_text() for p in (PARENT, CHILD)]
om, pm, cm = [methods(s) for s in (old, parent, child)]
body = lambda source: source[source.index("{"):]
adapters, unchanged = [], []
for name in sorted(om.keys() & pm.keys()):
    if pm[name] == om[name]:
        unchanged.append(name)
        continue
    assert len(pm[name]) == len(om[name]) == 1
    original, current = om[name][0], pm[name][0]
    signature = original[:original.index("{")]
    # Only split commas outside generic types, so Map<String,Object> is one parameter.
    parameters = re.search(r'\(([^()]*)\)', signature, re.S).group(1)
    parameters = re.sub(r'<[^<>]*>', '', parameters)
    args = ", ".join(p.strip().split()[-1] for p in parameters.split(",") if p.strip())
    assert current == signature + '{ return configuration().' + name + '(' + args + '); }', name
    adapters.append(name)
exact = sorted(om.keys() & cm.keys())
for name in exact:
    assert [body(s) for s in om[name]] == [body(s) for s in cm[name]], name
assert len(exact) == 46 and len(adapters) == 26
assert len(om.keys() - pm.keys()) == 14 and len(unchanged) == 261
assert set(pm) - set(om) == {"configuration"}
def ready_type(source):
    start = source.index("static final class ReadyConfig {")
    end = source.index("\n    }", start) + len("\n    }")
    return source[start:end].replace("private final ", "final ")
assert ready_type(old) == ready_type(child)  # Same typed result; only field access crosses the package boundary.
assert not (set(re.findall(r'(?<![\w.])([a-zA-Z]\w+)\s*\(', mask(child))) & set(om) - set(cm))
assert not re.search(r'@(?:Autowired|Service|Component|Transactional)|GameSessionRuntimeServiceImpl|\b(?!(?:ObjectMapper|objectMapper)\b)\w*Mapper\b|\b\w*Clock\b|callback|Executor', child)
assert re.findall(r'private final [^;]+;', child) == ['private final ObjectMapper objectMapper;']
assert re.findall(r'@Transactional\([^\n]+', old) == re.findall(r'@Transactional\([^\n]+', parent)
assert re.findall(r'@Autowired[^\n]*', old) == re.findall(r'@Autowired[^\n]*', parent)
for source in (parent, child):
    stack = []
    for char in mask(source):
        if char in "([{":
            stack.append(char)
        elif char in ")]}":
            assert stack and stack.pop() == {")": "(", "]": "[", "}": "{"}[char]
    assert not stack
changed = set(git("diff", "--name-only", BASE).splitlines())
assert all(p in (PARENT, CHILD) or p.startswith("docs/delivery/architecture-day/A/RUNTIME-CONFIG/") for p in changed)
git("diff", "--check")
print(json.dumps({"baseline": BASE, "status": "STATIC_ONLY_PASS", "java_junit_mysql_ci": "NOT_RUN",
                  "parent_lines": [len(old.splitlines()), len(parent.splitlines())], "owner_lines": len(child.splitlines()),
                  "exact_method_bodies": exact, "adapters": adapters, "unchanged_parent_method_groups": len(unchanged),
                  "files_sha256": {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in (PARENT, CHILD)}},
                 ensure_ascii=False, indent=2))

#!/usr/bin/env python3
"""Static source/patch equivalence only; does not compile Java or establish SQL behavior."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[5]
DOCS = Path(__file__).resolve().parent
BASE = "d586c143eb0a4499aeb15cbb3d173496f18b83e2"
SHARED = "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java"
OWNER = SHARED.replace("CmsTopicServiceImpl.java", "TopicQueryService.java")


def original(path):
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=ROOT, text=True)


def methods(source):
    masked = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                    lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), source, flags=re.S)
    pattern = re.compile(r'^    (?![ \t])(?:(?:public|private|protected|static|final|synchronized)\s+)*'
                         r'(?:[\w.$<>?,\[\] ]+?\s+)?(?P<name>\w+)\s*\([^;{}]*?\)\s*'
                         r'(?:throws\s+[\w.,\s]+)?\{', re.M)
    out = {}
    for match in pattern.finditer(masked):
        begin = match.end() - 1
        depth = 0
        for end in range(begin, len(masked)):
            if masked[end] == '{': depth += 1
            elif masked[end] == '}':
                depth -= 1
                if depth == 0: break
        header = source[match.start():begin]
        parameters = header[header.index('('):header.rindex(')') + 1]
        key = (match['name'], re.sub(r'\s+', '', parameters))
        assert key not in out, key
        out[key] = (header, source[begin:end + 1])
    return out


before = methods(original(SHARED))
owner = methods((ROOT / OWNER).read_text())
evidence = json.loads((DOCS / 'migration-evidence.json').read_text())
moved = {(m['method_name'], m['parameters']): m for m in evidence['moved_exact_bodies']}
assert len(moved) == 21 and len(owner) == 22
for key, item in moved.items():
    assert before[key][1] == owner[key][1], key
    assert hashlib.sha256(owner[key][1].encode()).hexdigest() == item['body_sha256'], key
assert before[('nullToEmpty', '(List<T>rows)')][1] == owner[('nullToEmpty', '(List<T>rows)')][1]
fixtures = json.loads((DOCS / 'test-wiring-evidence.json').read_text())
with tempfile.TemporaryDirectory(prefix='e-topic-query-static-') as temporary:
    scratch = Path(temporary)
    for path in [SHARED] + [item['path'] for item in fixtures]:
        target = scratch / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(original(path))
    for name in ['review.patch', 'test-wiring.review.patch']:
        subprocess.run(['git', 'apply', '--check', str(DOCS / name)], cwd=scratch, check=True)
        subprocess.run(['git', 'apply', str(DOCS / name)], cwd=scratch, check=True)
    after = methods((scratch / SHARED).read_text())
    delegates = 0
    for key, (header, body) in before.items():
        if key not in moved:
            assert after[key][1] == body, key
        elif header.lstrip().startswith('public '):
            parameters = header[header.index('(') + 1:header.rindex(')')]
            args = ', '.join(p.strip().split()[-1] for p in parameters.split(',') if p.strip())
            assert after[key][1] == '{\n        return topicQueryService.' + key[0] + '(' + args + ');\n    }', key
            delegates += 1
        else:
            assert key not in after, key
    assert delegates == 13
    for fixture in fixtures:
        path = fixture['path']
        old = original(path)
        expected = old.replace('CmsTopicServiceImpl', 'TopicQueryService')
        expected, count = re.subn(r'(?m)^[ \t]*org\.springframework\.test\.util\.ReflectionTestUtils\.setField'
                                  r'\(service, "supplyReviewService",[\s\S]*?;\n', '', expected)
        assert count == fixture['removed_write_owner_fixture_wirings'], path
        assert (scratch / path).read_text() == expected, path
        assert len(re.findall(r'@Test\b', expected)) == fixture['unchanged_test_cases'], path
print('STATIC PASS: 21 exact moved bodies; 128 other bodies preserved; 13 delegates; 8 fixtures/44 original cases mechanically retained')

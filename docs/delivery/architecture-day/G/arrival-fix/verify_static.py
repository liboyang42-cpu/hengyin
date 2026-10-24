#!/usr/bin/env python3
"""Five authorized Java files only; source comparison, never Java/business execution."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

BASE = '4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa'
NEGATIVE = 'acc4bf5ea10bbefabb9d5db52dd17dc800b3a955'
PROD = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ShopDayProofServiceImpl.java'
TESTROOT = 'chengyinhub-admin/src/test/java/com/chengyinhub/'
TESTS = [TESTROOT + p for p in [
    'business/service/ShopDayProofServiceTest.java',
    'business/service/ShopDayGameThenPhotoH2Test.java',
    'business/service/PlayerNodeArrivalSharingH2Test.java',
    'business/it/ThemeRuntimeMySqlIT.java',
]]


def git(*args):
    return subprocess.check_output(['git', *args]).decode()


def mask(text):
    # Bounded fixtures contain no text blocks; ignore braces in comments/literals.
    assert '"""' not in text
    return re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), text, flags=re.S)


def method(text, name):
    clean = mask(text)
    matches = list(re.finditer(r'\b(?:public|private|protected)?\s*(?:static\s+)?[\w<>\[\], ?]+\s+'
                              + re.escape(name) + r'\s*\([^)]*\)\s*(?:throws\s+[\w., ]+)?\s*\{', clean))
    assert len(matches) == 1, (name, len(matches))
    start = clean.index('{', matches[0].start())
    depth = 1
    end = start + 1
    while depth:
        if clean[end] == '{':
            depth += 1
        elif clean[end] == '}':
            depth -= 1
        end += 1
    return text[start:end]


def cases(text):
    return re.findall(r'@Test\s+void\s+(\w+)\s*\(', text)


results = []
for path in TESTS:
    old = git('show', BASE + ':' + path)
    now = Path(path).read_text()
    for name in cases(old):
        assert method(old, name) == method(now, name), (path, name, 'old assertion/body changed')
    assert not re.search(r'@Disabled|Assumptions\.|assumeTrue\(', now)
    results.append(dict(path=path, original_cases=cases(old), candidate_cases=cases(now),
                        added_cases=[n for n in cases(now) if n not in cases(old)],
                        all_original_test_bodies_byte_unchanged=True))

it = TESTS[-1]
original = git('show', BASE + ':' + it)
now = Path(it).read_text()
assert now.split('    // G WP05 negative-only additions:', 1)[0] + '}\n' == original
negative = git('show', NEGATIVE + ':' + it)
for name in cases(negative):
    if name not in cases(original):
        expected = method(negative, name).replace(
            '        ReflectionTestUtils.setField(realArrival,"playerNodeProgressMapper",sql.getMapper(PlayerNodeProgressMapper.class));',
            '        ReflectionTestUtils.setField(realArrival,"playerNodeProgressMapper",sql.getMapper(PlayerNodeProgressMapper.class));\n'
            '        ReflectionTestUtils.setField(realArrival,"playerNodeProgressService",arrivalProgressOwner());')
        assert method(now, name) == expected, name

old = git('show', BASE + ':' + PROD)
now = Path(PROD).read_text()
protected = ['hasArrived', 'submitPhoto', 'markGameDone', 'probe', 'samePlace', 'parseCoordinate', 'isPhotoWritableStatus']
for name in protected:
    assert method(old, name) == method(now, name), name
assert now.count('playerNodeProgressService.selectPlayerNodeProgressList(') == 1
assert 'private IPlayerNodeProgressService playerNodeProgressService;' in now
assert '@Autowired(required' not in now
assert 'setStatus(1)' not in now and now.count('setStatus(0)') == 2
assert '"default"' not in now
assert [len(r['candidate_cases']) for r in results] == [9, 9, 9, 39]

allowed = {PROD, *TESTS}
changed = git('diff', '--name-only', BASE).splitlines()
assert all(p in allowed or p.startswith('docs/delivery/architecture-day/G/arrival-') for p in changed), changed
report = dict(kind='STATIC_SOURCE_ONLY_NOT_JAVA_OR_DB_VERIFICATION', base=BASE, negative=NEGATIVE,
              original_total_cases=sum(len(r['original_cases']) for r in results),
              candidate_total_cases=sum(len(r['candidate_cases']) for r in results), tests=results,
              protected_production_method_bodies_byte_unchanged=protected,
              files=[dict(path=p, base_sha256=hashlib.sha256(git('show', BASE + ':' + p).encode()).hexdigest(),
                          candidate_sha256=hashlib.sha256(Path(p).read_bytes()).hexdigest()) for p in [PROD, *TESTS]])
print(json.dumps(report, ensure_ascii=False, indent=2))

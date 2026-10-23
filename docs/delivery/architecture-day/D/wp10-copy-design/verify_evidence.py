#!/usr/bin/env python3
"""Read-only WP10 evidence verification, not Java/DB/behavior validation.

Run against the public ad7e source tree (or its exact read-only snapshot):
  python3 verify_evidence.py --source-root /path/to/ad7e
No dependency installation, backend execution, writes, network or DDL.
"""
import argparse
import csv
import hashlib
import json
import pathlib
import re


def mask_java(raw):
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    return re.sub(pattern, lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), raw)


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--source-root', required=True, type=pathlib.Path)
    ns = args.parse_args()
    here = pathlib.Path(__file__).resolve().parent
    root = ns.source_root.resolve()
    evidence = json.loads((here / 'SOURCE_FINGERPRINTS.json').read_text())
    assert evidence['base_sha'] == 'ad7e59fbf6a20e85e741318afe654c5411bf1101'
    for entry in evidence['files']:
        data = (root / entry['path']).read_bytes()
        assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['path']
        blob = b'blob ' + str(len(data)).encode() + b'\0' + data
        assert hashlib.sha1(blob).hexdigest() == entry['git_blob'], entry['path']

    callers = []
    refs = set()
    source_count = 0
    main_files = sorted(root.glob('**/src/main/java/**/*.java'))
    public_methods = {m['method'] for m in json.loads((here / 'PUBLIC_METHODS.json').read_text())}
    for path in main_files:
        source_count += 1
        raw = path.read_text()
        masked = mask_java(raw)
        if not re.search(r'\b(?:ICmsTopicService|CmsTopicServiceImpl)\b', masked):
            continue
        rel = str(path.relative_to(root))
        refs.add(rel)
        receivers = set(re.findall(r'\b(?:ICmsTopicService|CmsTopicServiceImpl)\s+(\w+)\b', masked))
        for receiver in receivers:
            pat = r'\b' + re.escape(receiver) + r'\s*\.\s*(\w+)\s*\('
            for match in re.finditer(pat, masked):
                if match[1] in public_methods:
                    callers.append((rel, masked.count('\n', 0, match.start()) + 1, receiver, match[1]))
            pat = r'\b' + re.escape(receiver) + r'\s*::\s*(\w+)'
            for match in re.finditer(pat, masked):
                if match[1] in public_methods:
                    callers.append((rel, masked.count('\n', 0, match.start()) + 1, receiver, match[1]))
        pat = r'cmsTopicServiceProvider\.getObject\(\)\.(\w+)\s*\('
        for match in re.finditer(pat, masked):
            callers.append((rel, masked.count('\n', 0, match.start()) + 1,
                            'cmsTopicServiceProvider.getObject()', match[1]))

    with (here / 'PRODUCTION_CALLERS.csv').open() as src:
        rows = list(csv.DictReader(src))
    expected = [(r['path'], int(r['line']), r['receiver'], r['method']) for r in rows]
    assert sorted(callers) == sorted(expected), 'production caller inventory changed'
    summary = json.loads((here / 'AUDIT_SUMMARY.json').read_text())
    assert source_count == summary['production_java'] == 2438
    assert len(refs) == summary['type_reference_files'] == 61
    assert len(callers) == summary['direct_and_provider_call_sites'] == 192
    assert len({c[0] for c in callers}) == summary['direct_and_provider_caller_files'] == 55
    forward = json.loads((here / 'FORWARDED_CALLERS.json').read_text())
    assert len(forward) == summary['forwarded_calls'] == 7
    assert len({r['path'] for r in forward}) == summary['forwarded_files'] == 4
    for row in forward:
        actual = (root / row['path']).read_text().splitlines()[row['line'] - 1].strip()
        assert actual == row['call'], row
    tests = json.loads((here / 'EXISTING_TESTS.json').read_text())
    assert len(tests) == 22 and all(t['executed_this_task'] is False for t in tests)
    print(json.dumps({'result': 'PASS', 'source_fingerprints': len(evidence['files']),
                      'production_java': source_count, 'caller_sites': len(callers),
                      'caller_files': 55, 'forwarded_sites': 7, 'forwarded_files': 4,
                      'java_db_tests_executed': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()

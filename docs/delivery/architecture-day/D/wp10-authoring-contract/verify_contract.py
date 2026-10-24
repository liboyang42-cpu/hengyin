#!/usr/bin/env python3
"""Verify the doc inventory against pinned public source. Does not execute Java or prove equivalence."""
import argparse
import hashlib
import json
import pathlib
import re


def mask(text):
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), text)


def bodies(text):
    clean = mask(text)
    pat = r'(?m)^    (?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>, ?\[\].]+?\s+(\w+)\s*\(([^;{}]*)\)\s*(?:throws\s+[\w., ]+)?\{'
    found = {}
    for m in re.finditer(pat, clean):
        depth, end = 1, m.end()
        while depth:
            depth += (clean[end] == '{') - (clean[end] == '}')
            end += 1
        found[(m[1], ' '.join(m[2].split()))] = hashlib.sha256(text[m.end():end-1].encode()).hexdigest()
    return found


def test_bodies(text):
    clean = mask(text)
    found = {}
    for m in re.finditer(r'@Test\b[\s\S]*?\bvoid\s+(\w+)\s*\(', clean):
        start = clean.index('{', m.end())
        depth, end = 1, start + 1
        while depth:
            depth += (clean[end] == '{') - (clean[end] == '}')
            end += 1
        assert m[1] not in found, m[1]
        found[m[1]] = hashlib.sha256(text[start+1:end-1].encode()).hexdigest()
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=pathlib.Path, required=True)
    parser.add_argument('--fallback-source-root', type=pathlib.Path)
    args = parser.parse_args()
    folder = pathlib.Path(__file__).resolve().parent
    source_info = json.loads((folder/'SOURCES.json').read_text())
    matrix = json.loads((folder/'TEST_MATRIX.json').read_text())
    methods = json.loads((folder/'METHODS.json').read_text())
    assert source_info['base_sha'] == matrix['base_sha'] == methods['base_sha'] == '864e708e2f38130e21780485e462f8dc147bb76f'
    sources = {}
    for f in source_info['files']:
        matched = None
        for root in [args.source_root, args.fallback_source_root]:
            if root is None or not (root/f['path']).is_file():
                continue
            data = (root/f['path']).read_bytes()
            blob = hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
            if blob == f['sha']:
                assert hashlib.sha256(data).hexdigest() == f['sha256'], f['path']
                matched = data.decode()
                break
        assert matched is not None, 'pinned source mismatch: '+f['path']
        sources[f['path']] = matched
    actual = bodies(sources[methods['source']])
    assert len(methods['definitions']) == 25
    assert len({x['name'] for x in methods['definitions']}) == 23
    for item in methods['definitions']:
        assert actual[(item['name'], item['args'])] == item['body_sha256'], item['name']
        for call in item['callsites_by_name']:
            assert sources[methods['source']].splitlines()[call['line']-1].strip() == call['source_line']
    classes = {x['class_name']: x for x in matrix['classes']}
    assert len(classes) == 9
    expected = {(c['class_name'], c['case_name']): c['old_body_sha256'] for c in matrix['cases']}
    assert len(expected) == len(matrix['cases']) == matrix['case_count'] == 141
    actual_tests = {}
    for name, c in classes.items():
        found = test_bodies(sources[c['path']])
        assert len(found) == c['tests']
        actual_tests.update({(name, case): sha for case, sha in found.items()})
    assert actual_tests == expected, 'old test cases/body hashes differ'
    ids = re.findall(r'^\| (G\d\d) ', (folder/'GRAPH_CASES.md').read_text(), re.M)
    assert ids == ['G%02d' % n for n in range(1,33)]
    print(json.dumps({'result':'PASS', 'pinned_source_files':len(sources), 'move_definitions':25,
                      'move_names':23, 'old_classes':9, 'old_case_bodies':141, 'planned_graph_cases':32,
                      'java_mysql_executed':False, 'equivalence_proven':False}))


if __name__ == '__main__':
    main()

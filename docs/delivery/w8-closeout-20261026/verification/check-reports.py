#!/usr/bin/env python3
"""Verify fresh Surefire reports against an explicit FQCN list. No builds."""
import argparse
import collections
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('classes', type=pathlib.Path)
p.add_argument('reports', type=pathlib.Path)
a = p.parse_args()
expected = set(a.classes.read_text().splitlines())
counts = collections.defaultdict(lambda: collections.Counter())
for report in a.reports.rglob('TEST-*.xml'):
    root = ET.parse(report).getroot()
    for case in root.iter('testcase'):
        name = case.get('classname', '')
        parent = name.split('$')[0]
        key = name if name in expected else parent
        counts[key]['tests'] += 1
        for tag in ('skipped', 'failure', 'error'):
            counts[key][tag] += int(case.find(tag) is not None)
    for suite in root.iter('testsuite'):
        if any(int(suite.get(k, '0')) for k in ('failures', 'errors', 'skipped')):
            counts['__suite_failure__']['errors'] += 1
bad = {name: dict(counts[name]) for name in expected
       if counts[name]['tests'] == 0 or any(counts[name][k] for k in ('skipped', 'failure', 'error'))}
if counts['__suite_failure__']:
    bad['__suite_failure__'] = dict(counts['__suite_failure__'])
print(json.dumps({'expectedClasses': len(expected), 'classes': {k: dict(counts[k]) for k in sorted(expected)},
                  'failures': bad, 'result': 'FAIL' if bad else 'PASS'}, indent=2))
sys.exit(bool(bad))

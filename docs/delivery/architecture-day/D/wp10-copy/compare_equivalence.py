#!/usr/bin/env python3
"""Compare actual old/new MySQL receipts. Never creates or substitutes execution evidence.

Run only after BOTH controlled runs passed all 25 cases with clean scratch teardown.
Surrogate IDs are deliberately compared exactly: each case starts from the same explicit IDs/counters.
Only operation audit timestamps and fresh checkin-code bytes were normalized by the shared fixture;
the IT separately asserts code format/newness/uniqueness. Business dates, owner IDs, flags, fees,
lineage, graph links, payloads, row counts and mapper write order are not normalized here.
"""
import argparse
import json
import pathlib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('legacy_dir', type=pathlib.Path)
    parser.add_argument('extracted_dir', type=pathlib.Path)
    args = parser.parse_args()
    legacy = {p.name: p for p in args.legacy_dir.glob('*.json')}
    extracted = {p.name: p for p in args.extracted_dir.glob('*.json')}
    cases = json.loads((pathlib.Path(__file__).parent / 'mysql-cases.json').read_text())
    expected = {name + '.json' for name in cases['case_names']}
    assert set(legacy) == set(extracted) == expected and len(expected) == 25, 'both exact 25-case receipt sets are required'
    for name in sorted(legacy):
        old, new = json.loads(legacy[name].read_text()), json.loads(extracted[name].read_text())
        assert old.pop('owner') == 'legacy', name
        assert new.pop('owner') == 'extracted', name
        assert old['outcome'] != 'UNSET' and new['outcome'] != 'UNSET', name
        assert old == new, 'T14 behavior mismatch: ' + name
    print('PASS: 25 old/new MySQL receipt pairs; full content rows, outcomes and mapper write order equal')


if __name__ == '__main__':
    main()

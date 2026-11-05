#!/usr/bin/env python3
"""Exercise the inventory exception using the caller's verified Gitleaks binary."""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = 'docs/evidence/popup-style-1003/before/source-inventory.json'
LINES = (11975, 12177, 12305, 12320, 12336, 12350, 12378, 12422, 12424)


def scan(binary, path, content, expected):
    with tempfile.TemporaryDirectory(prefix='gitleaks-inventory-') as directory:
        source = Path(directory) / 'source'
        fixture = source / path
        fixture.parent.mkdir(parents=True)
        fixture.write_text(content)
        report = Path(directory) / 'report.json'
        result = subprocess.run([
            binary, 'dir', '--redact', '--no-banner', '--config',
            str(ROOT / '.gitleaks.toml'), '--report-format', 'json',
            '--report-path', str(report), str(source),
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        findings = (json.loads(report.read_text()) or []) if report.exists() else None
        if result.returncode != expected or findings is None:
            raise AssertionError('scanner exit/report contract failed')
        if bool(findings) != bool(expected):
            raise AssertionError('finding contract failed')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--gitleaks', required=True)
    args = parser.parse_args()
    version = subprocess.run([args.gitleaks, 'version'], capture_output=True, check=True)
    if version.stdout.decode().strip() != '8.24.3':
        raise AssertionError('requires Gitleaks 8.24.3')
    original = (ROOT / INVENTORY).read_bytes()
    entries = original.decode().splitlines()
    known = [entries[number - 1] for number in LINES]
    scan(args.gitleaks, INVENTORY, '\n'.join(known) + '\n', 0)
    # Generated only at runtime: no fixed credential-shaped value in test source.
    credential = ''.join(secrets.SystemRandom().sample('0123456789abcdef' * 4, 64))
    scan(args.gitleaks, INVENTORY, json.dumps({'api_key': credential}), 1)
    first = json.loads('{' + known[0].strip().rstrip(',') + '}')
    digest = next(iter(first.values()))
    scan(args.gitleaks, INVENTORY, json.dumps({'api_key': digest}), 1)
    scan(args.gitleaks, 'other/source-inventory.json', '\n'.join(known), 1)
    changed = known[0].replace(digest, credential)
    scan(args.gitleaks, INVENTORY, changed, 1)
    for walk_path, line_number, action in (
        ('docs/delivery/walk-fix-20261102/integ/fix-shots/i1-walk-readbacks.txt', 100, 'REPORT_SENSOR_FAILURE'),
        ('docs/delivery/walk-fix-20261102/l1-play/RIG-NOTES.md', 64, 'SUBMIT_QA'),
    ):
        walk_line = (ROOT / walk_path).read_text().splitlines()[line_number - 1]
        request = json.loads(walk_line[walk_line.index('{'):])
        if request['action'] != action:
            raise AssertionError('historical rig request changed')
        scan(args.gitleaks, walk_path, walk_line + '\n', 0)
        scan(args.gitleaks, walk_path, walk_line.replace(request['idempotencyKey'], credential), 1)
        scan(args.gitleaks, walk_path, walk_line + ' api_key=' + credential, 1)
        scan(args.gitleaks, 'other/walk-readbacks.txt', walk_line, 1)
        scan(args.gitleaks, walk_path, json.dumps({'api_key': request['idempotencyKey']}), 1)
    # Existing OR exceptions must retain their original behavior.
    scan(args.gitleaks, 'ordinary/config.json', json.dumps({'api_key': 'PUB_KEY_ID_' + credential}), 0)
    legacy_paths = (
        'chengyinhub-admin/src/test/java/com/chengyinhub/business/security/WithdrawalPiiProtectorTest.java',
        'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/WechatPayCallbackForgedSignatureTest.java',
        'chengyinhub-admin/src/test/resources/journey/seventh-pier/state-keys.json',
        'docs/evidence/popup-style-1003/complete/source-inventory.json',
        'docs/evidence/popup-style-1003/after/source-inventory.json',
        'docs/evidence/popup-style-1003/verification.json',
    )
    for path in legacy_paths:
        scan(args.gitleaks, path, json.dumps({'api_key': credential}), 0)
    if (ROOT / INVENTORY).read_bytes() != original:
        raise AssertionError('historical inventory changed')
    print(json.dumps({'scanner': '8.24.3', 'cases': 22, 'pass': True,
                      'inventory_sha256': hashlib.sha256(original).hexdigest()}))


if __name__ == '__main__':
    main()

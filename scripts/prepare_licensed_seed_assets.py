#!/usr/bin/env python3
"""Prepare a reviewable task-only asset replacement plan; never upload or write a database."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, ProxyHandler

ROOT = Path(__file__).resolve().parents[1]
MAPPING_PATH = 'design/icons/lucide/seed-assets-mapping.json'
MAPPING_SHA256 = '474c0a1f860240f9e0af9ed46304617ee54013579d43475aa47dbbe24559f35a'
KEYS = {f'preset-style-{n}' for n in range(1, 5)} | {f'preset-route-{n}' for n in range(1, 4)} | {f'category-{n}' for n in range(1, 6)}
FIELDS = {'sys_category': 'icon', 'preset_theme_style': 'cover_img', 'preset_route': 'preview_img'}
COLUMNS = {'name', 'city', 'del_flag', 'type', 'category_name', 'create_by', 'sort', 'sort_id'}

def canonical_records():
    raw = (ROOT / MAPPING_PATH).read_bytes()
    if hashlib.sha256(raw).hexdigest() != MAPPING_SHA256:
        raise ValueError('The reviewed twelve-record mapping has changed; review it before use')
    return json.loads(raw)['records']

def validate_target(schema, server_uuid, origin):
    if not re.fullmatch(r'cy_e2e_[a-z0-9_]{1,48}', schema or ''):
        raise ValueError('Only an explicitly identified task test schema is supported')
    if not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', server_uuid or ''):
        raise ValueError('An independently verified server UUID is required')
    base = urlsplit(origin)
    if base.scheme != 'http' or base.hostname not in {'127.0.0.1', 'localhost', '::1'} or not base.port or base.path not in {'', '/'} or base.username or base.query or base.fragment:
        raise ValueError('Only the verified loopback test backend origin is supported')

def validate_upload(record, receipt, origin, registry, content):
    response = receipt.get('response', {})
    key, url = response.get('fileName', ''), response.get('url', '')
    if receipt.get('http_status') != 200 or response.get('code') != 200:
        raise ValueError('A successful normal upload receipt is required')
    if not re.fullmatch(r'/profile/upload/\d{4}/\d{2}/\d{2}/[0-9a-f]{32}\.png', key) or url != origin.rstrip('/') + key:
        raise ValueError('Upload URL must match the exact normal local PNG receipt')
    if len(registry) != 1:
        raise ValueError('Approved media registry entry is missing or ambiguous')
    row = registry[0]
    if (row['media_type'], row['review_state'], row['owner_type'], row['storage_type'], row['public_url'], row['object_key']) != ('IMAGE', 'APPROVED', 'SYSTEM', 'LOCAL', url, key):
        raise ValueError('Actual system image approval and URL must match the receipt')
    if not isinstance(row['member_id'], int) or row['member_id'] <= 0:
        raise ValueError('A real system owner is required')
    if hashlib.sha256(content).hexdigest() != record['png']['sha256']:
        raise ValueError('Independent uploaded PNG byte readback does not match the selected icon')
    return url

def prepare(connection, records, receipts, schema, server_uuid, origin, fetch):
    validate_target(schema, server_uuid, origin)
    if len(records) != 12 or {r['mapping_key'] for r in records} != KEYS or len(receipts) != 12 or {r['mapping_key'] for r in receipts} != KEYS:
        raise ValueError('Exactly the twelve reviewed mappings and normal upload receipts are required')
    if records != canonical_records():
        raise ValueError('Natural keys, source ownership and old image fingerprints must match the reviewed mapping')
    by_key = {r['mapping_key']: r for r in receipts}
    plan = []
    with connection.cursor() as cursor:
        cursor.execute('SELECT @@server_uuid AS server_uuid, DATABASE() AS db, VERSION() AS version')
        target = cursor.fetchone()
        if target['server_uuid'] != server_uuid or target['db'] != schema or not target['version'].startswith('8.'):
            raise ValueError('Actual database identity differs from the independently verified target')
        for record in records:
            for name, expected_hash in ((record['png']['path'], record['png']['sha256']), (record['source_file'], record['source_sha256'])):
                path = (ROOT / name).resolve()
                if not path.is_relative_to(ROOT.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
                    raise ValueError('Reviewed asset or original seed source differs from the fixed mapping')
            table, field = record['table'], record['field']
            conditions = {**record['natural_key'], **record['source_marker']}
            if FIELDS.get(table) != field or not conditions or not set(conditions) <= COLUMNS:
                raise ValueError('Mapping table, field or seeded ownership columns are invalid')
            receipt = by_key[record['mapping_key']]
            response = receipt.get('response', {})
            cursor.execute('SELECT member_id,object_key,media_type,review_state,owner_type,storage_type,public_url FROM app_media_asset WHERE object_key=%s', (response.get('fileName'),))
            registry = cursor.fetchall()
            # Validate the origin before making any request; redirects never leave the verified backend.
            url = validate_upload(record, receipt, origin, registry, (ROOT / record['png']['path']).read_bytes())
            validate_upload(record, receipt, origin, registry, fetch(url))
            where = ' AND '.join(f'`{column}`=%s' for column in record['natural_key'])
            cursor.execute(f'SELECT * FROM `{table}` WHERE {where}', tuple(record['natural_key'].values()))
            rows = cursor.fetchall()
            if not rows and record['execution_scope'] == 'TEST_FIXTURE':
                plan.append({'mapping_key': record['mapping_key'], 'state': 'ABSENT_TEST_FIXTURE_NOT_INSERTED'})
                continue
            if len(rows) != 1 or any(rows[0].get(k) != v for k, v in record['source_marker'].items()):
                raise ValueError('Seed record is absent, ambiguous, or owned by another source')
            old = rows[0].get(field)
            if old != url and (not isinstance(old, str) or hashlib.sha256(old.encode()).hexdigest() != record['url_fingerprint']):
                raise ValueError('Custom or changed image must be preserved')
            guarded = ' AND '.join(f'`{column}`=%s' for column in conditions)
            plan.append({'mapping_key': record['mapping_key'], 'state': 'ALREADY_REPLACED' if old == url else 'READY_FOR_REVIEW', 'table': table, 'field': field,
                         'sql': f'UPDATE `{table}` SET `{field}`=%s WHERE {guarded} AND `{field}`=%s',
                         'parameters': [url, *conditions.values(), old], 'expected_changed_rows': 0 if old == url else 1})
    return {'schema': schema, 'server_uuid': server_uuid, 'task_only': True, 'database_writes': False, 'requires_atomic_apply_and_independent_readback': True, 'records': plan}

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ValueError('Asset readback redirects are not accepted')

def loopback_opener():
    return build_opener(ProxyHandler({}), NoRedirect)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipts', type=Path, required=True)
    parser.add_argument('--schema', required=True)
    parser.add_argument('--server-uuid', required=True)
    parser.add_argument('--origin', required=True)
    parser.add_argument('--db-port', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    validate_target(args.schema, args.server_uuid, args.origin)
    if sys.platform != 'linux' or not 1 <= args.db_port <= 65535:
        raise ValueError('Database preparation runs on the approved test VPS only')
    import pymysql
    connection = pymysql.connect(host='127.0.0.1', port=args.db_port, user=os.environ['CY_ASSET_DB_USER'], password=os.environ['CY_ASSET_DB_PASSWORD'], database=args.schema, cursorclass=pymysql.cursors.DictCursor)
    opener = loopback_opener()
    def fetch(url):
        with opener.open(url, timeout=20) as response:
            data = response.read(1024 * 1024 + 1)
            if response.status != 200 or len(data) > 1024 * 1024:
                raise ValueError('Independent PNG readback failed or exceeded its size limit')
            return data
    try:
        with connection.cursor() as cursor:
            cursor.execute('SET TRANSACTION READ ONLY')
        connection.begin()
        receipts = json.loads(args.receipts.read_text())
        result = prepare(connection, canonical_records(), receipts['uploads'], args.schema, args.server_uuid, args.origin, fetch)
    finally:
        connection.rollback()
        connection.close()
    # Exclusive creation preserves earlier evidence and never writes partial validation results.
    with args.output.open('x', encoding='utf-8') as output:
        os.chmod(args.output, 0o600)
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write('\n')
    print('Verified task-only replacement plan saved; no upload or database update performed')

if __name__ == '__main__':
    main()

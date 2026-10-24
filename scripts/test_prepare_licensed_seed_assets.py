#!/usr/bin/env python3
"""Network-free negative controls for the read-only asset preparation boundary."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import prepare_licensed_seed_assets as assets
from urllib.request import ProxyHandler
from prepare_licensed_seed_assets import validate_target, validate_upload

ORIGIN = 'http://127.0.0.1:18080'
KEY = '/profile/upload/2026/10/13/' + 'a' * 32 + '.png'
CONTENT = b'reviewed PNG bytes'
RECORD = {'png': {'sha256': hashlib.sha256(CONTENT).hexdigest()}}
RECEIPT = {'http_status': 200, 'response': {'code': 200, 'fileName': KEY, 'url': ORIGIN + KEY}}
ROW = {'member_id': 1, 'object_key': KEY, 'media_type': 'IMAGE', 'review_state': 'APPROVED', 'owner_type': 'SYSTEM', 'storage_type': 'LOCAL', 'public_url': ORIGIN + KEY}

class BoundaryTests(unittest.TestCase):
    def test_actual_normal_approved_receipt_matches_exact_bytes(self):
        self.assertEqual(validate_upload(RECORD, RECEIPT, ORIGIN, [ROW], CONTENT), ORIGIN + KEY)

    def test_transport_or_business_failure_has_no_approved_receipt(self):
        for field, value in [('http_status', 503), ('code', 503)]:
            receipt = copy.deepcopy(RECEIPT)
            (receipt if field == 'http_status' else receipt['response'])[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_upload(RECORD, receipt, ORIGIN, [ROW], CONTENT)

    def test_unknown_external_malformed_or_svg_url_rejected_before_fetch(self):
        for key, url in [(KEY, 'https://unapproved.invalid' + KEY), (KEY, ORIGIN + KEY + '?other=1'), (KEY.replace('.png', '.svg'), ORIGIN + KEY.replace('.png', '.svg')), (KEY, ORIGIN + '/profile/../' + KEY)]:
            receipt = copy.deepcopy(RECEIPT)
            receipt['response'].update(fileName=key, url=url)
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_upload(RECORD, receipt, ORIGIN, [ROW], CONTENT)

    def test_missing_duplicate_or_unapproved_registry_fails(self):
        for rows in [[], [ROW, ROW]]:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                validate_upload(RECORD, RECEIPT, ORIGIN, rows, CONTENT)
        for key, value in [('review_state', 'HOLD'), ('owner_type', 'APP'), ('storage_type', 'OSS'), ('member_id', 0), ('public_url', ORIGIN + '/other.png'), ('object_key', '/other.png')]:
            row = {**ROW, key: value}
            with self.subTest(field=key), self.assertRaises(ValueError):
                validate_upload(RECORD, RECEIPT, ORIGIN, [row], CONTENT)

    def test_png_readback_mismatch_fails(self):
        with self.assertRaises(ValueError):
            validate_upload(RECORD, RECEIPT, ORIGIN, [ROW], b'changed')

    def test_verified_task_loopback_target(self):
        validate_target('cy_e2e_hengyin_public_test', '1ab44c5a-c62f-11f1-abef-76aa6fafa8e2', ORIGIN)

    def test_non_task_database_external_origin_and_invalid_uuid_fail(self):
        valid = ['cy_e2e_hengyin_public_test', '1ab44c5a-c62f-11f1-abef-76aa6fafa8e2', ORIGIN]
        for index, value in [(0, 'chengyinhubdb'), (0, 'cy_e2e_x;drop'), (1, 'unknown'), (2, 'https://production.invalid'), (2, 'http://127.0.0.1:18080/path'), (2, 'http://user@127.0.0.1:18080'), (2, ORIGIN + '?target=x')]:
            args = valid.copy()
            args[index] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_target(*args)

class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.records = copy.deepcopy(assets.canonical_records())
        self.receipts, self.seed_rows, self.content_by_url = [], {}, {}
        paths = {assets.MAPPING_PATH} | {r['png']['path'] for r in self.records} | {r['source_file'] for r in self.records}
        for relative in paths:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(assets.ROOT / relative, destination)
        for index, record in enumerate(self.records):
            key, field = record['mapping_key'], record['field']
            key_path = KEY.replace('a' * 32, f'{index:032x}')
            self.receipts.append({'mapping_key': key, 'http_status': 200, 'response': {'code': 200, 'fileName': key_path, 'url': ORIGIN + key_path}})
            self.content_by_url[ORIGIN + key_path] = (self.root / record['png']['path']).read_bytes()
            legacy = 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/' + ('map.png' if key.startswith('preset') else 'c1.png')
            self.seed_rows[key] = [{**record['natural_key'], **record['source_marker'], field: legacy, 'route_geometry': 'unchanged'}]
        self.statements = []

    def tearDown(self):
        self.directory.cleanup()

    def run_prepare(self):
        test = self
        class Cursor:
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def execute(self, sql, parameters=()):
                test.assertTrue(sql.startswith('SELECT '), 'Preparation must not write or authorize a media asset')
                test.statements.append(sql)
                self.sql, self.parameters = sql, parameters
            def fetchone(self):
                return {'server_uuid': '1ab44c5a-c62f-11f1-abef-76aa6fafa8e2', 'db': 'cy_e2e_hengyin_public_test', 'version': '8.0.45'}
            def fetchall(self):
                if 'FROM app_media_asset' in self.sql:
                    return [{**ROW, 'object_key': self.parameters[0], 'public_url': ORIGIN + self.parameters[0]}]
                matches = [r['mapping_key'] for r in test.records if f"FROM `{r['table']}`" in self.sql and tuple(r['natural_key'].values()) == self.parameters]
                test.assertEqual(len(matches), 1)
                return test.seed_rows[matches[0]]
        class Connection:
            def cursor(self): return Cursor()
        with patch.object(assets, 'ROOT', self.root):
            return assets.prepare(Connection(), self.records, self.receipts, 'cy_e2e_hengyin_public_test', '1ab44c5a-c62f-11f1-abef-76aa6fafa8e2', ORIGIN, lambda url: test.content_by_url[url])

    def test_full_twelve_readbacks_prepare_only_exact_guarded_changes(self):
        before = copy.deepcopy(self.seed_rows)
        plan = self.run_prepare()
        self.assertFalse(plan['database_writes'])
        self.assertEqual(len(plan['records']), 12)
        self.assertTrue(all(row['state'] == 'READY_FOR_REVIEW' for row in plan['records']))
        self.assertTrue(all('`create_by`=%s' in row['sql'] and row['sql'].count('UPDATE ') == 1 for row in plan['records']))
        self.assertEqual(self.seed_rows, before)

    def test_missing_fixture_not_inserted_and_already_replaced_idempotent(self):
        self.seed_rows['preset-route-1'] = []
        self.seed_rows['category-1'][0]['icon'] = next(r['response']['url'] for r in self.receipts if r['mapping_key'] == 'category-1')
        states = {r['mapping_key']: r['state'] for r in self.run_prepare()['records']}
        self.assertEqual(states['preset-route-1'], 'ABSENT_TEST_FIXTURE_NOT_INSERTED')
        self.assertEqual(states['category-1'], 'ALREADY_REPLACED')

    def test_non_target_custom_source_missing_and_duplicate_rows_abort_whole_plan(self):
        original = copy.deepcopy(self.seed_rows)
        for changed in [[], [original['category-1'][0]] * 2, [{**original['category-1'][0], 'icon': 'custom-image'}], [{**original['category-1'][0], 'create_by': 'another-owner'}]]:
            self.seed_rows = copy.deepcopy(original)
            self.seed_rows['category-1'] = changed
            with self.subTest(row=changed), self.assertRaises(ValueError):
                self.run_prepare()

    def test_changed_original_source_or_png_aborts(self):
        for file in [self.records[0]['source_file'], self.records[0]['png']['path']]:
            path = self.root / file
            old = path.read_bytes()
            path.write_bytes(b'changed')
            with self.subTest(file=file), self.assertRaises(ValueError):
                self.run_prepare()
            path.write_bytes(old)

    def test_incomplete_or_duplicate_receipts_abort(self):
        self.receipts[-1] = self.receipts[0]
        with self.assertRaises(ValueError):
            self.run_prepare()

    def test_changed_natural_key_marker_or_custom_url_fingerprint_cannot_change_target(self):
        original = copy.deepcopy(self.records)
        for field, changed in [('natural_key', original[-1]['natural_key']), ('source_marker', {'create_by': 'another-owner'}), ('url_fingerprint', hashlib.sha256(b'custom-image').hexdigest())]:
            self.records = copy.deepcopy(original)
            self.records[0][field] = changed
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.run_prepare()
        self.assertEqual(self.statements, [], 'Changed mappings must fail before any database query')

    def test_changed_fixed_mapping_file_is_rejected(self):
        with (self.root / assets.MAPPING_PATH).open('a') as output:
            output.write(' ')
        with self.assertRaises(ValueError):
            self.run_prepare()

    def test_loopback_readback_does_not_inherit_proxy_or_follow_redirects(self):
        with patch.dict(os.environ, {'http_proxy': 'http://unapproved.invalid:3128', 'https_proxy': 'http://unapproved.invalid:3128', 'no_proxy': ''}):
            opener = assets.loopback_opener()
        self.assertFalse(any(isinstance(handler, ProxyHandler) and handler.proxies for handler in opener.handlers))
        redirect = next(handler for handler in opener.handlers if isinstance(handler, assets.NoRedirect))
        with self.assertRaises(ValueError):
            redirect.redirect_request(None, None, 302, '', {}, 'https://unapproved.invalid')

if __name__ == '__main__':
    unittest.main()

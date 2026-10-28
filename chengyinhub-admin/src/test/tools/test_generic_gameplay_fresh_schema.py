import importlib.util
import copy
from contextlib import contextmanager
import hashlib
import json
import pathlib
import re
import sqlite3
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fresh_schema', pathlib.Path(__file__).with_name('generic-gameplay-fresh-schema.py'))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class FreshSchemaTest(unittest.TestCase):
    def plan(self, sql, **kwargs):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / 'fixture.sql'
            path.write_text(sql)
            with patch.object(tool, 'ROOT', root):
                return tool.plan_file(path, **kwargs)

    def test_runner_assert_skipped_but_ddl_is_preserved(self):
        plan = self.plan("-- runner: requires=transaction\nCREATE TABLE fixture(id INT);\n-- runner: assert=scalar-true\nSELECT COUNT(*)>0 FROM fixture;\nALTER TABLE fixture ADD name VARCHAR(10);", migration=True)
        self.assertEqual(2, len(plan['apply']))
        self.assertEqual(1, len(plan['skipped']))
        self.assertTrue(plan['transaction'])
        self.assertIn('ALTER TABLE', plan['apply'][1])

    def test_specific_production_cleanup_and_temporary_guards_are_skipped(self):
        plan = self.plan('CREATE TEMPORARY TABLE guard(id INT); DELETE FROM cms_member WHERE id=100096; INSERT INTO guard VALUES(1);', migration=True)
        self.assertEqual([], plan['apply'])
        self.assertEqual(3, len(plan['skipped']))

    def test_mixed_cleanup_keeps_persistent_ddl_without_production_dml(self):
        plan = self.plan('DELETE FROM cms_topic WHERE id=100096; ALTER TABLE cms_topic ADD archive_flag INT;', migration=True)
        self.assertEqual(['ALTER TABLE cms_topic ADD archive_flag INT'], plan['apply'])
        self.assertEqual(1, len(plan['skipped']))

    def test_prepared_ddl_and_executable_comment_ddl_are_preserved(self):
        plan = self.plan("SET @ddl='ALTER TABLE fixture ADD column1 INT'; PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;", migration=True)
        self.assertEqual(4, len(plan['apply']))
        self.assertEqual([], plan['skipped'])
        plan = self.plan('/*!50001 CREATE TABLE fixture(id INT) */;', migration=True)
        self.assertEqual(1, len(plan['apply']))

    def test_base_seed_is_not_classified_as_production_cleanup(self):
        plan = self.plan("INSERT INTO sys_config VALUES('value;still one');")
        self.assertEqual(1, len(plan['apply']))

    def test_baseline_assertion_is_applied_not_skipped(self):
        plan = self.plan('-- runner: assert=scalar-true\nSELECT 1;')
        self.assertEqual(['SELECT 1'], plan['apply'])
        self.assertEqual(['scalar-true'], plan['assertions'])
        self.assertEqual([], plan['skipped'])

    def test_false_baseline_assertion_fails_execution(self):
        args = SimpleNamespace(host='127.0.0.1', database='cy_e2e_fixture', port=3316, user='root')
        for output in ['0', '1\n0', '']:
            plan = {'files': [{'path': 'base_seed.sql', 'transaction': False,
                              'apply': ['SELECT 0'], 'assertions': ['scalar-true']}]}
            with patch.object(tool.shutil, 'which', return_value='/usr/bin/mysql'), patch.object(tool.subprocess, 'run') as run:
                run.side_effect = [SimpleNamespace(stdout=value) for value in ['8.0.45', '', '', output]]
                with self.assertRaisesRegex(ValueError, 'Baseline assertion failed'):
                    tool.execute(args, plan)

    def test_unknown_annotation_invalid_sql_and_misclassified_ddl_fail_closed(self):
        for sql in ["-- runner: assert=unknown\nSELECT 1;", "SELECT 'unclosed;"]:
            with self.assertRaises(ValueError): self.plan(sql)
        with self.assertRaises(ValueError):
            self.plan('CREATE TABLE fixture(id INT);', migration=True, data_only=True)

    def test_remote_host_real_schema_and_external_sql_are_refused(self):
        for host, database in [('example.invalid', 'cy_e2e_fixture'), ('127.0.0.1', 'chengyinhubdb')]:
            with self.assertRaises(ValueError):
                tool.mysql_command(SimpleNamespace(host=host, database=database, port=3316, user='root'))
        for sql in ["SELECT LOAD_FILE('/etc/passwd');", 'USE unrelated;', 'CREATE DATABASE unrelated;']:
            with self.assertRaises(ValueError): self.plan(sql)

    def test_complete_repository_dry_run_keeps_new_runtime_ddl(self):
        plan = tool.build_plan()
        self.assertEqual('DRY_RUN', plan['mode'])
        self.assertGreater(plan['appliedStatements'], 0)
        self.assertGreater(plan['skippedStatements'], 0)
        frozen = next(f for f in plan['files'] if f['path'].endswith('migration_theme_runtime_20261004.sql'))
        self.assertTrue(any('CREATE TABLE' in sql for sql in frozen['apply']))
        referral = next(f for f in plan['files'] if f['path'].endswith('migration_20260815_tandianri_roam_referral.sql'))
        self.assertTrue(any('ADD COLUMN roam_referral_enabled' in sql for sql in referral['apply']))
        self.assertNotIn(referral['path'], plan['baselineIncluded'])

    def test_execution_refuses_existing_schema_and_wrong_mysql_before_create(self):
        args = SimpleNamespace(host='127.0.0.1', database='cy_e2e_fixture', port=3316, user='root')
        for outputs in [['9.7.1'], ['8.0.45', 'cy_e2e_fixture']]:
            with patch.object(tool.shutil, 'which', return_value='/usr/bin/mysql'), patch.object(tool.subprocess, 'run') as run:
                run.side_effect = [SimpleNamespace(stdout=output) for output in outputs]
                with self.assertRaises(ValueError): tool.execute(args, {'files': []})
                self.assertTrue(all('CREATE DATABASE' not in call.kwargs['input'] for call in run.call_args_list))

    def test_execution_keeps_same_connection_for_transaction_and_routine_delimiters(self):
        args = SimpleNamespace(host='127.0.0.1', database='cy_e2e_fixture', port=3316, user='root')
        plan = {'files': [{'path':'fixture.sql','transaction':True,'apply':['CREATE TABLE fixture(id INT)']}]}
        with patch.object(tool.shutil, 'which', return_value='/usr/bin/mysql'), patch.object(tool.subprocess, 'run') as run:
            run.side_effect = [SimpleNamespace(stdout=output) for output in ['8.0.45', '', '', '']]
            tool.execute(args, plan)
            payload = run.call_args_list[-1].kwargs['input']
            self.assertIn('COLLATE utf8mb4_general_ci', run.call_args_list[2].kwargs['input'])
            self.assertIn('START TRANSACTION$$', payload)
            self.assertIn('COMMIT$$', payload)
            self.assertEqual('SUCCEEDED', plan['mode'])


class MigrationGuardTest(unittest.TestCase):
    def test_existing_a8_file_is_byte_identical_to_master(self):
        path = 'chengyinhub-admin/sql/migration_seed_merchant_game_a8_20260823.sql'
        self.assertEqual(subprocess.check_output(['git', 'show', 'master:' + path], cwd=tool.ROOT), (tool.ROOT / path).read_bytes())

    def test_v51_forward_preasserts_before_update_and_is_empty_only_transactional(self):
        from ci.run_migration import _REQUIRED_TRANSACTION_DECLARATION
        sql = (tool.ROOT / 'chengyinhub-admin/sql/migration_20261012_legacy_game_v51_category_collation.sql').read_text()
        self.assertTrue(_REQUIRED_TRANSACTION_DECLARATION.search(sql))
        self.assertLess(sql.index('HAVING COUNT(c.id)<>1'), sql.index('UPDATE cms_template_library'))
        self.assertLess(sql.index('MIN(l.create_by)'), sql.index('UPDATE cms_template_library'))
        self.assertIn("COALESCE(TRIM(l.activity_categoryids),'')=''", sql)
        self.assertIn("l.create_by='seed_merchant_game_v51_20260827'", sql)
        self.assertEqual(2, sql.count('CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci'))

    def test_historical_v51_original_bytes_are_frozen_and_planner_rejects_drift(self):
        from scripts.e2e import build_schema
        manifest = build_schema.build_manifest(tool.ROOT, target_commit='a' * 40,
            dependency_path=tool.ROOT / 'ci/migration-dependencies.json')
        historical = tool.ROOT / 'chengyinhub-admin/sql/migration_20261007_legacy_game_v51_categories.sql'
        original = historical.read_bytes()
        self.assertEqual('8a49f3ef24a3e654210e8bc974224292910096d220c401be26135b81c6405643',
                         hashlib.sha256(original).hexdigest())
        read_bytes = pathlib.Path.read_bytes
        def read_with_drift(path):
            return original + b'\n-- changed historical bytes\n' if path == historical else read_bytes(path)
        with patch.object(pathlib.Path, 'read_bytes', read_with_drift):
            with self.assertRaisesRegex(RuntimeError, 'data-only 指纹不一致'):
                build_schema.migrations_after_baseline(manifest['migrations'])

    def test_new_cdn_guard_absent_valid_replay_and_invalid_rows(self):
        # The new migration is a portable, read-only SELECT. Run its actual SQL
        # against in-memory fixtures; this does not claim MySQL integration.
        sql = (tool.ROOT / 'chengyinhub-admin/sql/migration_20261009_merchant_game_a8_cdn_guard.sql').read_text()
        statement = tool.executable_statement(tool.split_statements(sql)[0])
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE cms_template_library(member_id INT, del_flag INT, create_by TEXT, title TEXT, img_url TEXT, story_img TEXT)')
            self.assertEqual(1, db.execute(statement).fetchone()[0])
            titles = ['招牌菜限时盲猜','咖啡风味人格','隐藏菜单线索链','60秒健身知识反应赛','节奏盲猜夜','分支故事接力','污渍侦探','家居安全排雷']
            for index, title in enumerate(titles):
                url = 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/%s.jpg' % index
                db.execute('INSERT INTO cms_template_library VALUES(100000,0,?,?,?,?)', ('seed_merchant_game_a8_20260823',title,url,url))
            before = list(db.execute('SELECT * FROM cms_template_library'))
            for _ in range(2): self.assertEqual(1, db.execute(statement).fetchone()[0])
            self.assertEqual(before, list(db.execute('SELECT * FROM cms_template_library')))
            for mutation in ["UPDATE cms_template_library SET img_url='/images/merchant-games/old.jpg' WHERE rowid=1", "UPDATE cms_template_library SET create_by='different' WHERE rowid=1", 'DELETE FROM cms_template_library WHERE rowid=1', 'INSERT INTO cms_template_library SELECT * FROM cms_template_library WHERE rowid=1']:
                db.execute('SAVEPOINT invalid')
                db.execute(mutation)
                self.assertEqual(0, db.execute(statement).fetchone()[0])
                db.execute('ROLLBACK TO invalid')

    def test_supersession_only_after_replacement_is_recorded(self):
        from ci.audit_migrations_applied import read_supersessions, apply_assertion_supersessions
        old = 'migration_seed_merchant_game_a8_20260823.sql'
        new = 'migration_20261009_merchant_game_a8_cdn_guard.sql'
        mapping = {old: read_supersessions()[old]}
        self.assertEqual(new, mapping[old])
        self.assertEqual([(old, 'false')], apply_assertion_supersessions([(old,'false')], [], mapping, {old,new})[0])
        self.assertEqual([], apply_assertion_supersessions([(old,'false')], [new], mapping, {old,new})[0])




class NoticeForwardCallerTest(unittest.TestCase):
    """Caller/transport controls only; migration assertions remain explicit HTTP-fixture skips."""

    @classmethod
    def setUpClass(cls):
        cls.original_plan = tool.build_plan()
        cls.manifest = tool.build_manifest(tool.ROOT,
            target_commit=cls.original_plan['manifest']['targetCommit'],
            dependency_path=tool.ROOT / 'ci/migration-dependencies.json')

    def fresh_plan(self):
        return copy.deepcopy(self.original_plan)

    def args(self):
        return SimpleNamespace(host='127.0.0.1', database='cy_e2e_notice_caller', port=3316, user='root')

    def outputs(self, plan, failed_path=None):
        result = [SimpleNamespace(stdout=value) for value in ('8.0.45', '', '')]
        for file in plan['files']:
            if file['apply']:
                if file['path'] == failed_path:
                    result.append(ValueError('controlled fixture apply failure'))
                    break
                result.append(SimpleNamespace(stdout='\n'.join(['1'] * len(file['assertions']))))
        return result

    @contextmanager
    def transport(self, outputs):
        with patch.object(tool, 'build_plan', return_value=self.fresh_plan()), \
                patch.object(tool.shutil, 'which', return_value='/usr/bin/mysql'), \
                patch.object(tool.subprocess, 'run', side_effect=outputs) as run, \
                patch('builtins.print') as printed:
            yield run, printed

    def test_exact_pair_preserves_all_other_files_fingerprints_and_skip_records(self):
        plan = self.fresh_plan()
        old_path = tool.NOTICE_FORWARD_FILES[tool.NOTICE_OLD][0]
        v2_path = tool.NOTICE_FORWARD_FILES[tool.NOTICE_V2][0]
        baseline = json.loads((tool.ROOT / 'scripts/e2e/base_schema_20260818_migrations.json').read_text())
        policy = json.loads((tool.ROOT / 'scripts/e2e/schema_replay_policy_20260921.json').read_text())
        expected = [baseline['baseSchema']['path'], policy['baselineSeed']['path']]
        expected += [row['path'] for row in self.manifest['migrations']
                     if row['path'] not in plan['baselineIncluded'] and row['path'] not in {old_path, tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_OLD][0]}]
        self.assertEqual(expected, [file['path'] for file in plan['files']])
        self.assertIn(v2_path, expected)
        self.assertNotIn(old_path, plan['baselineIncluded'])
        self.assertNotIn(old_path, policy['dataOnlyMigrations'])
        self.assertEqual(len(self.manifest['migrations']), plan['manifest']['migrationCount'])
        self.assertEqual('PLANNED_UNAPPLIED', plan['noticeForward']['state'])
        for index, file in enumerate(plan['files']):
            self.assertEqual(tool.plan_file(tool.ROOT / file['path'], index >= 2,
                file['path'] in policy['dataOnlyMigrations']), file)
        self.assertEqual(sum(len(f['apply']) for f in plan['files']), plan['appliedStatements'])
        self.assertEqual(sum(len(f['skipped']) for f in plan['files']), plan['skippedStatements'])

    def test_partial_pair_manifest_hash_and_order_drift_rejected_before_mysql(self):
        for key in tool.NOTICE_FORWARD_FILES:
            for mutation in ('missing', 'hash', 'dependency', 'order'):
                with self.subTest(migration=key, mutation=mutation):
                    manifest = copy.deepcopy(self.manifest)
                    rows = manifest['migrations']
                    entry = next(row for row in rows if row['migrationId'] == key)
                    if mutation == 'missing':
                        rows.remove(entry)
                    elif mutation == 'hash':
                        entry['sha256'] = '0' * 64
                    elif mutation == 'dependency':
                        entry['dependsOn'] = []
                    else:
                        entry['order'] += 1
                    with patch.object(tool, 'build_manifest', return_value=manifest), \
                            patch.object(tool, 'mysql_command') as command:
                        with self.assertRaisesRegex(RuntimeError, 'notice forward'):
                            tool.build_plan()
                        command.assert_not_called()

    def test_any_pair_file_byte_drift_rejected_by_shared_contract(self):
        read_bytes = pathlib.Path.read_bytes
        for path, _ in tool.NOTICE_FORWARD_FILES.values():
            with self.subTest(path=path):
                target = tool.ROOT / path
                def drift(file):
                    value = read_bytes(file)
                    return value + b'\n' if file == target else value
                with patch.object(pathlib.Path, 'read_bytes', drift), \
                        patch.object(tool, 'mysql_command') as command:
                    with self.assertRaisesRegex(RuntimeError, 'notice forward'):
                        tool.build_plan()
                    command.assert_not_called()

    def test_execution_plan_drift_cannot_reinsert_old_or_edit_apply_skip_receipts(self):
        for mutation in ('missing-record', 'old-file', 'apply', 'skip', 'hash', 'count', 'baseline', 'mode', 'completed'):
            with self.subTest(mutation=mutation):
                plan = self.fresh_plan()
                v2_path = tool.NOTICE_FORWARD_FILES[tool.NOTICE_V2][0]
                v2 = next(file for file in plan['files'] if file['path'] == v2_path)
                if mutation == 'missing-record':
                    del plan['noticeForward']
                elif mutation == 'old-file':
                    plan['files'].append(tool.plan_file(
                        tool.ROOT / tool.NOTICE_FORWARD_FILES[tool.NOTICE_OLD][0], True))
                elif mutation == 'apply':
                    v2['apply'][0] = 'SELECT 1'
                elif mutation == 'skip':
                    v2['skipped'][0]['sql'] = 'SELECT 1'
                elif mutation == 'hash':
                    plan['noticeForward']['fingerprints'][tool.NOTICE_V2]['sha256'] = '0' * 64
                elif mutation == 'count':
                    plan['skippedStatements'] += 1
                elif mutation == 'mode':
                    plan['mode'] = 'SUCCEEDED'
                elif mutation == 'completed':
                    plan['completed'] = [tool.NOTICE_FORWARD_FILES[tool.NOTICE_OLD][0]]
                else:
                    plan['baselineIncluded'].append(tool.NOTICE_FORWARD_FILES[tool.NOTICE_OLD][0])
                with patch.object(tool, 'build_plan', return_value=self.fresh_plan()), \
                        patch.object(tool, 'mysql_command') as command:
                    with self.assertRaisesRegex(ValueError, 'Notice forward execution plan drift'):
                        tool.execute(self.args(), plan)
                    command.assert_not_called()

    def test_successful_transport_completes_only_selected_files_without_claiming_strict_migration(self):
        plan = self.fresh_plan()
        original_skips = [copy.deepcopy(file['skipped']) for file in plan['files']]
        with self.transport(self.outputs(plan)) as (run, printed):
            tool.execute(self.args(), plan)
        self.assertEqual('SUCCEEDED', plan['mode'])
        self.assertEqual([file['path'] for file in plan['files']], plan['completed'])
        self.assertNotIn(tool.NOTICE_FORWARD_FILES[tool.NOTICE_OLD][0], plan['completed'])
        self.assertEqual(original_skips, [file['skipped'] for file in plan['files']])
        self.assertEqual('REPLACED_UNAPPLIED', plan['noticeForward']['state'])
        records = [str(call) for call in printed.call_args_list
                   if 'REPLACED_UNAPPLIED' in str(call) and tool.NOTICE_OLD in str(call)]
        broadcast_records = [str(call) for call in printed.call_args_list
                             if 'REPLACED_UNAPPLIED' in str(call) and tool.BROADCAST_OLD in str(call)]
        self.assertEqual(1, len(broadcast_records))
        self.assertIn(tool.BROADCAST_UNION, broadcast_records[0])
        self.assertEqual(1, len(records))
        self.assertIn(tool.NOTICE_OLD, records[0])
        self.assertIn(tool.NOTICE_V2, records[0])
        for metadata in plan['noticeForward']['fingerprints'].values():
            self.assertIn(metadata['sha256'], records[0])
        payloads = [call.kwargs['input'] for call in run.call_args_list]
        self.assertTrue(payloads[2].startswith('CREATE DATABASE '))
        self.assertNotIn('IF NOT EXISTS', payloads[2])
        self.assertFalse(any('DROP DATABASE' in sql for sql in payloads))
        expected_sql = []
        for file in plan['files']:
            if file['apply']:
                sqls = file['apply']
                if file['transaction']:
                    sqls = ['START TRANSACTION'] + sqls + ['COMMIT']
                expected_sql.append('DELIMITER $$\n' + '\n'.join(sql + '$$' for sql in sqls) + '\nDELIMITER ;\n')
        self.assertEqual(expected_sql, payloads[3:])

    def test_existing_schema_refused_without_create_import_or_replacement_receipt(self):
        plan = self.fresh_plan()
        with self.transport([SimpleNamespace(stdout='8.0.45'), SimpleNamespace(stdout=self.args().database)]) as (run, printed):
            with self.assertRaisesRegex(ValueError, 'Existing schema refused'):
                tool.execute(self.args(), plan)
        self.assertEqual(2, run.call_count)
        self.assertEqual('PLANNED_UNAPPLIED', plan['noticeForward']['state'])
        self.assertEqual([], plan.get('completed', []))
        self.assertFalse(any('REPLACED_UNAPPLIED' in str(call) for call in printed.call_args_list))
        self.assertFalse(any('CREATE DATABASE' in call.kwargs['input'] or 'DROP DATABASE' in call.kwargs['input']
                             for call in run.call_args_list))

    def test_concurrent_create_failure_is_never_adopted_or_marked_completed(self):
        plan = self.fresh_plan()
        failure = subprocess.CalledProcessError(1, ['mysql'], stderr='ERROR 1007 (HY000): controlled CREATE race')
        with self.transport([SimpleNamespace(stdout='8.0.45'), SimpleNamespace(stdout=''), failure]) as (run, printed):
            with self.assertRaises(subprocess.CalledProcessError) as rejected:
                tool.execute(self.args(), plan)
        self.assertIs(failure, rejected.exception)
        self.assertEqual(3, run.call_count)
        self.assertEqual('schema-create', plan['currentPhase'])
        self.assertEqual([], plan.get('completed', []))
        self.assertEqual('PLANNED_UNAPPLIED', plan['noticeForward']['state'])
        self.assertNotIn('IF NOT EXISTS', run.call_args.kwargs['input'])
        self.assertFalse(any('DROP DATABASE' in call.kwargs['input'] for call in run.call_args_list))
        self.assertFalse(any('REPLACED_UNAPPLIED' in str(call) for call in printed.call_args_list))

    def test_apply_failure_preserves_skips_and_does_not_complete_old_or_failed_file(self):
        plan = self.fresh_plan()
        original_skips = [copy.deepcopy(file['skipped']) for file in plan['files']]
        failed_path = tool.NOTICE_FORWARD_FILES[tool.NOTICE_V2][0]
        index = next(i for i, file in enumerate(plan['files']) if file['path'] == failed_path)
        with self.transport(self.outputs(plan, failed_path)):
            with self.assertRaisesRegex(ValueError, 'controlled fixture apply failure'):
                tool.execute(self.args(), plan)
        self.assertEqual([file['path'] for file in plan['files'][:index]], plan['completed'])
        self.assertNotIn(tool.NOTICE_FORWARD_FILES[tool.NOTICE_OLD][0], plan['completed'])
        self.assertNotIn(failed_path, plan['completed'])
        self.assertEqual(failed_path, plan['currentFile'])
        self.assertEqual(original_skips, [file['skipped'] for file in plan['files']])

    def test_canonical_custody_checks_baseline_and_data_only_bytes(self):
        policy = json.loads((tool.ROOT / 'scripts/e2e/schema_replay_policy_20260921.json').read_text())
        baseline = json.loads((tool.ROOT / 'scripts/e2e/base_schema_20260818_migrations.json').read_text())
        read_bytes = pathlib.Path.read_bytes
        for path in (baseline['baseSchema']['path'], next(iter(policy['dataOnlyMigrations']))):
            with self.subTest(path=path):
                target = tool.ROOT / path
                def drift(file):
                    value = read_bytes(file)
                    return value + b'\n' if file == target else value
                with patch.object(pathlib.Path, 'read_bytes', drift):
                    with self.assertRaises((ValueError, RuntimeError)):
                        tool.build_plan()


class BroadcastForwardCallerTest(unittest.TestCase):
    """SOURCE controls: exact broadcast composition, not MySQL or production migration evidence."""

    @classmethod
    def setUpClass(cls):
        cls.plan = tool.build_plan()
        cls.manifest = tool.build_manifest(tool.ROOT, target_commit=cls.plan['manifest']['targetCommit'],
            dependency_path=tool.ROOT / 'ci/migration-dependencies.json')

    def test_union_keeps_both_originals_and_all_runtime_fields_without_rewriting_evidence(self):
        plan = copy.deepcopy(self.plan)
        d_path = tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_D][0]
        old_path = tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_OLD][0]
        union_path = tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_UNION][0]
        paths = [file['path'] for file in plan['files']]
        self.assertIn(d_path, paths)
        self.assertNotIn(old_path, paths)
        self.assertIn(union_path, paths)
        self.assertLess(paths.index(d_path), paths.index(union_path))
        for key, (path, digest) in tool.BROADCAST_FORWARD_FILES.items():
            self.assertEqual(digest, hashlib.sha256((tool.ROOT / path).read_bytes()).hexdigest())
            self.assertNotIn(path, plan['baselineIncluded'])
            self.assertEqual(1, sum(row['migrationId'] == key for row in self.manifest['migrations']))
        union = next(file for file in plan['files'] if file['path'] == union_path)
        sql = '\n'.join(union['apply'])
        self.assertIn("MODIFY COLUMN delivery_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'", sql)
        self.assertNotIn('ADD COLUMN message_id', sql)
        for field in ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time',
                      'audience_frozen', 'client_request_id', 'request_owner', 'request_hash'):
            self.assertIn('ADD COLUMN ' + field, sql)
        self.assertIn('CREATE TABLE official_broadcast_creation', sql)
        self.assertFalse(any(re.match(r'(?i)\s*(UPDATE|DELETE|REPLACE)\b', statement)
                             for statement in union['apply']))
        self.assertEqual('PLANNED_UNAPPLIED', plan['broadcastForward']['state'])

    def test_partial_drift_or_reordered_pair_cannot_be_selected(self):
        original = self.manifest['migrations']
        pending = tool.migrations_after_baseline(original)
        for key in tool.BROADCAST_FORWARD_FILES:
            for field, value in (('sha256', '0' * 64), ('dependsOn', []), ('order', -1)):
                with self.subTest(key=key, field=field):
                    rows = copy.deepcopy(original)
                    next(row for row in rows if row['migrationId'] == key)[field] = value
                    with self.assertRaisesRegex(RuntimeError, 'broadcast forward'):
                        tool.broadcast_forward_plan(rows, pending)
            with self.assertRaisesRegex(RuntimeError, 'broadcast forward'):
                tool.broadcast_forward_plan(original, [row for row in pending if row['migrationId'] != key])
        rows = copy.deepcopy(original)
        a, b = [next(i for i, row in enumerate(rows) if row['migrationId'] == key)
                for key in (tool.BROADCAST_D, tool.BROADCAST_OLD)]
        rows[a], rows[b] = rows[b], rows[a]
        with self.assertRaisesRegex(RuntimeError, 'broadcast forward'):
            tool.broadcast_forward_plan(rows, pending)

    def test_execution_cannot_inject_old_sql_or_omit_replacement_custody(self):
        for mutation in ('old', 'missing', 'hash', 'apply'):
            with self.subTest(mutation=mutation):
                plan = copy.deepcopy(self.plan)
                if mutation == 'old':
                    plan['files'].append(tool.plan_file(tool.ROOT / tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_OLD][0], True))
                elif mutation == 'missing':
                    del plan['broadcastForward']
                elif mutation == 'hash':
                    plan['broadcastForward']['fingerprints'][tool.BROADCAST_OLD]['sha256'] = '0' * 64
                else:
                    next(file for file in plan['files'] if file['path'] ==
                         tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_UNION][0])['apply'][0] = 'SELECT 1'
                with patch.object(tool, 'build_plan', return_value=copy.deepcopy(self.plan)), \
                        patch.object(tool, 'mysql_command') as command:
                    with self.assertRaisesRegex(ValueError, 'execution plan drift'):
                        tool.execute(SimpleNamespace(), plan)
                    command.assert_not_called()

    def test_existing_schema_has_no_replacement_receipt_or_mutation(self):
        plan = copy.deepcopy(self.plan)
        args = SimpleNamespace(host='127.0.0.1', database='cy_e2e_broadcast_union', port=3316, user='root')
        outputs = [SimpleNamespace(stdout='8.0.45'), SimpleNamespace(stdout=args.database)]
        with patch.object(tool, 'build_plan', return_value=copy.deepcopy(self.plan)), \
                patch.object(tool.shutil, 'which', return_value='/usr/bin/mysql'), \
                patch.object(tool.subprocess, 'run', side_effect=outputs) as run, patch('builtins.print') as emitted:
            with self.assertRaisesRegex(ValueError, 'Existing schema refused'):
                tool.execute(args, plan)
        self.assertEqual(2, run.call_count)
        self.assertEqual('PLANNED_UNAPPLIED', plan['broadcastForward']['state'])
        self.assertFalse(any('REPLACED_UNAPPLIED' in str(call) for call in emitted.call_args_list))
        self.assertFalse(any('CREATE DATABASE' in call.kwargs['input'] for call in run.call_args_list))

    def test_union_failure_stops_without_completing_original_or_following_files(self):
        plan = copy.deepcopy(self.plan)
        args = SimpleNamespace(host='127.0.0.1', database='cy_e2e_broadcast_union', port=3316, user='root')
        union_path = tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_UNION][0]
        index = next(i for i, file in enumerate(plan['files']) if file['path'] == union_path)
        outputs = [SimpleNamespace(stdout=value) for value in ('8.0.45', '', '')]
        for file in plan['files']:
            if file['path'] == union_path:
                outputs.append(subprocess.CalledProcessError(1, ['mysql'], stderr='ERROR 1060 (42S21): fixture'))
                break
            if file['apply']:
                outputs.append(SimpleNamespace(stdout='\n'.join(['1'] * len(file['assertions']))))
        with patch.object(tool, 'build_plan', return_value=copy.deepcopy(self.plan)), \
                patch.object(tool.shutil, 'which', return_value='/usr/bin/mysql'), \
                patch.object(tool.subprocess, 'run', side_effect=outputs), patch('builtins.print'):
            with self.assertRaises(subprocess.CalledProcessError):
                tool.execute(args, plan)
        self.assertEqual([file['path'] for file in plan['files'][:index]], plan['completed'])
        self.assertEqual(union_path, plan['currentFile'])
        self.assertNotIn(tool.BROADCAST_FORWARD_FILES[tool.BROADCAST_OLD][0], plan['completed'])

if __name__ == '__main__':
    unittest.main()

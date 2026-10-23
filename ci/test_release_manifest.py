import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('release_manifest', Path(__file__).with_name('release_manifest.py'))
rm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rm)

REPO_ROOT = Path(__file__).resolve().parent.parent
SQL = 'chengyinhub-admin/sql/'
POM = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>'
       '<module>chengyinhub-admin</module><module>chengyinhub-common</module></modules></project>')
# Fixture appid only — the manifest must never copy it.
PROJECT_CONFIG = json.dumps({'appid': 'wx_fixture_should_not_leak'})


class FixtureRepo:
    def __init__(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = self.dir.name
        self.git('init', '-q')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'fixture')
        self.git('config', 'commit.gpgsign', 'false')

    def git(self, *args):
        return subprocess.run(['git', '-C', self.root] + list(args), check=True,
                              capture_output=True, text=True).stdout.strip()

    def write(self, files):
        for path, body in files.items():
            target = Path(self.root, path)
            if body is None:
                target.unlink()
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body, encoding='utf-8')

    def commit(self, files):
        self.write(files)
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'fixture')
        return self.git('rev-parse', 'HEAD')


def base_files():
    return {
        'pom.xml': POM,
        '.github/workflows/ci.yml': 'name: ci\n',
        '.github/workflows/backend-jar.yml': 'name: jar\n',
        'ci/migration-dependencies.json': json.dumps(
            {'schemaVersion': 1, 'dependencies': {'a_first': ['z_base']}}),
        SQL + 'migration_z_base.sql': 'CREATE TABLE IF NOT EXISTS t_base (id BIGINT);\n',
        SQL + 'migration_a_first.sql': 'INSERT INTO t_base (id) SELECT 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_base);\n',
        SQL + 'migration_m_gone.sql': 'SELECT 1;\n',
        SQL + 'bak_not_managed.sql': 'DROP TABLE t_base;\n',
        'chengyinhub-xcx/app.json': '{"pages":["pages/index/index"]}\n',
        'chengyinhub-xcx/project.config.json': PROJECT_CONFIG,
        'chengyinhub-xcx/pages/index/index.js': 'Page({})\n',
    }


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.repo = FixtureRepo()
        self.addCleanup(self.repo.dir.cleanup)
        self.sha = self.repo.commit(base_files())

    def test_manifest_identity_and_placeholders(self):
        manifest = rm.build_manifest(self.repo.root, 'HEAD')
        self.assertEqual(self.sha, manifest['public']['commit'])
        self.assertEqual(self.repo.git('rev-parse', 'HEAD^{tree}'), manifest['public']['tree'])
        self.assertEqual(['chengyinhub-admin', 'chengyinhub-common'], manifest['backend']['modules'])
        self.assertIn('--commit ' + self.sha, manifest['ci']['lookupCommand'])
        self.assertEqual('NOT_LOOKED_UP', manifest['ci']['lookup']['status'])
        self.assertEqual(['.github/workflows/backend-jar.yml', '.github/workflows/ci.yml'],
                         manifest['ci']['expectedWorkflows'])
        jar = manifest['jar']
        self.assertEqual(rm.TO_FILL, jar['sha256'])
        self.assertEqual(rm.TO_FILL, jar['embeddedGitCommitId'])
        self.assertEqual(self.sha, jar['embeddedGitCommitIdMustEqual'])
        self.assertIs(False, jar['privateUpload']['deployed'])
        self.assertEqual(rm.OWNER_INPUT, manifest['backup']['databaseBackupId'])
        self.assertIn('DDL', manifest['rollback']['note'])

    def test_migrations_ordered_with_sha256_and_applied_unknown(self):
        items = rm.build_manifest(self.repo.root, 'HEAD')['migrations']['items']
        ids = [m['migrationId'] for m in items]
        self.assertEqual(['m_gone', 'z_base', 'a_first'], ids)  # 依赖优先于字节序
        self.assertNotIn('bak_not_managed', ' '.join(m['path'] for m in items))
        body = Path(self.repo.root, SQL + 'migration_z_base.sql').read_bytes()
        self.assertEqual(hashlib.sha256(body).hexdigest(), items[1]['sha256'])
        self.assertEqual({'UNKNOWN'}, {m['applied'] for m in items})

    def test_mini_program_identity_without_appid(self):
        manifest = rm.build_manifest(self.repo.root, 'HEAD')
        mini = manifest['miniProgram']
        self.assertEqual(self.repo.git('rev-parse', 'HEAD:chengyinhub-xcx'), mini['treeHash'])
        self.assertEqual(3, mini['fileCount'])
        self.assertEqual(self.repo.git('rev-parse', 'HEAD:chengyinhub-xcx/app.json'), mini['appJsonBlob'])
        self.assertEqual(2 * 1024 * 1024, mini['mainPackageLimitBytes'])
        self.assertEqual(rm.OWNER_INPUT, mini['appid'])
        self.assertNotIn('wx_fixture_should_not_leak', json.dumps(manifest))

    def test_manifest_reads_commit_not_working_tree(self):
        self.repo.write({SQL + 'migration_z_base.sql': 'DROP TABLE t_base;\n'})
        before = rm.build_manifest(self.repo.root, self.sha)['migrations']['items']
        committed = Path(self.repo.root, SQL + 'migration_z_base.sql')
        self.assertNotEqual(before[1]['sha256'], hashlib.sha256(committed.read_bytes()).hexdigest())

    def test_unknown_commit_fails_closed(self):
        with self.assertRaises(rm.ManifestError):
            rm.build_manifest(self.repo.root, 'deadbeef' * 5)

    def test_cli_writes_json(self):
        out = Path(self.repo.root, 'out.json')
        self.assertEqual(0, rm.main(['--root', self.repo.root, 'manifest', '--commit', 'HEAD', '--output', str(out)]))
        self.assertEqual(self.sha, json.loads(out.read_text(encoding='utf-8'))['public']['commit'])
        self.assertEqual(2, rm.main(['--root', self.repo.root, 'manifest', '--commit', 'no-such-ref']))


class CiLookupTest(unittest.TestCase):
    SHA = 'a' * 40

    def fake(self, returncode=0, stdout='[]'):
        return lambda *a, **k: subprocess.CompletedProcess(a[0], returncode, stdout, '')

    def test_lookup_keeps_only_matching_head_sha(self):
        runs = [{'headSha': self.SHA, 'workflowName': 'CI'}, {'headSha': 'b' * 40, 'workflowName': 'CI'}]
        result = rm.lookup_ci('o/r', self.SHA, runner=self.fake(stdout=json.dumps(runs)))
        self.assertEqual('LOOKED_UP', result['status'])
        self.assertEqual([runs[0]], result['runs'])

    def test_lookup_failure_is_recorded_not_green(self):
        self.assertEqual('LOOKUP_FAILED', rm.lookup_ci('o/r', self.SHA, runner=self.fake(returncode=1))['status'])
        self.assertEqual('LOOKUP_FAILED', rm.lookup_ci('o/r', self.SHA, runner=self.fake(stdout='not json'))['status'])

        def boom(*_a, **_k):
            raise FileNotFoundError('gh')
        self.assertEqual('LOOKUP_FAILED', rm.lookup_ci('o/r', self.SHA, runner=boom)['status'])


class AnalyzeSqlTest(unittest.TestCase):
    def analyze(self, text):
        return rm.analyze_sql('migration_x.sql', text)

    def test_guarded_create_and_insert_are_idempotent(self):
        result = self.analyze('CREATE TABLE IF NOT EXISTS t (id INT);\n'
                              "INSERT INTO sys_config (k) SELECT 'a' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE k='a');\n")
        self.assertEqual('IDEMPOTENT_STATIC', result['idempotency'])
        self.assertEqual([], result['destructiveStatements'])

    def test_unguarded_ddl_needs_review(self):
        result = self.analyze('CREATE TABLE t (id INT);\nALTER TABLE t ADD COLUMN c INT;\nINSERT INTO t VALUES (1);\n')
        self.assertEqual('NEEDS_REVIEW', result['idempotency'])
        self.assertEqual(3, len(result['idempotencyIssues']))

    def test_information_schema_guarded_dynamic_ddl(self):
        result = self.analyze(
            "SET @c = (SELECT COUNT(*) FROM information_schema.columns WHERE table_name='t' AND column_name='v');\n"
            "SET @d = IF(@c = 0, 'ALTER TABLE t ADD COLUMN v INT', 'SELECT 1');\n"
            'PREPARE s FROM @d;\nEXECUTE s;\nDEALLOCATE PREPARE s;\n')
        self.assertEqual('GUARDED_INFORMATION_SCHEMA', result['idempotency'])
        self.assertTrue(result['informationSchemaGuard'])

    def test_destructive_statements_are_listed(self):
        result = self.analyze('DROP TABLE IF EXISTS old_t;\nTRUNCATE TABLE t;\nDELETE FROM t;\n'
                              'DELETE FROM t WHERE id = 1;\nUPDATE t SET a = 1;\nALTER TABLE t DROP COLUMN c;\n')
        joined = '\n'.join(result['destructiveStatements'])
        for needle in ('DROP TABLE', 'TRUNCATE', 'DELETE 无 WHERE', 'DELETE 带 WHERE', 'UPDATE', 'DROP COLUMN'):
            self.assertIn(needle, joined)

    def test_temporary_tables_are_not_destructive_or_unguarded(self):
        result = self.analyze('DROP TEMPORARY TABLE IF EXISTS tmp_x;\n'
                              'CREATE TEMPORARY TABLE tmp_x (id INT PRIMARY KEY);\n'
                              'INSERT INTO tmp_x (id) VALUES (1);\nDROP TEMPORARY TABLE tmp_x;\n')
        self.assertEqual('IDEMPOTENT_STATIC', result['idempotency'])
        self.assertEqual([], result['destructiveStatements'])

    def test_dynamic_drop_is_flagged(self):
        result = self.analyze("SET @d = 'ALTER TABLE t DROP COLUMN c';\nPREPARE s FROM @d;\nEXECUTE s;\n")
        self.assertEqual('NEEDS_REVIEW', result['idempotency'])
        self.assertTrue(any('DROP' in s for s in result['destructiveStatements']))


class DiffTest(unittest.TestCase):
    def test_added_modified_deleted_unchanged(self):
        repo = FixtureRepo()
        self.addCleanup(repo.dir.cleanup)
        base = repo.commit(base_files())
        head = repo.commit({
            SQL + 'migration_a_first.sql': 'INSERT IGNORE INTO t_base (id) VALUES (1);\n',
            SQL + 'migration_m_gone.sql': None,
            SQL + 'migration_n_new.sql': 'TRUNCATE TABLE t_base;\n',
        })
        diff = rm.migration_diff(repo.root, base, head)
        by_id = {row['migrationId']: row for row in diff['changes']}
        self.assertEqual({'a_first', 'm_gone', 'n_new'}, set(by_id))
        self.assertEqual('MODIFIED', by_id['a_first']['change'])
        self.assertIsNotNone(by_id['a_first']['baseSha256'])
        self.assertEqual('DELETED', by_id['m_gone']['change'])
        self.assertEqual('ADDED', by_id['n_new']['change'])
        self.assertTrue(any('TRUNCATE' in s for s in by_id['n_new']['destructiveStatements']))
        self.assertEqual({True}, {row['needsOwnerApproval'] for row in diff['changes']})
        self.assertEqual({'UNKNOWN'}, {row['applied'] for row in diff['changes']})


class RealRepoSmokeTest(unittest.TestCase):
    def test_head_manifest_covers_every_managed_migration(self):
        if not (REPO_ROOT / '.git').exists():
            self.skipTest('not a git checkout')
        manifest = rm.build_manifest(str(REPO_ROOT), 'HEAD')
        tracked = subprocess.run(['git', '-C', str(REPO_ROOT), 'ls-tree', '-r', '--name-only', 'HEAD', '--',
                                  rm.MIGRATION_DIR], check=True, capture_output=True, text=True).stdout.split()
        expected = [p for p in tracked if os.path.basename(p).startswith('migration_') and p.endswith('.sql')]
        paths = {m['path'] for m in manifest['migrations']['items']}
        self.assertTrue(set(expected) <= paths)
        self.assertEqual(list(range(1, len(paths) + 1)), [m['order'] for m in manifest['migrations']['items']])


if __name__ == '__main__':
    unittest.main()

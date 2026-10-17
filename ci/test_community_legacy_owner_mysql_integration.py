"""Actual versioned runner and retained backfill, only in a fresh local schema."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import uuid

import pymysql

from ci.run_migration import assert_mysql_server_provenance
from ci.sql_splitter import split_statements, executable_statement

REPO = pathlib.Path(__file__).resolve().parents[1]
SQL = REPO / 'chengyinhub-admin/sql'
OLD = SQL / 'migration_community_post_platform_20260909.sql'
FIX = SQL / 'migration_community_legacy_route_snapshot_20261010.sql'
RUNNER = REPO / 'ci/run_migration.py'
PREFIX = 'cy_community_legacy_owner_it_'


class CommunityLegacyOwnerMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get('CHENGYIN_MYSQL_IT') != '1':
            raise RuntimeError('explicit local MySQL IT gate required; no skip')
        cls.host = os.environ['CHENGYIN_MYSQL_IT_HOST']
        cls.port = int(os.environ['CHENGYIN_MYSQL_IT_PORT'])
        if cls.host not in {'127.0.0.1', 'localhost', '::1'}:
            raise RuntimeError('local scratch host required')
        cls.user = os.environ.get('CHENGYIN_MYSQL_IT_USER', 'root')
        cls.password = os.environ.get('CHENGYIN_MYSQL_IT_PASSWORD', '')
        cls.schema = PREFIX + uuid.uuid4().hex[:12]
        cls.admin = pymysql.connect(host=cls.host, port=cls.port, user=cls.user,
                                   password=cls.password, autocommit=True, connect_timeout=5)
        with cls.admin.cursor() as cur:
            cur.execute('SELECT VERSION(), @@version_comment')
            assert_mysql_server_provenance(*cur.fetchone())
            cur.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s', (cls.schema,))
            if cur.fetchone()[0] != 0:
                raise RuntimeError('new scratch schema must not exist')
        cls.private = tempfile.TemporaryDirectory(prefix='cy-community-legacy-it-')
        cls.config = pathlib.Path(cls.private.name) / 'config.yml'
        cls.config.write_text('url: jdbc:mysql://%s:%s/%s\nusername: %s\npassword: ${CHENGYIN_MYSQL_IT_PASSWORD:-}\n'
                              % (cls.host, cls.port, cls.schema, cls.user))
        cls.config.chmod(0o600)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, 'admin'):
            if not cls.schema.startswith(PREFIX):
                raise RuntimeError('invalid owned schema')
            with cls.admin.cursor() as cur:
                cur.execute('DROP DATABASE IF EXISTS `' + cls.schema + '`')
            cls.admin.close()
        if hasattr(cls, 'private'):
            cls.private.cleanup()

    def connect(self):
        return pymysql.connect(host=self.host, port=self.port, user=self.user,
                               password=self.password, database=self.schema, autocommit=True)

    def execute(self, statement, params=None):
        with self.connect() as conn, conn.cursor() as cur:
            cur.execute(statement, params)
            return cur.fetchall()

    def run_sql(self, path, transaction=False, inline=None, runner=RUNNER):
        env = os.environ.copy()
        env['CHENGYIN_DB_SSH_HOST'] = 'local'
        env['CHENGYIN_MYSQL_IT_PASSWORD'] = self.password
        env['PYTHONPATH'] = str(REPO) + os.pathsep + env.get('PYTHONPATH', '')
        args = [sys.executable, str(runner), '--config', str(self.config)]
        args += ['--exec', inline] if inline else ['--sql', str(path)]
        if transaction:
            args.append('--transaction')
        return subprocess.run(args, cwd=REPO, env=env, capture_output=True, text=True)

    def passed(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def stopped_without_writes(self, result, before):
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('preflight failed before all DDL/DML', result.stdout)
        self.assertNotIn('[1/', result.stdout)
        self.assertEqual(before, self.snapshot())

    def snapshot(self):
        names = ('ums_creative_square', 'ums_comment', 'ums_comment_like', 'ums_creative_square_like',
                 'roam_session', 'community_post', 'community_post_media', 'community_post_reference',
                 'community_post_comment', 'community_post_member_action', 'community_post_comment_action')
        return {name: self.execute('SELECT * FROM `' + name + '` ORDER BY id') for name in names}

    def setUp(self):
        with self.admin.cursor() as cur:
            cur.execute('DROP DATABASE IF EXISTS `' + self.schema + '`')
            cur.execute('CREATE DATABASE `' + self.schema + '` CHARACTER SET utf8mb4')
        # Real retained prerequisite DDL; no rewritten community target schema.
        wanted = {'community_report_case', 'community_report_timeline', 'biz_event_log', 'roam_session'}
        for name in ('migration_community_trust_20260828.sql', 'migration_p3_platform_foundation.sql', 'migration_roam.sql'):
            for stmt in split_statements((SQL / name).read_text()):
                text = executable_statement(stmt)
                if any(text.upper().startswith('CREATE TABLE IF NOT EXISTS ' + table.upper() + ' ') for table in wanted):
                    self.execute(stmt)
        # Minimal legacy source fixture includes every column read by the exact old SQL.
        self.execute('CREATE TABLE ums_creative_square(id BIGINT PRIMARY KEY,member_id BIGINT,contents TEXT,pics TEXT,'
                     'file_url TEXT,data_id BIGINT,data_type INT,route_preview_img TEXT,status INT,del_flag INT,'
                     'create_time DATETIME,update_time DATETIME) ENGINE=InnoDB')
        self.execute('CREATE TABLE ums_creative_square_like(id BIGINT PRIMARY KEY,member_id BIGINT,data_id BIGINT,'
                     'like_type INT,create_time DATETIME,update_time DATETIME) ENGINE=InnoDB')
        self.execute('CREATE TABLE ums_comment(id BIGINT PRIMARY KEY,member_id BIGINT,owner_type INT,owner_id BIGINT,'
                     'contents TEXT,reply_id BIGINT,status INT,del_flag INT,create_time DATETIME,update_time DATETIME) ENGINE=InnoDB')
        self.execute('CREATE TABLE ums_comment_like(id BIGINT PRIMARY KEY,member_id BIGINT,comment_id BIGINT,create_time DATETIME) ENGINE=InnoDB')
        self.passed(self.run_sql(OLD))
        self.execute("INSERT INTO roam_session(id,member_id,distance_m,tiles_revealed,pois_found,status,finished_at) "
                     "VALUES(10,101,321,4,2,1,'2026-10-09 10:10:00'),(11,102,123,1,1,1,'2026-10-09 10:10:00'),(12,101,0,0,0,0,NULL)")
        self.execute("INSERT INTO ums_creative_square VALUES"
                     "(1,101,'media positive','image-a;image-b','file-a',NULL,0,NULL,1,0,'2026-10-09','2026-10-09'),"
                     "(2,101,'own route',NULL,NULL,10,3,'route-image',1,0,'2026-10-09','2026-10-09'),"
                     "(3,101,'foreign route',NULL,NULL,11,3,NULL,1,0,'2026-10-09','2026-10-09'),"
                     "(4,101,'unfinished route',NULL,NULL,12,3,NULL,1,0,'2026-10-09','2026-10-09')")
        self.execute("INSERT INTO ums_comment VALUES(20,101,3,1,'parent',0,1,0,'2026-10-09',NULL),"
                     "(21,102,3,1,'reply',20,1,0,'2026-10-09',NULL)")
        self.execute("INSERT INTO ums_creative_square_like VALUES(30,102,1,1,'2026-10-09',NULL)")
        self.execute("INSERT INTO ums_comment_like VALUES(40,102,20,'2026-10-09')")
        self.passed(self.run_sql(OLD))

    def collision(self):
        self.execute("INSERT INTO ums_creative_square VALUES(9,101,'legacy collision','collision-image',NULL,NULL,0,NULL,1,0,'2026-10-09','2026-10-09')")
        # Clone a complete actual native row shape without overriding the legacy source.
        columns = [row[0] for row in self.execute('SHOW COLUMNS FROM community_post')]
        expressions = ['9' if col == 'id' else '102' if col == 'author_id' else "'native-collision'" if col == 'client_request_id' else col for col in columns]
        self.execute('INSERT INTO community_post (' + ','.join(columns) + ') SELECT ' + ','.join(expressions) + ' FROM community_post WHERE id=1')

    def test_positive_nonempty_semantics_and_route_authority(self):
        before = self.snapshot()
        self.passed(self.run_sql(FIX, True))
        after = self.snapshot()
        for table in before.keys() - {'community_post_reference'}:
            self.assertEqual(before[table], after[table], table)
        self.assertEqual(4, len(after['community_post_media']))
        self.assertEqual(2, len(after['community_post_comment']))
        self.assertEqual(1, len(after['community_post_member_action']))
        self.assertEqual(1, len(after['community_post_comment_action']))
        rows = self.execute('SELECT post_id,snapshot_json,invalidated_at FROM community_post_reference ORDER BY post_id')
        own = json.loads(rows[0][1])
        self.assertEqual((10, 'ROUTE', 321, 4, 2), (own['reference_id'], own['reference_type'], own['distance_m'], own['tiles_revealed'], own['pois_found']))
        self.assertEqual('城市漫游记录', own['title'])
        self.assertTrue(own['subtitle'] and own['finished_at'])
        self.assertIsNone(rows[0][2])
        self.assertIsNotNone(rows[1][2])
        self.assertIsNotNone(rows[2][2])
        self.execute("INSERT INTO community_post_member_action(post_id,member_id,action_type,request_id,source,create_time,update_time) "
                     "VALUES(1,1000,'BOOKMARK','legacy-square-like-native-bookmark','NATIVE',NOW(),NOW())")
        self.passed(self.run_sql(OLD))  # The original LIKE-only counter must not count this bookmark.
        self.execute("INSERT INTO community_post_member_action(post_id,member_id,action_type,request_id,source,create_time,update_time) "
                     "VALUES(1,999,'LIKE','legacy-square-like-30','NATIVE',NOW(),NOW())")
        self.execute("INSERT INTO community_post_comment_action(comment_id,member_id,action_type,request_id,create_time) "
                     "VALUES(20,999,'LIKE','legacy-comment-like-40',NOW())")
        # Exercise the actual retained INSERTs, not the old broad request-prefix count assertions.
        for statement in split_statements(OLD.read_text()):
            text = executable_statement(statement)
            if text.startswith('INSERT IGNORE INTO community_post_member_action') or text.startswith('INSERT IGNORE INTO community_post_comment_action'):
                self.passed(self.run_sql(None, inline=text))
        self.assertEqual(3, self.execute('SELECT COUNT(*) FROM community_post_member_action')[0][0])
        self.assertEqual(2, self.execute('SELECT COUNT(*) FROM community_post_comment_action')[0][0])
        before = self.snapshot()
        result = self.run_sql(OLD)
        self.stopped_without_writes(result, before)
        self.assertIn('counter provenance ambiguous', result.stdout)

    def test_native_collision_precedes_all_ddl_and_dml(self):
        self.collision()
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())

    def test_original_runner_negative_control_commits_wrong_owner_media(self):
        original = pathlib.Path(self.private.name) / 'original-runner.py'
        original.write_bytes(subprocess.check_output(
            ['git', 'show', 'fafde98723211ccbc12b78a534a0cb2c1685e361:ci/run_migration.py'], cwd=REPO))
        self.execute("UPDATE community_post_reference SET snapshot_json=JSON_OBJECT('native',TRUE) WHERE post_id=2")
        self.execute("INSERT INTO ums_creative_square VALUES(5,101,'pending valid media','pending-image',NULL,NULL,0,NULL,1,0,'2026-10-09','2026-10-09')")
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        result = self.run_sql(OLD, runner=original)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(((101, 5),), self.execute("SELECT owner_member_id,post_id FROM community_post_media WHERE upload_request_id='legacy-square-media-5-1'"))
        print('Original runner RED: native reference key collision fails after pending media committed')
        self.setUp()
        self.collision()
        result = self.run_sql(OLD, runner=original)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(((102, 'native-collision'),), self.execute(
            'SELECT author_id,client_request_id FROM community_post WHERE id=9'))
        self.assertEqual(((101, 9),), self.execute(
            "SELECT owner_member_id,post_id FROM community_post_media WHERE upload_request_id='legacy-square-media-9-1'"))
        print('Original runner RED: one wrong-owner media row committed before scalar failure; owned scratch only')

    def test_renamed_and_inline_backfill_have_same_guard(self):
        # Existing native rows without a square source must not become targets for orphan interactions.
        self.collision()
        self.execute('DELETE FROM ums_creative_square WHERE id=9')
        self.execute("INSERT INTO ums_creative_square_like VALUES(99,101,9,1,'2026-10-09',NULL)")
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        self.execute('DELETE FROM ums_creative_square_like WHERE id=99')
        self.execute("INSERT INTO ums_comment VALUES(99,101,3,9,'orphan native target',0,1,0,'2026-10-09',NULL)")
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        self.execute('DELETE FROM ums_comment WHERE id=99')
        self.execute("UPDATE community_post_comment SET client_request_id='native-comment',author_id=999 WHERE id=20")
        self.execute('DELETE FROM ums_comment WHERE id=20')
        self.execute('DELETE FROM ums_comment WHERE id=21')
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        # Recreate this test's own disposable fixture before the renamed/inline checks.
        self.setUp()
        self.collision()
        renamed = pathlib.Path(self.private.name) / 'renamed.sql'
        renamed.write_text(OLD.read_text())
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(renamed), before)
        self.stopped_without_writes(self.run_sql(None, inline="INSERT IGNORE INTO community_post_media(owner_member_id,upload_request_id,post_id) SELECT member_id,CONCAT('legacy-square-media-',id,'-1'),id FROM ums_creative_square"), before)
        qualified = "INSERT IGNORE INTO `{0}`.`community_post_media`(owner_member_id,upload_request_id,post_id) SELECT member_id,CONCAT('legacy-square-media-',id,'-1'),id FROM `{0}`.`ums_creative_square`".format(self.schema)
        self.stopped_without_writes(self.run_sql(None, inline=qualified), before)
        self.stopped_without_writes(self.run_sql(None, inline=qualified.replace('`.`', '` . `')), before)
        self.stopped_without_writes(self.run_sql(None, inline=qualified.replace('`'+self.schema+'`.`community_post_media`', '`unapproved_schema`.`community_post_media`')), before)

    def test_comment_collision_rejects_without_guessing_cleanup(self):
        self.execute("INSERT INTO ums_creative_square_like VALUES(31,102,1,1,'2026-10-09',NULL)")
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        self.execute('DELETE FROM ums_creative_square_like WHERE id=31')
        self.execute("INSERT INTO ums_comment_like VALUES(41,102,20,'2026-10-09')")
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        self.execute('DELETE FROM ums_comment_like WHERE id=41')
        self.execute('UPDATE ums_comment SET reply_id=999 WHERE id=20')
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        self.execute('UPDATE ums_comment SET reply_id=0 WHERE id=20')
        self.execute('UPDATE community_post_comment SET root_id=999 WHERE id=21')
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())
        self.execute('UPDATE community_post_comment SET root_id=20 WHERE id=21')
        self.execute("UPDATE community_post_comment SET author_id=999,client_request_id='native-comment' WHERE id=20")
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())

    def test_historical_foreign_media_is_rejected_and_preserved(self):
        self.execute('UPDATE community_post_media SET post_id=2,owner_member_id=102 WHERE post_id=1 LIMIT 1')
        self.stopped_without_writes(self.run_sql(OLD), self.snapshot())

    def test_post_write_assertion_failure_rolls_back_entire_repair(self):
        broken = pathlib.Path(self.private.name) / 'broken-repair.sql'
        broken.write_text(FIX.read_text().replace('DROP TEMPORARY TABLE community_legacy_route_repair;',
                           'INSERT INTO community_legacy_route_assert(assertion_ok) VALUES(0);\nDROP TEMPORARY TABLE community_legacy_route_repair;'))
        before = self.snapshot()
        result = self.run_sql(broken, True)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('ROLLBACK OK', result.stdout)
        self.assertEqual(before, self.snapshot())

    def test_second_repair_is_exactly_idempotent(self):
        self.passed(self.run_sql(FIX, True))
        before = self.snapshot()
        self.passed(self.run_sql(FIX, True))
        self.assertEqual(before, self.snapshot())


if __name__ == '__main__':
    unittest.main()

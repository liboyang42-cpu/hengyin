"""Actual versioned runner and retained backfill, only in a fresh local schema."""
import json
import os
import pathlib
import re
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
FIX = SQL / 'migration_community_legacy_comment_like_counts_20261015.sql'
RUNNER = REPO / 'ci/run_migration.py'
PREFIX = 'cy_community_legacy_it_'


class CommunityLegacyCommentLikesMysqlIntegrationTest(unittest.TestCase):
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
            if not re.fullmatch(PREFIX + '[0-9a-f]{12}', cls.schema):
                raise RuntimeError('invalid owned schema')
            with cls.admin.cursor() as cur:
                cur.execute('DROP DATABASE IF EXISTS `' + cls.schema + '`')
                cur.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s', (cls.schema,))
                if cur.fetchone()[0]:
                    raise RuntimeError('owned scratch cleanup readback failed')
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
        names = [row[0] for row in self.execute('SHOW TABLES')]
        return {name: (self.execute('SHOW CREATE TABLE `' + name + '`')[0][1],
                       tuple(sorted(self.execute('SELECT * FROM `' + name + '`'), key=lambda row: json.dumps(row, default=str)))) for name in names}

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

    def test_counter_repair_replays_and_preserves_native_comments(self):
        self.execute("INSERT INTO community_post_comment(id,post_id,author_id,client_request_id,body,lifecycle,like_count,create_time,update_time) "
                     "VALUES(200,1,102,'native-comment','native comment','PUBLISHED',9,'2026-10-09','2026-10-09')")
        self.assertEqual(((20, 0), (21, 0), (200, 9)), self.execute('SELECT id,like_count FROM community_post_comment ORDER BY id'))
        self.execute('ALTER TABLE community_post_comment MODIFY like_count INT UNSIGNED NULL')
        self.execute('UPDATE community_post_comment SET like_count=NULL WHERE id IN (20,21)')
        before = self.snapshot()
        self.passed(self.run_sql(FIX, True))
        after = self.snapshot()
        self.assertEqual(((20, 1), (21, 0), (200, 9)), self.execute('SELECT id,like_count FROM community_post_comment ORDER BY id'))
        for table in before:
            if table != 'community_post_comment':
                self.assertEqual(before[table], after[table], table)
        self.assertEqual(before['community_post_comment'][0], after['community_post_comment'][0])
        self.assertEqual([row[:10] + row[11:] for row in before['community_post_comment'][1]],
                         [row[:10] + row[11:] for row in after['community_post_comment'][1]])
        self.passed(self.run_sql(FIX, True))
        self.assertEqual(after, self.snapshot())

    def test_counter_repair_rejects_ambiguous_old_author_without_writes(self):
        self.execute('UPDATE community_post_comment SET author_id=999 WHERE id=20')
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(FIX, True), before)

    def test_legacy_guard_accepts_valid_input_without_writes(self):
        from ci.run_migration import (assert_community_legacy_owner_preflight,
                                      assert_community_legacy_counter_preflight)
        before = self.snapshot()
        with self.connect() as conn, conn.cursor() as cur:
            assert_community_legacy_owner_preflight(cur, self.schema)
            assert_community_legacy_counter_preflight(cur, self.schema, split_statements(OLD.read_text()))
        self.assertEqual(before, self.snapshot())

    def test_post_id_and_different_id_request_collisions_stop_before_ddl(self):
        self.collision()
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)
        self.execute('DELETE FROM ums_creative_square WHERE id=9')
        self.execute('DELETE FROM community_post WHERE id=9')
        for marker in ['legacy-square-1', 'LEGACY-SQUARE-1']:
            with self.subTest(marker=marker):
                self.execute('UPDATE community_post SET id=200,client_request_id=%s WHERE id=1', (marker,))
                before = self.snapshot()
                self.stopped_without_writes(self.run_sql(OLD), before)
                self.execute('UPDATE community_post SET id=1,client_request_id=%s WHERE id=200', ('legacy-square-1',))

    def test_comment_identity_and_request_collisions_stop_before_ddl(self):
        for column, value in [('author_id', 999), ('post_id', 999), ('client_request_id', 'native-comment')]:
            with self.subTest(column=column):
                old_value = self.execute('SELECT `' + column + '` FROM community_post_comment WHERE id=20')[0][0]
                self.execute('UPDATE community_post_comment SET `' + column + '`=%s WHERE id=20', (value,))
                before = self.snapshot()
                self.stopped_without_writes(self.run_sql(OLD), before)
                self.execute('UPDATE community_post_comment SET `' + column + '`=%s WHERE id=20', (old_value,))
        for marker in ['legacy-square-comment-20', 'LEGACY-SQUARE-COMMENT-20']:
            with self.subTest(marker=marker):
                self.execute('UPDATE community_post_comment SET id=222,client_request_id=%s WHERE id=20', (marker,))
                before = self.snapshot()
                self.stopped_without_writes(self.run_sql(OLD), before)
                self.execute("UPDATE community_post_comment SET id=20,client_request_id='legacy-square-comment-20' WHERE id=222")

    def test_nullable_and_case_drift_is_rejected_before_writes(self):
        self.execute('ALTER TABLE community_post MODIFY author_id BIGINT NULL, MODIFY client_request_id VARCHAR(96) NULL')
        self.execute('ALTER TABLE community_post_comment MODIFY author_id BIGINT NULL, MODIFY post_id BIGINT NULL, MODIFY client_request_id VARCHAR(96) NULL')
        cases = [('community_post', 1, 'author_id', None), ('community_post', 1, 'client_request_id', None),
                 ('community_post_comment', 20, 'author_id', None), ('community_post_comment', 20, 'post_id', None),
                 ('community_post_comment', 20, 'client_request_id', None),
                 ('community_post', 1, 'client_request_id', 'LEGACY-SQUARE-1'),
                 ('community_post_comment', 20, 'client_request_id', 'LEGACY-SQUARE-COMMENT-20')]
        for table, row_id, column, value in cases:
            with self.subTest(table=table, column=column, value=value):
                old_value = self.execute('SELECT `' + column + '` FROM `' + table + '` WHERE id=%s', (row_id,))[0][0]
                self.execute('UPDATE `' + table + '` SET `' + column + '`=%s WHERE id=%s', (value, row_id))
                before = self.snapshot()
                self.stopped_without_writes(self.run_sql(OLD), before)
                self.execute('UPDATE `' + table + '` SET `' + column + '`=%s WHERE id=%s', (old_value, row_id))

    def test_orphan_post_like_and_comment_cannot_attach_to_native_targets(self):
        self.collision()
        self.execute('DELETE FROM ums_creative_square WHERE id=9')
        self.execute("INSERT INTO ums_creative_square_like VALUES(99,101,9,1,'2026-10-09',NULL)")
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)
        self.execute('DELETE FROM ums_creative_square_like WHERE id=99')
        self.execute("INSERT INTO ums_comment VALUES(99,101,3,9,'orphan native target',0,1,0,'2026-10-09',NULL)")
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)
        self.execute('DELETE FROM ums_comment WHERE id=99')
        self.execute("UPDATE community_post_comment SET client_request_id='native-comment',author_id=999 WHERE id=20")
        self.execute('DELETE FROM ums_comment WHERE id=20')
        self.execute('DELETE FROM ums_comment WHERE id=21')
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)

    def test_duplicate_source_interactions_and_broken_comment_tree_are_rejected(self):
        self.execute("INSERT INTO ums_creative_square_like VALUES(31,102,1,1,'2026-10-09',NULL)")
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)
        self.execute('DELETE FROM ums_creative_square_like WHERE id=31')
        self.execute("INSERT INTO ums_comment_like VALUES(41,102,20,'2026-10-09')")
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)
        self.execute('DELETE FROM ums_comment_like WHERE id=41')
        self.execute('UPDATE ums_comment SET reply_id=999 WHERE id=20')
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)

    def test_wrong_comment_root_and_foreign_media_are_rejected(self):
        self.execute('UPDATE community_post_comment SET root_id=999 WHERE id=21')
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)
        self.execute('UPDATE community_post_comment SET root_id=20 WHERE id=21')
        self.execute('UPDATE community_post_media SET post_id=2,owner_member_id=102 WHERE post_id=1 LIMIT 1')
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)

    def test_reference_and_counter_provenance_are_rejected(self):
        self.execute("UPDATE community_post_reference SET snapshot_json=JSON_OBJECT('native',TRUE) WHERE post_id=2")
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)
        self.execute("UPDATE community_post_reference SET snapshot_json=JSON_OBJECT('legacy',TRUE) WHERE post_id=2")
        self.execute("INSERT INTO community_post_member_action(post_id,member_id,action_type,request_id,source,create_time,update_time) "
                     "VALUES(1,999,'LIKE','legacy-square-like-30','NATIVE',NOW(),NOW())")
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)

    def test_native_pending_media_with_nullable_post_remains_valid(self):
        columns = [row[0] for row in self.execute('SHOW COLUMNS FROM community_post_media')]
        expressions = ['999' if col == 'id' else 'NULL' if col == 'post_id' else "'native-pending-upload'" if col == 'upload_request_id' else col for col in columns]
        self.execute('INSERT INTO community_post_media (' + ','.join(columns) + ') SELECT ' + ','.join(expressions) + ' FROM community_post_media LIMIT 1')
        from ci.run_migration import assert_community_legacy_owner_preflight
        before = self.snapshot()
        with self.connect() as conn, conn.cursor() as cur:
            assert_community_legacy_owner_preflight(cur, self.schema)
        self.assertEqual(before, self.snapshot())

    def test_nullable_native_action_source_is_valid_but_legacy_collision_is_not(self):
        self.execute("INSERT INTO community_post_member_action(post_id,member_id,action_type,request_id,source,create_time,update_time) "
                     "VALUES(1,1000,'BOOKMARK','native-bookmark',NULL,NOW(),NOW())")
        from ci.run_migration import assert_community_legacy_owner_preflight
        before = self.snapshot()
        with self.connect() as conn, conn.cursor() as cur:
            assert_community_legacy_owner_preflight(cur, self.schema)
        self.assertEqual(before, self.snapshot())
        self.execute('UPDATE community_post_member_action SET source=NULL WHERE member_id=102 AND post_id=1')
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(OLD), before)

    def test_renamed_inline_and_cross_schema_sources_have_same_guard(self):
        self.collision()
        renamed = pathlib.Path(self.private.name) / 'renamed.sql'
        renamed.write_text(OLD.read_text())
        before = self.snapshot()
        self.stopped_without_writes(self.run_sql(renamed), before)
        inline = "INSERT IGNORE INTO community_post_media(owner_member_id,upload_request_id,post_id) SELECT member_id,CONCAT('legacy-square-media-',id,'-1'),id FROM ums_creative_square"
        self.stopped_without_writes(self.run_sql(None, inline=inline), before)
        qualified = "INSERT IGNORE INTO `{0}`.`community_post_media`(owner_member_id,upload_request_id,post_id) SELECT member_id,CONCAT('legacy-square-media-',id,'-1'),id FROM `{0}`.`ums_creative_square`".format(self.schema)
        self.stopped_without_writes(self.run_sql(None, inline=qualified), before)
        self.stopped_without_writes(self.run_sql(None, inline=qualified.replace('`'+self.schema+'`.`community_post_media`', '`unapproved_schema`.`community_post_media`')), before)

    def test_repair_requires_transaction_before_any_write(self):
        before = self.snapshot()
        result = self.run_sql(FIX)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('requires=transaction', result.stderr)
        self.assertEqual(before, self.snapshot())

    def test_failed_post_assertion_rolls_back_count_and_new_action(self):
        before = self.snapshot()
        injection = ("INSERT INTO community_post_comment_action(id,comment_id,member_id,action_type,request_id,create_time) "
                     "VALUES(-1,20,101,'LIKE','rollback-negative-control','2026-10-09');\n")
        text = FIX.read_text().replace('CREATE TEMPORARY TABLE assert_', injection + 'CREATE TEMPORARY TABLE assert_', 1)
        with tempfile.NamedTemporaryFile('w', suffix='.sql') as mutated:
            mutated.write(text)
            mutated.flush()
            result = self.run_sql(pathlib.Path(mutated.name), True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('ROLLBACK OK', result.stdout)
        self.assertEqual(before, self.snapshot())


if __name__ == '__main__':
    unittest.main()

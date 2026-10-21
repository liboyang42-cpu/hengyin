"""Synthetic fixtures only; run on the approved test VPS, never against production."""
import collections
import os
import pathlib
import tempfile
import unittest

import pymysql

from ci import test_community_legacy_owner_mysql_integration as legacy_fixture
from ci.run_migration import assert_mysql_server_provenance, referenced_persistent_tables
from ci.sql_splitter import split_statements

REPO = pathlib.Path(__file__).resolve().parents[1]
FIX = REPO / 'chengyinhub-admin/sql/migration_community_legacy_projection_delta_20261021.sql'
SCHEMA = 'cy_projection_delta_20261021_audit'


class CommunityLegacyProjectionDeltaMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if (os.environ.get('CHENGYIN_MYSQL_IT') != '1'
                or os.environ.get('CHENGYIN_MYSQL_IT_SCHEMA') != SCHEMA):
            raise RuntimeError('explicit approved VPS scratch schema gate required; no skip')
        host = os.environ['CHENGYIN_MYSQL_IT_HOST']
        if host not in {'127.0.0.1', 'localhost', '::1'}:
            raise RuntimeError('approved VPS loopback MySQL required')
        cls.fixture = legacy_fixture.CommunityLegacyOwnerMysqlIntegrationTest()
        fixture = cls.fixture
        fixture.host = host
        fixture.port = int(os.environ['CHENGYIN_MYSQL_IT_PORT'])
        fixture.user = os.environ.get('CHENGYIN_MYSQL_IT_USER', 'root')
        fixture.password = os.environ.get('CHENGYIN_MYSQL_IT_PASSWORD', '')
        fixture.schema = SCHEMA
        fixture.admin = pymysql.connect(host=host, port=fixture.port, user=fixture.user,
                                        password=fixture.password, autocommit=True, connect_timeout=5)
        with fixture.admin.cursor() as cur:
            cur.execute('SELECT VERSION(), @@version_comment')
            assert_mysql_server_provenance(*cur.fetchone())
            cur.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s', (SCHEMA,))
            if cur.fetchone()[0] != 0:
                fixture.admin.close()
                raise RuntimeError('owned scratch schema already exists; refuse to overwrite')
        fixture.private = tempfile.TemporaryDirectory(prefix='cy-projection-delta-it-')
        fixture.config = pathlib.Path(fixture.private.name) / 'config.yml'
        fixture.config.write_text('url: jdbc:mysql://%s:%s/%s\nusername: %s\npassword: ${CHENGYIN_MYSQL_IT_PASSWORD:-}\n'
                                  % (host, fixture.port, SCHEMA, fixture.user))
        fixture.config.chmod(0o600)

    @classmethod
    def tearDownClass(cls):
        fixture = cls.fixture
        if fixture.schema != SCHEMA:
            raise RuntimeError('owned scratch schema changed; refuse cleanup')
        with fixture.admin.cursor() as cur:
            cur.execute('DROP DATABASE `' + SCHEMA + '`')
            cur.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s', (SCHEMA,))
            if cur.fetchone()[0] != 0:
                raise RuntimeError('owned scratch cleanup readback failed')
        fixture.admin.close()
        fixture.private.cleanup()

    def setUp(self):
        # Reuse the retained prerequisite/platform DDL and versioned runner, not a model schema.
        self.fixture.setUp()
        self.sql = self.fixture.execute
        existing = []
        for offset in range(34):
            deleted = 0 if offset<19 else 1 if offset<32 else 2
            existing.append("(%s,101,'synthetic retained',NULL,NULL,NULL,0,NULL,1,%s,'2026-09-01',NULL)"
                            % (100+offset, deleted))
        self.sql('INSERT INTO ums_creative_square VALUES' + ','.join(existing))
        self.passed(self.fixture.run_sql(legacy_fixture.OLD))
        self.passed(self.fixture.run_sql(legacy_fixture.SQL / 'migration_community_legacy_projection_delflag_20260919.sql'))
        self.assertEqual(((38,),), self.sql('SELECT COUNT(*) FROM community_post'))
        self.sql("UPDATE community_post SET body='existing edited text' WHERE id=1")
        self.sql("INSERT INTO ums_creative_square VALUES"
                 "(501,201,'synthetic topic one','image-one;image-two',NULL,701,2,NULL,1,0,'2026-10-10',NULL),"
                 "(502,202,'synthetic topic two','image-three',NULL,702,2,NULL,1,0,'2026-10-10',NULL),"
                 "(503,203,'synthetic topic three',NULL,NULL,703,2,NULL,1,0,'2026-10-10',NULL),"
                 "(504,204,'synthetic deleted','deleted-image',NULL,NULL,0,NULL,1,2,'2026-10-10','2026-10-11')")

    def snapshot(self):
        return {name: tuple(rows) for name, (_, rows) in self.full_snapshot().items()}

    def full_snapshot(self):
        return {row[0]: (None, self.sql('SELECT * FROM `' + row[0] + '`'))
                for row in self.sql('SHOW TABLES')}

    def run_delta(self, path=FIX, transaction=True):
        return self.fixture.run_sql(path, transaction=transaction)

    def passed(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def rejected_unchanged(self, result, before):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(before, self.snapshot())

    def test_missing_posts_children_and_search_then_exact_noop_replay(self):
        before = self.snapshot()
        old_assertion = ("SELECT (SELECT COUNT(*) FROM ums_creative_square WHERE member_id IS NOT NULL "
                         "AND COALESCE(del_flag,0)=0 AND status=1)=(SELECT COUNT(*) FROM community_post "
                         "WHERE client_request_id LIKE 'legacy-square-%' AND lifecycle='PUBLISHED')")
        self.assertEqual(((0,),), self.sql(old_assertion))
        self.passed(self.run_delta())
        self.assertEqual(((1,),), self.sql(old_assertion))
        after = self.snapshot()
        for table, rows in before.items():
            self.assertFalse(collections.Counter(rows) - collections.Counter(after[table]), table)
        self.assertEqual(((501,201,'legacy-square-501','PUBLISHED',1,1,1),
                          (502,202,'legacy-square-502','PUBLISHED',1,1,1),
                          (503,203,'legacy-square-503','PUBLISHED',1,1,1),
                          (504,204,'legacy-square-504','DELETED',0,0,0)),
                         self.sql('SELECT id,author_id,client_request_id,lifecycle,recommendation_eligible,'
                                  'search_eligible,interaction_enabled FROM community_post WHERE id>=501 ORDER BY id'))
        self.assertEqual(((501,'image-one',0,'PASS','ACTIVE'),(501,'image-two',1,'PASS','ACTIVE'),
                          (502,'image-three',0,'PASS','ACTIVE'),(504,'deleted-image',0,'PENDING','ORPHANED')),
                         self.sql('SELECT post_id,object_key,sort_order,review_state,retention_state '
                                  'FROM community_post_media WHERE post_id>=501 ORDER BY post_id,sort_order'))
        self.assertEqual(((501,'TOPIC',701),(502,'TOPIC',702),(503,'TOPIC',703)),
                         self.sql('SELECT post_id,reference_type,reference_id FROM community_post_reference '
                                  'WHERE post_id>=501 ORDER BY post_id'))
        self.assertEqual(((501,'VISIBLE'),(502,'VISIBLE'),(503,'VISIBLE'),(504,'DELETED')),
                         self.sql("SELECT document_id,visibility_state FROM community_search_document "
                                  "WHERE document_type='POST' AND document_id>=501 ORDER BY document_id"))
        self.assertEqual(((0,0,0,0),), self.sql('SELECT SUM(like_count),SUM(comment_count),SUM(bookmark_count),'
                                             'SUM(share_count) FROM community_post WHERE id>=501'))
        self.passed(self.run_delta())
        self.assertEqual(after, self.snapshot())

    def test_identity_collision_and_existing_orphan_reject_before_persistent_changes(self):
        mutations = (
            "UPDATE community_post SET author_id=999 WHERE id=1",
            "UPDATE community_post SET client_request_id=CONCAT('LEGACY-','SQUARE-',1) WHERE id=1",
            "UPDATE community_post SET author_id=201,client_request_id=CONCAT('legacy-square-',501) WHERE id=1",
            "INSERT INTO community_post_member_action(post_id,member_id,action_type,request_id,source,create_time,update_time) "
            "VALUES(501,999,'BOOKMARK','synthetic-orphan','NATIVE',NOW(),NOW())",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.setUp()
                self.sql(mutation)
                self.rejected_unchanged(self.run_delta(), self.snapshot())

    def test_unsupported_source_associations_reject_without_partial_delta(self):
        mutations = (
            "UPDATE ums_creative_square SET file_url='synthetic-file' WHERE id=501",
            "UPDATE ums_creative_square SET route_preview_img='synthetic-map' WHERE id=501",
            "UPDATE ums_creative_square SET data_type=3,data_id=10 WHERE id=501",
            "UPDATE ums_creative_square SET del_flag=NULL WHERE id=501",
            "INSERT INTO ums_comment VALUES(520,201,3,501,'synthetic comment',0,1,0,NOW(),NULL)",
            "INSERT INTO ums_creative_square_like VALUES(530,202,501,1,NOW(),NULL)",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.setUp()
                self.sql(mutation)
                self.rejected_unchanged(self.run_delta(), self.snapshot())

    def test_deleted_pending_removed_and_non_author_records_never_become_public(self):
        self.sql('UPDATE ums_creative_square SET del_flag=1 WHERE id=501')
        self.sql('UPDATE ums_creative_square SET status=0 WHERE id=502')
        self.sql('UPDATE ums_creative_square SET member_id=NULL WHERE id=503')
        self.passed(self.run_delta())
        self.assertEqual(((501,'REMOVED',0),(502,'CHECKING',0),(504,'DELETED',0)),
                         self.sql('SELECT id,lifecycle,search_eligible FROM community_post WHERE id>=501 ORDER BY id'))
        self.assertEqual(((501,'HIDDEN'),(502,'HIDDEN'),(504,'DELETED')),
                         self.sql("SELECT document_id,visibility_state FROM community_search_document "
                                  "WHERE document_type='POST' AND document_id>=501 ORDER BY document_id"))

    def test_images_escape_and_late_unique_failure_roll_back_all_posts(self):
        self.sql('UPDATE ums_creative_square SET pics=%s WHERE id=501', ('image-"quoted";path\\image',))
        self.passed(self.run_delta())
        self.assertEqual((('image-"quoted"',),('path\\image',)),
                         self.sql('SELECT object_key FROM community_post_media WHERE post_id=501 ORDER BY sort_order'))
        self.setUp()
        self.sql("UPDATE community_post_media SET owner_member_id=201,upload_request_id='legacy-square-media-501-1',"
                 "post_id=NULL WHERE id=(SELECT picked.id FROM (SELECT MIN(id) id FROM community_post_media) picked)")
        self.rejected_unchanged(self.run_delta(), self.snapshot())

    def test_missing_transaction_and_broken_search_mutation_are_red(self):
        before = self.snapshot()
        self.rejected_unchanged(self.run_delta(transaction=False), before)
        original = FIX.read_text()
        old = "WHEN p.lifecycle='PUBLISHED' AND p.search_eligible=1 THEN 'VISIBLE' ELSE 'HIDDEN' END,p.version,NOW()"
        self.assertEqual(1, original.count(old))
        changed = pathlib.Path(self.fixture.private.name) / 'broken-search.sql'
        changed.write_text(original.replace(old, "WHEN p.lifecycle='PUBLISHED' AND p.search_eligible=1 THEN 'HIDDEN' ELSE 'HIDDEN' END,p.version,NOW()"))
        self.rejected_unchanged(self.run_delta(changed), before)

    def test_all_persistent_tables_are_checked_and_non_innodb_target_rejected_before_writes(self):
        expected = {
            'community_guideline_version', 'community_post', 'community_post_comment',
            'community_post_media', 'community_post_member_action', 'community_post_reference',
            'community_search_document', 'ums_comment', 'ums_creative_square', 'ums_creative_square_like',
        }
        self.assertEqual(expected, set(referenced_persistent_tables(split_statements(FIX.read_text()), SCHEMA)))
        self.sql('ALTER TABLE community_search_document ENGINE=MyISAM')
        before = self.snapshot()
        result = self.run_delta()
        self.rejected_unchanged(result, before)
        self.assertIn('非 InnoDB', result.stdout + result.stderr)
        self.assertNotIn('[1/', result.stdout)


if __name__ == '__main__':
    unittest.main()

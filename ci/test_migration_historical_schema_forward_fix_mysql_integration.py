import os
import pathlib
import unittest
import uuid

import pymysql

from ci.run_migration import assert_mysql_server_provenance
from scripts.e2e.build_schema import BASELINE, run_file


REPO = pathlib.Path(__file__).resolve().parent.parent
MIGRATION = REPO / "chengyinhub-admin/sql/migration_zz_20260921_historical_schema_forward_fix.sql"
SCHEMA_PREFIX = "cy_historical_forward_it_"


class HistoricalSchemaForwardFixMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError("MySQL IT fail closed: CHENGYIN_MYSQL_IT=1 is required")
        cls.host = os.environ["CHENGYIN_MYSQL_IT_HOST"]
        cls.port = int(os.environ["CHENGYIN_MYSQL_IT_PORT"])
        cls.user = os.environ.get("CHENGYIN_MYSQL_IT_USER", "root")
        cls.password = os.environ.get("CHENGYIN_MYSQL_IT_PASSWORD", "")
        if cls.host not in {"127.0.0.1", "localhost", "::1"}:
            raise RuntimeError("MySQL IT only accepts an explicit localhost scratch server")
        cls.schema = SCHEMA_PREFIX + uuid.uuid4().hex[:12]
        cls.admin = pymysql.connect(host=cls.host, port=cls.port, user=cls.user,
                                    password=cls.password, autocommit=True)
        with cls.admin.cursor() as cursor:
            cursor.execute("SELECT VERSION(), @@version_comment")
            version, comment = cursor.fetchone()
            assert_mysql_server_provenance(version, comment)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "admin"):
            if not cls.schema.startswith(SCHEMA_PREFIX):
                raise RuntimeError("refusing to drop an unvalidated scratch schema")
            with cls.admin.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS `{cls.schema}`")
                cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s",
                               (cls.schema,))
                if cursor.fetchone()[0] != 0:
                    raise RuntimeError("scratch schema cleanup readback failed")
            cls.admin.close()

    def setUp(self):
        with self.admin.cursor() as cursor:
            cursor.execute(f"DROP DATABASE IF EXISTS `{self.schema}`")
            cursor.execute(f"CREATE DATABASE `{self.schema}` CHARACTER SET utf8mb4")
        connection = self.connect()
        try:
            results = []
            self.assertTrue(run_file(connection.cursor(), BASELINE, "baseline", results, True))
            self.assertEqual([], results)
        finally:
            connection.close()

    def connect(self):
        return pymysql.connect(host=self.host, port=self.port, user=self.user,
                               password=self.password, database=self.schema, autocommit=True)

    def run_migration(self):
        connection = self.connect()
        try:
            results = []
            ok = run_file(connection.cursor(), MIGRATION, "historical-forward-fix", results, False)
            return ok, results
        finally:
            connection.close()

    def test_fresh_and_existing_shapes_converge_and_replay(self):
        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("INSERT INTO biz_event_log(event_name,member_id,idempotency_key,occurred_at) "
                               "VALUES ('open',NULL,'anon-1',NOW()),('open',7,'member-1',NOW())")
        finally:
            connection.close()

        self.assertEqual((True, []), self.run_migration())
        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("UPDATE biz_event_log SET actor_scope='WRONG' WHERE member_id=7")
        finally:
            connection.close()
        self.assertEqual((True, []), self.run_migration())

        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT actor_scope FROM biz_event_log ORDER BY id")
                self.assertEqual((("ANONYMOUS",), ("MEMBER:7",)), cursor.fetchall())
        finally:
            connection.close()

    def test_duplicate_business_rows_fail_before_schema_or_data_mutation(self):
        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("INSERT INTO biz_event_log(event_name,member_id,idempotency_key,occurred_at) "
                               "VALUES ('dup',NULL,'same',NOW()),('dup',NULL,'same',NOW())")
        finally:
            connection.close()

        ok, results = self.run_migration()
        self.assertFalse(ok)
        self.assertEqual("ASSERT", results[0][2])
        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT COUNT(*) FROM biz_event_log")
                self.assertEqual(2, cursor.fetchone()[0])
                cursor.execute("SELECT COUNT(*) FROM information_schema.columns "
                               "WHERE table_schema=%s AND table_name='biz_event_log' "
                               "AND column_name='actor_scope'", (self.schema,))
                self.assertEqual(0, cursor.fetchone()[0])
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()

"""Published offer/application projections require a real forward schema migration."""
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET

import pymysql

from ci.run_migration import assert_mysql_server_provenance
from ci.sql_splitter import split_statements

REPO = pathlib.Path(__file__).resolve().parent.parent
SQL_DIR = REPO / "chengyinhub-admin/sql"
MIGRATION = SQL_DIR / "migration_20261003_merchant_offer_withdrawal_columns.sql"
ROUND_MIGRATION = SQL_DIR / "migration_20261024_d_chapter_application_round.sql"
PREFIX = "cy_offer_withdraw_columns_it_"
TABLES = ("merchant_chapter_offer", "merchant_chapter_application")
TARGET_COLUMNS = ("withdraw_reason", "withdraw_required", "replacement_of_offer_id")


class MerchantOfferWithdrawalColumnsMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError("MySQL IT fail closed: CHENGYIN_MYSQL_IT=1 is required")
        cls.host = os.environ["CHENGYIN_MYSQL_IT_HOST"]
        cls.port = int(os.environ["CHENGYIN_MYSQL_IT_PORT"])
        cls.user = os.environ.get("CHENGYIN_MYSQL_IT_USER", "root")
        cls.password = os.environ.get("CHENGYIN_MYSQL_IT_PASSWORD", "")
        if cls.host not in {"127.0.0.1", "localhost", "::1"}:
            raise RuntimeError("MySQL IT only accepts explicit localhost scratch")
        cls.schema = PREFIX + uuid.uuid4().hex[:12]
        cls.admin = pymysql.connect(host=cls.host, port=cls.port, user=cls.user,
                                    password=cls.password, autocommit=True)
        with cls.admin.cursor() as cursor:
            cursor.execute("SELECT VERSION(), @@version_comment")
            version, comment = cursor.fetchone()
            assert_mysql_server_provenance(version, comment)
        cls.config = tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False)
        cls.config.write("url: jdbc:mysql://%s:%s/%s\nusername: %s\n"
                         "password: ${CHENGYIN_MYSQL_IT_PASSWORD:-}\n"
                         % (cls.host, cls.port, cls.schema, cls.user))
        cls.config.close()
        print("acceptance tier: Oracle MySQL %s; localhost scratch %s:%s/%s"
              % (version, cls.host, cls.port, cls.schema))

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "admin"):
            if not cls.schema.startswith(PREFIX):
                raise RuntimeError("refusing to drop an unvalidated scratch schema")
            with cls.admin.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS `{cls.schema}`")
                cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s",
                               (cls.schema,))
                if cursor.fetchone()[0] != 0:
                    raise RuntimeError("scratch cleanup readback failed")
            cls.admin.close()
        if hasattr(cls, "config"):
            pathlib.Path(cls.config.name).unlink(missing_ok=True)

    def connect(self):
        return pymysql.connect(host=self.host, port=self.port, user=self.user,
                               password=self.password, database=self.schema, autocommit=True)

    def setUp(self):
        with self.admin.cursor() as cursor:
            cursor.execute(f"DROP DATABASE IF EXISTS `{self.schema}`")
            cursor.execute(f"CREATE DATABASE `{self.schema}` CHARACTER SET utf8mb4")
        with self.connect() as connection, connection.cursor() as cursor:
            # Execute exact historical statements, not a hand-built already-migrated fixture.
            sources = (
                ("migration_perk_ledger_20260716.sql", r"^CREATE TABLE IF NOT EXISTS merchant_chapter_offer\s*\("),
                ("migration_chapter_recruitment_20260722.sql", r"^CREATE TABLE IF NOT EXISTS merchant_chapter_application\s*\("),
                ("migration_terms_mode_20260716.sql", r"^ALTER TABLE merchant_chapter_offer\s"),
                ("migration_chapter_merchant_node.sql", r"^ALTER TABLE `(?:merchant_chapter_offer|merchant_chapter_application)`\s"),
                ("migration_club_direct_merchant_receivable_20261004.sql",
                 r"^(?:ALTER TABLE merchant_chapter_offer|CREATE TABLE club_direct_merchant_receivable)\s"),
            )
            executed = []
            for filename, pattern in sources:
                matching = [sql for sql in split_statements((SQL_DIR / filename).read_text())
                            if re.search(pattern, sql, re.I)]
                self.assertTrue(matching, filename)
                for sql in matching:
                    cursor.execute(sql)
                    executed.append(sql)
            self.assertEqual(10, len(executed))  # 2 CREATE + 4 terms ALTER + 2 merchant-node ALTER + 2 club-direct DDL.
            cursor.execute("INSERT INTO merchant_chapter_offer "
                           "(id,topic_id,chapter_id,merchant_id,perk_id,quota_total,quota_used,status) "
                           "VALUES (1,9,10,20,11,4,1,1)")
            cursor.execute("INSERT INTO merchant_chapter_offer "
                           "(id,topic_id,chapter_id,merchant_id,terms_mode,per_head_fee,status) "
                           "VALUES (2,9,11,21,'REVSHARE',2.50,0)")
            cursor.execute("INSERT INTO merchant_chapter_application "
                           "(id,topic_id,chapter_id,merchant_id,status,source,message,audit_member_id) "
                           "VALUES (1,9,10,20,1,1,'existing invitation',30),"
                           "(2,9,11,21,2,0,'existing rejected application',30)")
        self.before = self.snapshot()
        # The current application projection also needs the independent round migration.
        # Apply its canonical SQL after history exists, while withdrawal columns remain absent.
        self.migrate(ROUND_MIGRATION)
        self.assertEqual(self.before, self.snapshot(self.before))
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT id,application_round FROM merchant_chapter_application ORDER BY id")
            self.assertEqual(((1, None), (2, None)), cursor.fetchall())

    def snapshot(self, columns=None):
        rows = {}
        with self.connect() as connection, connection.cursor() as cursor:
            for table in TABLES:
                select = "*" if columns is None else ",".join("`%s`" % key for key in columns[table][0])
                cursor.execute("SELECT %s FROM %s ORDER BY id" % (select, table))
                rows[table] = (tuple(item[0] for item in cursor.description), cursor.fetchall())
        return rows

    def run_migration(self, migration=MIGRATION):
        env = os.environ.copy()
        env["CHENGYIN_DB_SSH_HOST"] = "local"
        env["CHENGYIN_MYSQL_IT_PASSWORD"] = self.password
        return subprocess.run([sys.executable, str(REPO / "ci/run_migration.py"),
                               "--config", self.config.name, "--sql", str(migration)],
                              cwd=REPO, env=env, capture_output=True, text=True, timeout=30)

    def migrate(self, migration=MIGRATION):
        result = self.run_migration(migration)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def projection(self, cursor, mapper, sql_id):
        xml = REPO / "chengyinhub-system/src/main/resources/mapper/business" / (mapper + ".xml")
        element = ET.parse(xml).getroot().find("sql[@id='%s']" % sql_id)
        self.assertIsNotNone(element)
        self.assertEqual([], list(element), "Use the actual published static projection, not a replica")
        cursor.execute(element.text + " ORDER BY id")
        return tuple(item[0] for item in cursor.description), cursor.fetchall()

    def new_columns(self):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT table_name,column_name,column_type,is_nullable,column_default "
                           "FROM information_schema.columns WHERE table_schema=DATABASE() "
                           "AND table_name IN (%s,%s) AND column_name IN (%s,%s,%s) "
                           "ORDER BY table_name,column_name", TABLES + TARGET_COLUMNS)
            return cursor.fetchall()

    def test_legacy_projection_fails_then_migration_preserves_all_history(self):
        with self.connect() as connection, connection.cursor() as cursor:
            for mapper, sql_id in (("MerchantChapterOfferMapper", "selectMerchantChapterOfferVo"),
                                   ("MerchantChapterApplicationMapper", "selectMerchantChapterApplicationVo")):
                with self.assertRaises(pymysql.err.OperationalError) as raised:
                    self.projection(cursor, mapper, sql_id)
                self.assertEqual(1054, raised.exception.args[0])
                if mapper == "MerchantChapterApplicationMapper":
                    self.assertIn("replacement_of_offer_id", str(raised.exception))
        self.migrate()
        self.assertEqual(self.before, self.snapshot(self.before))
        self.assertEqual((
            (TABLES[1], "replacement_of_offer_id", "bigint", "YES", None),
            (TABLES[0], "replacement_of_offer_id", "bigint", "YES", None),
            (TABLES[0], "withdraw_reason", "varchar(500)", "YES", None),
            (TABLES[0], "withdraw_required", "tinyint(1)", "YES", None),
        ), self.new_columns())
        with self.connect() as connection, connection.cursor() as cursor:
            for mapper, sql_id in (("MerchantChapterOfferMapper", "selectMerchantChapterOfferVo"),
                                   ("MerchantChapterApplicationMapper", "selectMerchantChapterApplicationVo")):
                columns, rows = self.projection(cursor, mapper, sql_id)
                self.assertEqual(2, len(rows))
                for row in rows:
                    for name in set(TARGET_COLUMNS).intersection(columns):
                        self.assertIsNone(row[columns.index(name)])

    def test_explicit_snapshots_and_replacement_survive_replay(self):
        self.migrate()
        reason = "退出" * 250
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("UPDATE merchant_chapter_offer SET status=0,withdraw_reason=%s,"
                           "withdraw_required=1 WHERE id=1", (reason,))
            cursor.execute("UPDATE merchant_chapter_offer SET withdraw_required=0,"
                           "replacement_of_offer_id=1 WHERE id=2")
            cursor.execute("UPDATE merchant_chapter_application SET replacement_of_offer_id=1 WHERE id=2")
        expected = self.snapshot()
        self.migrate()
        self.assertEqual(expected, self.snapshot())
        with self.connect() as connection, connection.cursor() as cursor:
            columns, rows = self.projection(cursor, "MerchantChapterOfferMapper", "selectMerchantChapterOfferVo")
            self.assertEqual(reason, rows[0][columns.index("withdraw_reason")])
            self.assertEqual(1, rows[0][columns.index("withdraw_required")])
            self.assertEqual(0, rows[1][columns.index("withdraw_required")])
            self.assertEqual(1, rows[1][columns.index("replacement_of_offer_id")])
            columns, rows = self.projection(cursor, "MerchantChapterApplicationMapper", "selectMerchantChapterApplicationVo")
            self.assertEqual(1, rows[1][columns.index("replacement_of_offer_id")])

    def test_partial_existing_column_resumes_without_overwriting_values(self):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_reason VARCHAR(500) NULL")
            cursor.execute("UPDATE merchant_chapter_offer SET withdraw_reason='preserved' WHERE id=2")
        expected = self.snapshot()
        self.migrate()
        self.assertEqual(expected, self.snapshot(expected))
        self.assertEqual(4, len(self.new_columns()))

    def test_wrong_existing_shapes_fail_before_any_ddl_or_data_change(self):
        shapes = ((TABLES[0], "withdraw_reason VARCHAR(50) NULL"),
                  (TABLES[0], "withdraw_required TINYINT(1) NULL DEFAULT 0"),
                  (TABLES[0], "replacement_of_offer_id BIGINT UNSIGNED NULL"),
                  (TABLES[1], "replacement_of_offer_id BIGINT NOT NULL DEFAULT 0"))
        for table, definition in shapes:
            with self.subTest(table=table, definition=definition):
                self.setUp()
                with self.connect() as connection, connection.cursor() as cursor:
                    cursor.execute("ALTER TABLE %s ADD COLUMN %s" % (table, definition))
                expected = self.snapshot()
                columns = self.new_columns()
                result = self.run_migration()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("[1/22]", result.stdout)
                self.assertIn("(3141,", result.stdout + result.stderr)
                self.assertEqual(columns, self.new_columns())
                self.assertEqual(expected, self.snapshot())

    def test_missing_prerequisite_table_fails_before_offer_ddl(self):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("DROP TABLE merchant_chapter_application")
        result = self.run_migration()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual((), self.new_columns())

    def test_dependency_registration_matches_real_ddl_sources(self):
        dependencies = json.loads((REPO / "ci/migration-dependencies.json").read_text())["dependencies"]
        self.assertEqual(["perk_ledger_20260716", "terms_mode_20260716", "chapter_merchant_node",
                          "chapter_recruitment_20260722"],
                         dependencies["20261003_merchant_offer_withdrawal_columns"])


if __name__ == "__main__":
    unittest.main()

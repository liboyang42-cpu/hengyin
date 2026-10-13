import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import uuid
from decimal import Decimal

import pymysql

from ci.run_migration import assert_mysql_server_provenance
from ci.sql_splitter import split_statements


RUNNER = pathlib.Path(__file__).with_name("run_migration.py")
REPAIR = pathlib.Path(
    "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_repair_20260821.sql")
SETUP_SQL = (
    pathlib.Path("chengyinhub-admin/src/test/resources/it-schema/explore-alliance-it-schema.sql"),
    pathlib.Path("chengyinhub-admin/sql/migration_product_role_20260716.sql"),
    pathlib.Path("chengyinhub-admin/sql/migration_topic_template_meta_20260726.sql"),
    pathlib.Path("chengyinhub-admin/sql/migration_circle_theme_batch2_20260821.sql"),
)
SEED = pathlib.Path(
    "chengyinhub-admin/sql/migration_seed_circle_theme_batch2_20260821.sql")
SCRATCH_SCHEMA_PREFIX = "cy_circle_seed_repair_it_"


class CircleThemeSeedRepairMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError(
                "MySQL IT fail closed: set CHENGYIN_MYSQL_IT=1 only in the explicit CI gate")
        cls.repo = pathlib.Path(os.environ.get("CHENGYIN_REPO", os.getcwd())).resolve()
        missing = [path for path in (*SETUP_SQL, SEED, REPAIR)
                   if not (cls.repo / path).is_file()]
        if missing:
            raise RuntimeError("MySQL IT fail closed: missing SQL: " + ", ".join(map(str, missing)))

        cls.host = os.environ["CHENGYIN_MYSQL_IT_HOST"]
        cls.port = int(os.environ["CHENGYIN_MYSQL_IT_PORT"])
        cls.user = os.environ.get("CHENGYIN_MYSQL_IT_USER", "root")
        cls.password = os.environ.get("CHENGYIN_MYSQL_IT_PASSWORD", "")
        if cls.host not in {"127.0.0.1", "localhost", "::1"}:
            raise RuntimeError("MySQL IT fail closed: only localhost scratch is allowed")

        cls.schema = SCRATCH_SCHEMA_PREFIX + uuid.uuid4().hex[:12]
        cls.admin = pymysql.connect(
            host=cls.host, port=cls.port, user=cls.user, password=cls.password,
            connect_timeout=5, autocommit=True)
        with cls.admin.cursor() as cursor:
            cursor.execute("SELECT VERSION(), @@version_comment")
            version, version_comment = cursor.fetchone()
            assert_mysql_server_provenance(version, version_comment)

        cls.config_file = tempfile.NamedTemporaryFile(
            "w", suffix=".yml", encoding="utf-8", delete=False)
        cls.config_file.write(
            "url: jdbc:mysql://%s:%s/%s\nusername: %s\npassword: ${CHENGYIN_MYSQL_IT_PASSWORD:-}\n"
            % (cls.host, cls.port, cls.schema, cls.user))
        cls.config_file.close()

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "admin"):
            if not cls.schema.startswith(SCRATCH_SCHEMA_PREFIX):
                raise RuntimeError("refusing to drop an unvalidated scratch schema")
            with cls.admin.cursor() as cursor:
                cursor.execute("DROP DATABASE IF EXISTS `%s`" % cls.schema)
            cls.admin.close()
        if hasattr(cls, "config_file"):
            pathlib.Path(cls.config_file.name).unlink(missing_ok=True)

    def setUp(self):
        with self.admin.cursor() as cursor:
            cursor.execute("DROP DATABASE IF EXISTS `%s`" % self.schema)
            cursor.execute(
                "CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
                % self.schema)
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                for path in SETUP_SQL:
                    self.apply_sql(cursor, self.repo / path)
                cursor.execute(
                    "CREATE TABLE sys_category ("
                    "id BIGINT NOT NULL AUTO_INCREMENT, category_name VARCHAR(64) NOT NULL, "
                    "type INT NOT NULL, status TINYINT NOT NULL, del_flag TINYINT NOT NULL, "
                    "PRIMARY KEY (id), UNIQUE KEY uk_category_name (category_name)) ENGINE=InnoDB")
                cursor.execute(
                    "INSERT INTO sys_category(category_name,type,status,del_flag) VALUES "
                    "('美食烹饪',1,1,0),('运动健身',1,1,0),('娱乐达人',1,1,0),"
                    "('生活美学',1,1,0),('礼品纪念',1,1,0)")
        finally:
            connection.close()

        seeded = self.run_migration(SEED)
        self.assertEqual(0, seeded.returncode, seeded.stdout + seeded.stderr)
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE cms_topic_chapter SET category_id=(SELECT id FROM sys_category "
                    "WHERE category_name='娱乐达人') WHERE circle_candidate_code IN "
                    "('A5','H6','H7','K7','K8','O8') AND topic_id IN "
                    "(SELECT id FROM cms_topic WHERE is_template=1 AND del_flag=0)")
                cursor.execute(
                    "UPDATE cms_topic_chapter_template_meta "
                    "SET effort_prep_hours=0.17, update_time='2026-08-22 00:56:52' "
                    "WHERE topic_id IN (SELECT id FROM cms_topic "
                    "WHERE is_template=1 AND del_flag=0)")
        finally:
            connection.close()

    def connect_schema(self):
        return pymysql.connect(
            host=self.host, port=self.port, user=self.user, password=self.password,
            database=self.schema, connect_timeout=5, autocommit=True)

    @staticmethod
    def apply_sql(cursor, path):
        for statement in split_statements(path.read_text(encoding="utf-8")):
            cursor.execute(statement)

    def run_migration(self, path):
        env = os.environ.copy()
        env["CHENGYIN_DB_SSH_HOST"] = "local"
        env["CHENGYIN_MYSQL_IT_PASSWORD"] = self.password
        return subprocess.run(
            [sys.executable, str(RUNNER), "--config", self.config_file.name,
             "--sql", str(self.repo / path), "--transaction"],
            cwd=self.repo, env=env, text=True, capture_output=True, timeout=30)

    def read_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT ch.circle_candidate_code,c.category_name,m.effort_prep_hours,m.update_time "
                    "FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id "
                    "JOIN sys_category c ON c.id=ch.category_id "
                    "JOIN cms_topic_chapter_template_meta m ON m.chapter_id=ch.id "
                    "WHERE t.is_template=1 AND t.del_flag=0 ORDER BY ch.circle_candidate_code")
                return tuple(cursor.fetchall())
        finally:
            connection.close()

    def test_exact_preimage_commits_without_changing_update_time(self):
        before = self.read_state()

        result = self.run_migration(REPAIR)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("COMMIT OK", result.stdout)
        after = self.read_state()
        self.assertEqual(40, len(after))
        self.assertTrue(all(row[2] == Decimal("0.16") for row in after))
        self.assertEqual(tuple(row[3] for row in before), tuple(row[3] for row in after))
        expected = {"A5": "生活美学", "H6": "礼品纪念", "H7": "礼品纪念",
                    "K7": "礼品纪念", "K8": "生活美学", "O8": "礼品纪念"}
        actual = {code: category for code, category, _effort, _updated in after}
        for code, category in expected.items():
            self.assertEqual(category, actual[code])

    def test_category_drift_rejects_and_preserves_all_rows(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE cms_topic_chapter SET category_id=(SELECT id FROM sys_category "
                    "WHERE category_name='娱乐达人') WHERE circle_candidate_code='A1'")
        finally:
            connection.close()
        before = self.read_state()

        result = self.run_migration(REPAIR)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("ROLLBACK OK", result.stdout)
        self.assertEqual(before, self.read_state())

    def test_effort_drift_rejects_and_preserves_all_rows(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE cms_topic_chapter_template_meta m JOIN cms_topic_chapter ch "
                    "ON ch.id=m.chapter_id SET m.effort_prep_hours=0.18 "
                    "WHERE ch.circle_candidate_code='A1'")
        finally:
            connection.close()
        before = self.read_state()

        result = self.run_migration(REPAIR)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("ROLLBACK OK", result.stdout)
        self.assertEqual(before, self.read_state())

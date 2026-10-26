"""Explicit CI-only MySQL acceptance; one class owns one randomly named scratch schema."""
import copy
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

import pymysql

from ci import o14_backfill_rollback as tool
from ci.run_migration import assert_mysql_server_provenance
from ci.sql_splitter import RUNNER_ASSERTION, split_statements

REPO = Path(__file__).resolve().parent.parent
SQL = REPO / "chengyinhub-admin/sql"
PREFIX = "cy_o14_rollback_it_"
SNAPSHOT_TABLES = ("club", "cms_topic", "cms_activity", tool.LOG, "sys_job")


class O14BackfillRollbackMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError("MySQL IT fail closed: explicit CHENGYIN_MYSQL_IT=1 required")
        required = ("CHENGYIN_MYSQL_IT_HOST", "CHENGYIN_MYSQL_IT_PORT",
                    "CHENGYIN_MYSQL_IT_USER", "CHENGYIN_MYSQL_IT_PASSWORD")
        if any(name not in os.environ for name in required):
            raise RuntimeError("MySQL IT fail closed: explicit target and credentials required")
        cls.host = os.environ[required[0]]
        cls.port = int(os.environ[required[1]])
        cls.user = os.environ[required[2]]
        cls.password = os.environ[required[3]]
        if cls.host not in {"127.0.0.1", "localhost", "::1"}:
            raise RuntimeError("MySQL IT only accepts explicit loopback scratch")
        cls.schema = PREFIX + uuid.uuid4().hex[:12]
        cls.admin = cls.connect(database=None)
        cls.addClassCleanup(cls.admin.close)
        with cls.admin.cursor() as cursor:
            cursor.execute("SELECT VERSION(),@@version_comment,@@server_uuid")
            version, comment, cls.server_uuid = cursor.fetchone()
            assert_mysql_server_provenance(version, comment)
            # No IF NOT EXISTS: an existing database is never adopted by this suite.
            cursor.execute("CREATE DATABASE `%s` CHARACTER SET utf8mb4 "
                           "COLLATE utf8mb4_general_ci" % cls.schema)
        cls.addClassCleanup(cls.drop_schema)
        print("acceptance tier: Oracle MySQL 8.0.45; isolated schema " + cls.schema)

    @classmethod
    def drop_schema(cls):
        if not cls.schema.startswith(PREFIX):
            raise RuntimeError("refusing cleanup of unowned database")
        with cls.admin.cursor() as cursor:
            cursor.execute("DROP DATABASE `%s`" % cls.schema)
            cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s",
                           (cls.schema,))
            if cursor.fetchone() != (0,):
                raise RuntimeError("scratch cleanup readback failed")

    @classmethod
    def connect(cls, database=True):
        return pymysql.connect(host=cls.host, port=cls.port, user=cls.user, password=cls.password,
                               database=cls.schema if database is True else database,
                               charset="utf8mb4", autocommit=True, connect_timeout=5,
                               read_timeout=15, write_timeout=15)

    def migration(self, cursor, filename):
        for statement in split_statements((SQL / filename).read_text(encoding="utf-8")):
            cursor.execute(statement)
            if RUNNER_ASSERTION.search(statement):
                self.assertEqual((1,), cursor.fetchone(), filename)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "manifest.json"
        self.before_path = Path(self.directory.name) / "before.json"
        with self.connect() as connection, connection.cursor() as cursor:
            for table in SNAPSHOT_TABLES:
                cursor.execute("DROP TABLE IF EXISTS `%s`" % table)
            cursor.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, city VARCHAR(128)) ENGINE=InnoDB")
            cursor.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY,club_id BIGINT,"
                           "note VARCHAR(32)) ENGINE=InnoDB")
            cursor.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY,club_id BIGINT,"
                           "topic_id BIGINT,note VARCHAR(32)) ENGINE=InnoDB")
            cursor.execute("CREATE TABLE sys_job (id BIGINT PRIMARY KEY,status CHAR(1)) ENGINE=InnoDB")
            cursor.execute("INSERT INTO sys_job VALUES (1,'1')")
            cursor.execute("INSERT INTO club VALUES (1,'上海市'),(2,'广州市')")
            cursor.execute("INSERT INTO cms_topic VALUES (11,1,'target'),(21,2,'outside'),(91,1,'manual')")
            cursor.execute("INSERT INTO cms_activity VALUES "
                           "(12,NULL,11,'target'),(22,2,21,'outside'),(92,1,11,'manual')")
            # Apply the shipped column/log contracts before the shipped backfill, not a replica.
            for filename in ("migration_o14_cms_topic_city_code_20261022.sql",
                             "migration_o14_cms_activity_city_code_20261022.sql",
                             "migration_o14_city_code_backfill_log_20261022.sql"):
                self.migration(cursor, filename)
            cursor.execute("UPDATE cms_topic SET city_code='320500' WHERE id=91")
            cursor.execute("UPDATE cms_activity SET city_code='320500' WHERE id=92")
            before_rows = []
            for table, row_id in (("cms_topic", 11), ("cms_activity", 12)):
                cursor.execute("SELECT city_code FROM `%s` WHERE id=%%s" % table, (row_id,))
                value = cursor.fetchone()[0]
                self.assertIsNone(value)
                before_rows.append({"target_table": table, "row_id": row_id, "city_code": value})
            before = {"format_version": 1, "database": self.schema, "server_uuid": self.server_uuid,
                      "captured_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                      "rows": before_rows}
            self.before_path.write_text(json.dumps(before), encoding="utf-8")
            connection.begin()
            self.migration(cursor, "migration_o14_content_city_code_backfill_20261022.sql")
            connection.commit()
            rows = []
            for before_row in before_rows:
                cursor.execute("SELECT city_code,source,write_time FROM o14_city_code_backfill_log "
                               "WHERE target_table=%s AND row_id=%s",
                               (before_row["target_table"], before_row["row_id"]))
                city, source, written = cursor.fetchone()
                rows.append({"target_table": before_row["target_table"], "row_id": before_row["row_id"],
                             "city_code": city, "source": source,
                             "write_time": written.strftime("%Y-%m-%d %H:%M:%S")})
        self.manifest = {"format_version": 1, "database": self.schema, "server_uuid": self.server_uuid,
                         "expected_count": 2, "rows": rows, "before_snapshot_sha256":
                             hashlib.sha256(self.before_path.read_bytes()).hexdigest()}
        self.path.write_text(json.dumps(self.manifest), encoding="utf-8")
        self.digest = hashlib.sha256(self.path.read_bytes()).hexdigest()
        self.manifest = tool.load_manifest(self.path, self.digest, self.schema, 2, self.before_path)
        self.baseline = self.snapshot()

    def snapshot(self):
        result = {}
        # Every assertion observes committed data from a new connection.
        with self.connect() as connection, connection.cursor() as cursor:
            for table in SNAPSHOT_TABLES:
                cursor.execute("SELECT * FROM `%s` ORDER BY 1,2" % table)
                result[table] = tuple(cursor.fetchall())
        return result

    def cli(self, apply=False):
        env = os.environ.copy()
        env.update({"O14_MYSQL_HOST": self.host, "O14_MYSQL_PORT": str(self.port),
                    "O14_MYSQL_DATABASE": self.schema, "O14_MYSQL_USER": self.user,
                    "O14_MYSQL_PASSWORD": self.password})
        args = [sys.executable, str(REPO / "ci/o14_backfill_rollback.py"), "--manifest", str(self.path),
                "--sha256", self.digest, "--before-snapshot", str(self.before_path),
                "--database", self.schema, "--expected-count", "2"]
        return subprocess.run(args + (["--apply"] if apply else []), cwd=REPO,
                              env=env, capture_output=True, text=True, timeout=30)

    def assert_applied(self):
        expected = copy.deepcopy(self.baseline)
        for table, row_id in (("cms_topic", 11), ("cms_activity", 12)):
            # city_code was appended by the real shipped migrations.
            expected[table] = tuple(row[:-1] + (None,) if row[0] == row_id else row
                                    for row in expected[table])
        self.assertEqual(expected, self.snapshot())

    def test_cli_preview_apply_readback_preserves_all_logs_and_rejects_repeat(self):
        preview = self.cli()
        self.assertEqual(0, preview.returncode, preview.stderr)
        self.assertEqual("preview", json.loads(preview.stdout)["status"])
        self.assertEqual(self.baseline, self.snapshot())
        applied = self.cli(apply=True)
        self.assertEqual(0, applied.returncode, applied.stderr)
        self.assertEqual("applied", json.loads(applied.stdout)["status"])
        self.assert_applied()
        after = self.snapshot()
        repeat = self.cli(apply=True)
        self.assertNotEqual(0, repeat.returncode)
        self.assertIn("already withdrawn", repeat.stderr)
        self.assertEqual(after, self.snapshot())

    def test_manual_edit_refuses_whole_batch(self):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("UPDATE cms_topic SET city_code='440100' WHERE id=11")
        before = self.snapshot()
        result = self.cli(apply=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.snapshot())

    def test_log_refresh_or_removal_refuses_whole_batch(self):
        for mutation in ("UPDATE o14_city_code_backfill_log SET write_time=write_time+INTERVAL 1 SECOND "
                         "WHERE target_table='cms_topic' AND row_id=11",
                         "DELETE FROM o14_city_code_backfill_log WHERE target_table='cms_topic' AND row_id=11"):
            with self.subTest(mutation=mutation):
                with self.connect() as connection, connection.cursor() as cursor:
                    cursor.execute(mutation)
                before = self.snapshot()
                self.assertNotEqual(0, self.cli(apply=True).returncode)
                self.assertEqual(before, self.snapshot())

    def test_second_table_update_failure_rolls_back_first_table(self):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("ALTER TABLE cms_topic ADD CONSTRAINT it_refuse_null CHECK (city_code IS NOT NULL)")
        before = self.snapshot()
        with self.connect() as connection:
            with self.assertRaises(pymysql.err.OperationalError) as raised:
                tool.rollback_batch(connection, self.manifest, apply=True)
            self.assertEqual(3819, raised.exception.args[0])
        self.assertEqual(before, self.snapshot(), "earlier activity update must roll back")

    def test_actual_row_lock_excludes_concurrent_manual_edit(self):
        locked = threading.Event()
        release = threading.Event()
        outcome = []
        original = tool.read_city

        def pause_after_lock(cursor, row):
            value = original(cursor, row)
            if row["target_table"] == "cms_topic" and not locked.is_set():
                locked.set()
                if not release.wait(10):
                    raise RuntimeError("test lock barrier timed out")
            return value

        def worker():
            try:
                with self.connect() as connection:
                    outcome.append(tool.rollback_batch(connection, self.manifest, apply=True))
            except Exception as error:
                outcome.append(error)

        with patch.object(tool, "read_city", side_effect=pause_after_lock):
            thread = threading.Thread(target=worker)
            thread.start()
            try:
                self.assertTrue(locked.wait(10), "rollback did not acquire target lock")
                with self.connect() as connection, connection.cursor() as cursor:
                    cursor.execute("SET SESSION innodb_lock_wait_timeout=1")
                    with self.assertRaises(pymysql.err.OperationalError) as raised:
                        cursor.execute("UPDATE cms_topic SET city_code='440100' WHERE id=11")
                    self.assertEqual(1205, raised.exception.args[0])
            finally:
                release.set()
                thread.join(timeout=15)
            self.assertFalse(thread.is_alive())
        self.assertEqual(1, len(outcome))
        self.assertIsInstance(outcome[0], dict)
        self.assertEqual("applied", outcome[0]["status"])
        self.assert_applied()


if __name__ == "__main__":
    unittest.main()

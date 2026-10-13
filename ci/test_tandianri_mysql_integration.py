import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import uuid

import pymysql

from ci.run_migration import assert_mysql_server_provenance


RUNNER = pathlib.Path(__file__).with_name("run_migration.py")
MIGRATION_RELATIVE = pathlib.Path(
    "chengyinhub-admin/sql/migration_20260814_tandianri_product_type_backfill.sql")
SCRATCH_SCHEMA_PREFIX = "cy_tandianri_atomic_it_"


class TandianriAtomicMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError(
                "MySQL IT fail closed: set CHENGYIN_MYSQL_IT=1 only in the explicit CI gate")
        cls.repo = pathlib.Path(os.environ.get("CHENGYIN_REPO", os.getcwd())).resolve()
        cls.migration = cls.repo / MIGRATION_RELATIVE
        if not cls.migration.is_file():
            raise RuntimeError(f"MySQL IT fail closed: migration not found under repo: {cls.repo}")

        missing_target = [
            name for name in ("CHENGYIN_MYSQL_IT_HOST", "CHENGYIN_MYSQL_IT_PORT")
            if name not in os.environ
        ]
        if missing_target:
            raise RuntimeError(
                "MySQL IT fail closed: explicitly set local target: "
                + ", ".join(missing_target))
        cls.host = os.environ["CHENGYIN_MYSQL_IT_HOST"]
        cls.port = int(os.environ["CHENGYIN_MYSQL_IT_PORT"])
        cls.user = os.environ.get("CHENGYIN_MYSQL_IT_USER", "root")
        cls.password = os.environ.get("CHENGYIN_MYSQL_IT_PASSWORD", "")
        if cls.host not in {"127.0.0.1", "localhost", "::1"}:
            raise RuntimeError("MySQL IT fail closed: only an explicitly local scratch server is allowed")

        cls.schema = SCRATCH_SCHEMA_PREFIX + uuid.uuid4().hex[:12]
        if not cls.schema.startswith(SCRATCH_SCHEMA_PREFIX):
            raise RuntimeError("MySQL IT fail closed: invalid scratch schema prefix")
        print(
            f"scratch target preflight: host={cls.host}, port={cls.port}, "
            f"database={cls.schema}")
        cls.admin = pymysql.connect(
            host=cls.host,
            port=cls.port,
            user=cls.user,
            password=cls.password,
            connect_timeout=5,
            autocommit=True,
        )
        with cls.admin.cursor() as cursor:
            cursor.execute("SELECT VERSION(), @@version_comment")
            cls.mysql_version, version_comment = cursor.fetchone()
            print(
                f"scratch MySQL version: {cls.mysql_version}; "
                f"version_comment: {version_comment}")
            try:
                assert_mysql_server_provenance(cls.mysql_version, version_comment)
            except ValueError as error:
                raise RuntimeError(
                    "MySQL IT fail closed: only Oracle MySQL 8.0.45 is accepted; "
                    f"got {cls.mysql_version} ({version_comment})") from error
            print("acceptance tier: Oracle MySQL 8.0.45")
            cursor.execute(f"CREATE DATABASE `{cls.schema}` CHARACTER SET utf8mb4")

        cls.config_file = tempfile.NamedTemporaryFile(
            "w", suffix=".yml", encoding="utf-8", delete=False)
        cls.config_file.write(
            "url: jdbc:mysql://%s:%s/%s\nusername: %s\npassword: ${CHENGYIN_MYSQL_IT_PASSWORD:-}\n"
            % (cls.host, cls.port, cls.schema, cls.user)
        )
        cls.config_file.close()

        connection = cls.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TABLE cms_topic ("
                    "id BIGINT PRIMARY KEY, product_type INT NULL, del_flag INT NOT NULL, "
                    "status INT NOT NULL, update_by VARCHAR(64), update_time DATETIME NULL"
                    ") ENGINE=InnoDB"
                )
                cursor.execute(
                    "CREATE TABLE oms_ticket ("
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_type BIGINT NOT NULL, "
                    "owner_id BIGINT NOT NULL, mode INT NOT NULL, del_flag INT NOT NULL"
                    ") ENGINE=InnoDB"
                )
                cursor.execute(
                    "SELECT TABLE_NAME, ENGINE FROM INFORMATION_SCHEMA.TABLES "
                    "WHERE TABLE_SCHEMA=%s ORDER BY TABLE_NAME", (cls.schema,)
                )
                engines = tuple(cursor.fetchall())
                if engines != (("cms_topic", "InnoDB"), ("oms_ticket", "InnoDB")):
                    raise AssertionError(f"scratch tables must be InnoDB, got {engines!r}")
        finally:
            connection.close()

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "admin"):
            if not cls.schema.startswith(SCRATCH_SCHEMA_PREFIX):
                raise RuntimeError("refusing to drop an unvalidated scratch schema")
            with cls.admin.cursor() as cursor:
                cursor.execute(f"DROP DATABASE `{cls.schema}`")
            cls.admin.close()
        if hasattr(cls, "config_file"):
            pathlib.Path(cls.config_file.name).unlink(missing_ok=True)

    @classmethod
    def connect_schema(cls):
        return pymysql.connect(
            host=cls.host,
            port=cls.port,
            user=cls.user,
            password=cls.password,
            database=cls.schema,
            connect_timeout=5,
            autocommit=True,
        )

    def reset_fixture(self, extra_topic_active):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute("DELETE FROM oms_ticket")
                cursor.execute("DELETE FROM cms_topic")
                cursor.execute(
                    "INSERT INTO cms_topic(id,product_type,del_flag,status,update_by) "
                    "VALUES (23,NULL,0,1,'system'),(24,NULL,0,%s,'system')",
                    (1 if extra_topic_active else 0,),
                )
                cursor.execute(
                    "INSERT INTO oms_ticket(owner_type,owner_id,mode,del_flag) "
                    "VALUES (1,23,1,0),(1,23,2,0),(1,24,1,0)"
                )
        finally:
            connection.close()

    def run_migration(self):
        env = os.environ.copy()
        # ★ 必须显式写「local」,不能留空:runner 的设计是**空字符串不算「显式选择本机」**
        #   (它多半来自上一次清理不干净),会 fail-closed 到生产 SSH 目标 ——
        #   PR #741 的 Backend tests 就红在这:CI 上没有部署密钥,连生产失败,
        #   于是 CHECK probe 那两条回执断言全落空。本机 scratch 是显式选择,就显式写出来。
        env["CHENGYIN_DB_SSH_HOST"] = "local"
        env["CHENGYIN_MYSQL_IT_PASSWORD"] = self.password
        return subprocess.run(
            [
                sys.executable,
                str(RUNNER),
                "--config", self.config_file.name,
                "--sql", str(self.migration),
                "--transaction",
            ],
            cwd=self.repo,
            env=env,
            text=True,
            capture_output=True,
            timeout=30,
        )

    def assert_check_probe_and_same_session_cleanup_receipts(self, result):
        output = result.stdout + result.stderr
        self.assertIn("CHECK probe=3819", output)
        self.assertIn("same-session cleanup=1146", output)

    def read_topics(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT id,product_type,update_by,update_time FROM cms_topic ORDER BY id")
                return tuple(cursor.fetchall())
        finally:
            connection.close()

    def test_extra_active_topic_rolls_back_target_update_across_new_connection(self):
        self.reset_fixture(extra_topic_active=True)

        result = self.run_migration()

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assert_check_probe_and_same_session_cleanup_receipts(result)
        self.assertIn("ROLLBACK OK", result.stdout)
        self.assertIn("全部持久表均为 InnoDB: cms_topic, oms_ticket", result.stdout)
        topics = self.read_topics()
        self.assertIsNone(topics[0][1], "id=23 must remain NULL after the failed global guard")
        self.assertEqual("system", topics[0][2])
        self.assertIsNone(topics[1][1])

    def test_success_commits_and_replay_is_a_noop(self):
        self.reset_fixture(extra_topic_active=False)

        first = self.run_migration()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self.assert_check_probe_and_same_session_cleanup_receipts(first)
        self.assertIn("COMMIT OK", first.stdout)
        self.assertIn("全部持久表均为 InnoDB: cms_topic, oms_ticket", first.stdout)
        after_first = self.read_topics()
        self.assertEqual(1, after_first[0][1])
        self.assertEqual("migration_tandianri_product_type_backfill", after_first[0][2])

        replay = self.run_migration()
        self.assertEqual(0, replay.returncode, replay.stdout + replay.stderr)
        self.assert_check_probe_and_same_session_cleanup_receipts(replay)
        self.assertIn("COMMIT OK", replay.stdout)
        self.assertEqual(after_first, self.read_topics())

    def test_product_type_drift_fails_before_update_and_preserves_preimage(self):
        self.reset_fixture(extra_topic_active=False)
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute("UPDATE cms_topic SET product_type=2 WHERE id=23")
        finally:
            connection.close()
        before = self.read_topics()

        result = self.run_migration()

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assert_check_probe_and_same_session_cleanup_receipts(result)
        self.assertIn("ROLLBACK OK", result.stdout)
        self.assertEqual(before, self.read_topics())

    def test_status_drift_fails_before_update_and_preserves_preimage(self):
        self.reset_fixture(extra_topic_active=False)
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute("UPDATE cms_topic SET status=0 WHERE id=23")
        finally:
            connection.close()
        before = self.read_topics()

        result = self.run_migration()

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assert_check_probe_and_same_session_cleanup_receipts(result)
        self.assertIn("ROLLBACK OK", result.stdout)
        self.assertEqual(before, self.read_topics())

    def test_ticket_mode_drift_fails_before_update_and_preserves_preimage(self):
        self.reset_fixture(extra_topic_active=False)
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute("DELETE FROM oms_ticket WHERE owner_id=23 AND mode=2")
        finally:
            connection.close()
        before = self.read_topics()

        result = self.run_migration()

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assert_check_probe_and_same_session_cleanup_receipts(result)
        self.assertIn("ROLLBACK OK", result.stdout)
        self.assertEqual(before, self.read_topics())


if __name__ == "__main__":
    unittest.main()

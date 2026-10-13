import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import uuid
from datetime import datetime

import pymysql

from ci.run_migration import assert_mysql_server_provenance


RUNNER = pathlib.Path(__file__).with_name("run_migration.py")
MIGRATION = pathlib.Path(
    "chengyinhub-admin/sql/migration_merchant_z_quartz_jobs_pause_repair_20260826.sql")
SCRATCH_SCHEMA_PREFIX = "cy_quartz_pause_repair_it_"

TARGET_ROWS = (
    (225, "商家CRM触达恢复", "merchantCrmDeliveryRecoveryTask.run()"),
    (226, "俱乐部活动运营维护", "clubEventOpsTask.run()"),
    (227, "俱乐部动态图片送审恢复", "clubPostMediaReviewTask.run()"),
)


class QuartzJobsPauseRepairMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError(
                "MySQL IT fail closed: set CHENGYIN_MYSQL_IT=1 only in the explicit CI gate")
        cls.repo = pathlib.Path(os.environ.get("CHENGYIN_REPO", os.getcwd())).resolve()
        cls.migration = cls.repo / MIGRATION
        if not cls.migration.is_file():
            raise RuntimeError(
                f"MySQL IT fail closed: migration not found under repo: {cls.migration}")

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
            raise RuntimeError(
                "MySQL IT fail closed: only an explicitly local scratch server is allowed")

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
            version, version_comment = cursor.fetchone()
            print(f"scratch MySQL version: {version}; version_comment: {version_comment}")
            try:
                assert_mysql_server_provenance(version, version_comment)
            except ValueError as error:
                raise RuntimeError(
                    "MySQL IT fail closed: only Oracle MySQL 8.0.45 is accepted; "
                    f"got {version} ({version_comment})") from error
            print("acceptance tier: Oracle MySQL 8.0.45")

        cls.config_file = tempfile.NamedTemporaryFile(
            "w", suffix=".yml", encoding="utf-8", delete=False)
        cls.config_file.write(
            "url: jdbc:mysql://%s:%s/%s\nusername: %s\n"
            "password: ${CHENGYIN_MYSQL_IT_PASSWORD:-}\n"
            % (cls.host, cls.port, cls.schema, cls.user))
        cls.config_file.close()

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "admin"):
            if not cls.schema.startswith(SCRATCH_SCHEMA_PREFIX):
                raise RuntimeError("refusing to drop an unvalidated scratch schema")
            with cls.admin.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS `{cls.schema}`")
            cls.admin.close()
        if hasattr(cls, "config_file"):
            pathlib.Path(cls.config_file.name).unlink(missing_ok=True)

    def setUp(self):
        with self.admin.cursor() as cursor:
            cursor.execute(f"DROP DATABASE IF EXISTS `{self.schema}`")
            cursor.execute(
                f"CREATE DATABASE `{self.schema}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TABLE sys_job ("
                    "job_id BIGINT NOT NULL, "
                    "job_name VARCHAR(64) NOT NULL, "
                    "job_group VARCHAR(64) NOT NULL, "
                    "invoke_target VARCHAR(500) NOT NULL, "
                    "cron_expression VARCHAR(255) NOT NULL, "
                    "misfire_policy VARCHAR(20) NOT NULL, "
                    "concurrent CHAR(1) NOT NULL, "
                    "status CHAR(1) NOT NULL, "
                    "create_by VARCHAR(64) DEFAULT NULL, "
                    "create_time DATETIME DEFAULT NULL, "
                    "update_by VARCHAR(64) DEFAULT NULL, "
                    "update_time DATETIME DEFAULT NULL, "
                    "remark VARCHAR(1000) DEFAULT NULL, "
                    "PRIMARY KEY (job_id)"
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                    "COLLATE=utf8mb4_general_ci")
                cursor.execute(
                    "INSERT INTO sys_job "
                    "(job_id,job_name,job_group,invoke_target,cron_expression,"
                    "misfire_policy,concurrent,status,create_by,create_time,remark) "
                    "VALUES "
                    "(125,'既有无关任务','DEFAULT','legacyOtherTask.run()',"
                    "'0 0 * * * ?','3','1','0','admin','2026-08-01 00:00:00','保持不变')")
                cursor.executemany(
                    "INSERT INTO sys_job "
                    "(job_id,job_name,job_group,invoke_target,cron_expression,"
                    "misfire_policy,concurrent,status,create_by,create_time,remark) "
                    "VALUES (%s,%s,'DEFAULT',%s,'0 * * * * ?','3','1','0',"
                    "'admin','2026-08-25 00:00:00','历史注册原始备注')",
                    TARGET_ROWS,
                )
        finally:
            connection.close()

    def connect_schema(self):
        return pymysql.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.schema,
            connect_timeout=5,
            autocommit=True,
        )

    def run_migration(self):
        env = os.environ.copy()
        env["CHENGYIN_DB_SSH_HOST"] = "local"
        env["CHENGYIN_MYSQL_IT_PASSWORD"] = self.password
        return subprocess.run(
            [
                sys.executable,
                str(RUNNER),
                "--config",
                self.config_file.name,
                "--sql",
                str(self.migration),
                "--transaction",
            ],
            cwd=self.repo,
            env=env,
            text=True,
            capture_output=True,
            timeout=30,
        )

    def read_rows(self):
        """Always observe the committed business table through a fresh connection."""
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT job_id,job_name,invoke_target,job_group,cron_expression,"
                    "misfire_policy,concurrent,status,update_by,update_time,remark "
                    "FROM sys_job ORDER BY job_id")
                return tuple(cursor.fetchall())
        finally:
            connection.close()

    def assert_rollback_preserves_all_rows(self, result, before):
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("ROLLBACK OK", result.stdout)
        self.assertEqual(before, self.read_rows())

    def test_arbitrary_target_ids_pause_and_unrelated_job_id_125_stays_unchanged(self):
        before = self.read_rows()

        result = self.run_migration()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("COMMIT OK", result.stdout)
        after = self.read_rows()
        self.assertEqual(before[0], after[0], "unrelated job_id=125 must remain byte-for-byte unchanged")
        self.assertEqual((225, 226, 227), tuple(row[0] for row in after[1:]))
        self.assertTrue(all(row[7] == "1" for row in after[1:]))
        self.assertTrue(all(row[8] == "admin" for row in after[1:]))
        self.assertTrue(all("触发时机=" in row[10] for row in after[1:]))
        self.assertTrue(all("另行独立授权启用" in row[10] for row in after[1:]))

    def test_replay_is_safe_and_does_not_refresh_update_time(self):
        first = self.run_migration()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)

        sentinels = {
            225: datetime(2001, 1, 1, 1, 1, 1),
            226: datetime(2002, 2, 2, 2, 2, 2),
            227: datetime(2003, 3, 3, 3, 3, 3),
        }
        connection = self.connect_schema()
        connection.autocommit(False)
        try:
            with connection.cursor() as cursor:
                cursor.executemany(
                    "UPDATE sys_job SET update_time=%s WHERE job_id=%s",
                    [(sentinel, job_id) for job_id, sentinel in sentinels.items()],
                )
                self.assertEqual(3, cursor.rowcount)
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

        before_replay = self.read_rows()
        before_times = {row[0]: row[9] for row in before_replay if row[0] in sentinels}
        for job_id, sentinel in sentinels.items():
            self.assertEqual(sentinel, before_times[job_id])

        replay = self.run_migration()

        self.assertEqual(0, replay.returncode, replay.stdout + replay.stderr)
        self.assertIn("COMMIT OK", replay.stdout)
        after_replay = self.read_rows()
        after_times = {row[0]: row[9] for row in after_replay if row[0] in sentinels}
        for job_id, sentinel in sentinels.items():
            self.assertEqual(sentinel, after_times[job_id])
        self.assertEqual(before_replay, after_replay)

    def test_duplicate_target_fails_closed_and_rolls_back(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO sys_job "
                    "(job_id,job_name,job_group,invoke_target,cron_expression,"
                    "misfire_policy,concurrent,status,remark) VALUES "
                    "(228,'商家CRM触达恢复副本','DEFAULT',"
                    "'merchantCrmDeliveryRecoveryTask.run()',"
                    "'0 * * * * ?','3','1','0','重复 target')")
        finally:
            connection.close()
        before = self.read_rows()

        self.assert_rollback_preserves_all_rows(self.run_migration(), before)

    def test_wrong_configuration_fails_closed_and_rolls_back(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE sys_job SET cron_expression='0 */5 * * * ?' WHERE job_id=226")
        finally:
            connection.close()
        before = self.read_rows()

        self.assert_rollback_preserves_all_rows(self.run_migration(), before)

    def test_wrong_case_target_fails_closed_and_rolls_back(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE sys_job "
                    "SET invoke_target='MerchantCrmDeliveryRecoveryTask.run()' "
                    "WHERE job_id=225")
        finally:
            connection.close()
        before = self.read_rows()

        self.assert_rollback_preserves_all_rows(self.run_migration(), before)

    def test_trailing_space_name_fails_closed_and_rolls_back(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE sys_job SET job_name=CONCAT(job_name, ' ') WHERE job_id=226")
        finally:
            connection.close()
        before = self.read_rows()

        self.assert_rollback_preserves_all_rows(self.run_migration(), before)

    def test_extra_same_name_with_other_target_fails_closed_and_rolls_back(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO sys_job "
                    "(job_id,job_name,job_group,invoke_target,cron_expression,"
                    "misfire_policy,concurrent,status,remark) VALUES "
                    "(228,'俱乐部活动运营维护','DEFAULT','unrelatedTask.run()',"
                    "'0 * * * * ?','3','1','0','额外同名目标')")
        finally:
            connection.close()
        before = self.read_rows()

        self.assert_rollback_preserves_all_rows(self.run_migration(), before)


if __name__ == "__main__":
    unittest.main(verbosity=2)

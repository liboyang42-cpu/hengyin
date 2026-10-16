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



class RetentionJobRegistrationMysqlIntegrationTest(unittest.TestCase):
    """Exercise the real forward through the fresh-schema runner on a tiny owned table."""

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
        cls.schema = "cy_retention_reg_it_" + uuid.uuid4().hex[:12]
        cls.migration = REPO / "chengyinhub-admin/sql/migration_20261015_retention_job_missing_registration.sql"
        cls.admin = pymysql.connect(host=cls.host, port=cls.port, user=cls.user,
                                    password=cls.password, autocommit=True, charset="utf8mb4")
        cls.addClassCleanup(cls.admin.close)
        with cls.admin.cursor() as cursor:
            cursor.execute("SELECT VERSION(), @@version_comment")
            assert_mysql_server_provenance(*cursor.fetchone())
            cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (cls.schema,))
            if cursor.fetchone()[0] != 0:
                raise RuntimeError("refusing to reuse an existing retention fixture schema")
            cls.addClassCleanup(cls.cleanup_owned_schema)
            cursor.execute("CREATE DATABASE " + cls.schema + " CHARACTER SET utf8mb4")
        from scripts.e2e import build_schema
        cls.build_schema = build_schema
        ddl = [build_schema.executable_statement(statement) for statement in
               build_schema.split_statements(BASELINE.read_text(encoding="utf-8"))
               if build_schema.executable_statement(statement).startswith("CREATE TABLE " + chr(96) + "sys_job" + chr(96) + " (")]
        if len(ddl) != 1:
            raise RuntimeError("the actual base sys_job DDL must be unique")
        with cls.connect() as connection, connection.cursor() as cursor:
            cursor.execute(ddl[0])

    @classmethod
    def connect(cls):
        return pymysql.connect(host=cls.host, port=cls.port, user=cls.user, password=cls.password,
                               database=cls.schema, autocommit=True, charset="utf8mb4")

    @classmethod
    def cleanup_owned_schema(cls):
        if hasattr(cls, "admin"):
            if not cls.schema.startswith("cy_retention_reg_it_"):
                raise RuntimeError("refusing to drop an unvalidated retention fixture schema")
            with cls.admin.cursor() as cursor:
                cursor.execute("DROP DATABASE IF EXISTS " + cls.schema)
                cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (cls.schema,))
                if cursor.fetchone()[0] != 0:
                    raise RuntimeError("retention fixture cleanup readback failed")

    def setUp(self):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("DELETE FROM sys_job")

    def rows(self):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT * FROM sys_job ORDER BY job_id,job_name,job_group")
            return cursor.fetchall()

    def seed(self, target, status="1", group="fixture"):
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,"
                           "misfire_policy,concurrent,status,create_by,create_time,update_by,update_time,remark) "
                           "VALUES('owned',%s,%s,'0 7 2 * * ?','2','0',%s,'fixture','2026-10-01 01:02:03',"
                           "'fixture','2026-10-02 04:05:06','keep exactly')", (group, target, status))

    def apply(self, after_execute=None):
        self.assertIn("-- runner: requires=transaction", self.migration.read_text(encoding="utf-8"))
        with self.connect() as connection, connection.cursor() as cursor:
            class ObservedCursor:
                def __getattr__(proxy, name):
                    return getattr(cursor, name)

                def execute(proxy, sql, *args):
                    result = cursor.execute(sql, *args)
                    if after_execute:
                        after_execute(cursor, sql)
                    return result
            results = []
            ok = run_file(ObservedCursor(), self.migration, "retention-job-forward", results, False)
            return ok, results

    def test_fresh_is_paused_and_replay_preserves_every_column(self):
        ok, errors = self.apply()
        self.assertTrue(ok, errors)
        before = self.rows()
        self.assertEqual(1, len(before))
        with self.connect() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT status,invoke_target,job_group,cron_expression,concurrent,misfire_policy FROM sys_job")
            self.assertEqual(("1", "dataRetentionCleanupTask.run()", "COMPLIANCE", "0 30 3 * * ?", "1", "3"), cursor.fetchone())
        self.assertTrue(self.apply()[0])
        self.assertEqual(before, self.rows())

    def test_existing_paused_or_enabled_custom_definition_is_never_rewritten(self):
        for status in ("0", "1"):
            with self.subTest(status=status):
                self.setUp()
                self.seed("dataRetentionCleanupTask.run()", status)
                before = self.rows()
                self.assertTrue(self.apply()[0])
                self.assertEqual(before, self.rows())

    def test_single_class_alias_whitespace_and_omitted_parentheses_are_preserved(self):
        aliases = ("com.chengyinhub.business.task.DataRetentionCleanupTask.run()",
                   " dataRetentionCleanupTask . run(  ) ",
                   "dataRetentionCleanupTask.run",
                   "\x01dataRetentionCleanupTask . run(\t)\x1f")
        for target in aliases:
            with self.subTest(target=target):
                self.setUp()
                self.seed(target)
                before = self.rows()
                self.assertTrue(self.apply()[0])
                self.assertEqual(before, self.rows())

    def test_cross_group_and_status_canonical_duplicates_reject_before_insert(self):
        self.seed("dataRetentionCleanupTask.run()", "0", "one")
        self.seed("com.chengyinhub.business.task.DataRetentionCleanupTask.run()", "1", "two")
        before = self.rows()
        ok, errors = self.apply()
        self.assertFalse(ok)
        self.assertTrue(errors)
        self.assertEqual(before, self.rows())

    def test_whitespace_and_omitted_parentheses_duplicates_reject(self):
        self.seed(" dataRetentionCleanupTask . run(  ) ", "1", "one")
        self.seed("dataRetentionCleanupTask.run", "0", "two")
        before = self.rows()
        self.assertFalse(self.apply()[0])
        self.assertEqual(before, self.rows())

    def test_unknown_argument_or_suffix_does_not_create_a_second_definition(self):
        for target in ("dataRetentionCleanupTask.run(1)", "dataRetentionCleanupTask.run()suffix",
                       "\u00a0dataRetentionCleanupTask.run()"):
            with self.subTest(target=target):
                self.setUp()
                self.seed(target)
                before = self.rows()
                self.assertFalse(self.apply()[0])
                self.assertEqual(before, self.rows())

    def test_unrelated_task_preserves_every_column(self):
        self.seed("ryTask.ryNoParams()", "0")
        before = self.rows()
        self.assertTrue(self.apply()[0])
        self.assertEqual(2, len(self.rows()))
        self.assertEqual(before[0], self.rows()[0])

    def test_actual_sql_failure_after_insert_rolls_back_the_persistent_row(self):
        def fail_after_insert(cursor, sql):
            if sql.lstrip().upper().startswith("INSERT INTO SYS_JOB("):
                cursor.execute("INSERT INTO tmp_cy_retention_job_guard(id) VALUES(1)")
        ok, errors = self.apply(fail_after_insert)
        self.assertFalse(ok)
        self.assertTrue(errors)
        self.assertEqual((), self.rows())

    def test_explicit_callers_transaction_is_not_committed_when_autocommit_is_true(self):
        with self.connect() as connection, connection.cursor() as cursor:
            connection.begin()
            cursor.execute("INSERT INTO sys_job(job_name,job_group,invoke_target) VALUES('caller','caller','ryTask.ryNoParams()')")
            self.assertTrue(connection.get_autocommit())
            self.assertTrue(connection.server_status & 1)
            errors = []
            self.assertFalse(run_file(cursor, self.migration, "retention-job-forward", errors, False))
            self.assertTrue(connection.server_status & 1)
            self.assertEqual((), self.rows(), "the caller's prior write must remain uncommitted")
            connection.rollback()
        self.assertEqual((), self.rows())

    def test_empty_registry_lock_prevents_concurrent_alias_insert(self):
        import threading
        outcomes = []

        def competing_insert():
            with self.connect() as connection, connection.cursor() as cursor:
                cursor.execute("SET SESSION innodb_lock_wait_timeout=1")
                try:
                    cursor.execute("INSERT INTO sys_job(job_name,job_group,invoke_target,status) "
                                   "VALUES('competing','other','com.chengyinhub.business.task.DataRetentionCleanupTask.run()','0')")
                    outcomes.append("inserted")
                except pymysql.OperationalError as error:
                    outcomes.append(error.args[0])

        def observe_lock(cursor, sql):
            if "INSERT INTO tmp_cy_retention_job_rows" in sql and "FOR UPDATE" in sql:
                worker = threading.Thread(target=competing_insert)
                worker.start()
                worker.join(5)
                self.assertFalse(worker.is_alive(), "competing own-schema insert must resolve under the short lock timeout")

        ok, errors = self.apply(observe_lock)
        self.assertTrue(ok, errors)
        self.assertEqual([1205], outcomes, "the actual fresh runner must keep its row/range lock until commit")
        self.assertEqual(1, len(self.rows()))


if __name__ == "__main__":
    unittest.main()

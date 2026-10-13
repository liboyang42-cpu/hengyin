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
MIGRATION = pathlib.Path(
    "chengyinhub-admin/sql/"
    "migration_merchant_operator_crm_phase1a_delivery_20260823.sql")
SCRATCH_SCHEMA_PREFIX = "cy_crm_delivery_check_it_"


class MerchantCrmDeliveryCheckMysqlIntegrationTest(unittest.TestCase):
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
                "--config", self.config_file.name,
                "--sql", str(self.migration),
            ],
            cwd=self.repo,
            env=env,
            text=True,
            capture_output=True,
            timeout=30,
        )

    def execute(self, statement):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(statement)
        finally:
            connection.close()

    def read_enforcement(self, table, constraint):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT enforced FROM information_schema.table_constraints "
                    "WHERE table_schema=DATABASE() AND table_name=%s "
                    "AND constraint_name=%s AND constraint_type='CHECK'",
                    (table, constraint),
                )
                row = cursor.fetchone()
                return None if row is None else row[0]
        finally:
            connection.close()

    def assert_success(self, result):
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertEqual(
            10, output.count("断言通过:scalar-true; readback=1"), output)

    def assert_simple_readback_fails(self, result):
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertEqual(
            8, output.count("断言通过:scalar-true; readback=1"), output)
        self.assertIn("runner 断言 scalar-true 失败: readback=(0,)", output)

    def assert_compound_readback_fails(self, result):
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertEqual(
            9, output.count("断言通过:scalar-true; readback=1"), output)
        self.assertIn("runner 断言 scalar-true 失败: readback=(0,)", output)

    def test_enforced_receipt_check_accepts_valid_rejects_invalid_and_reads_exact_row(self):
        self.assert_success(self.run_migration())
        self.assertEqual(
            "YES",
            self.read_enforcement(
                "merchant_crm_delivery_guard", "chk_merchant_crm_delivery_receipt"),
        )

        valid_connection = self.connect_schema()
        try:
            with valid_connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO merchant_crm_delivery_guard("
                    "id,merchant_id,member_id,channel,delivery_date,campaign_id,"
                    "recipient_id,status,lease_token,lease_until,version,"
                    "message_receipt_id,coupon_history_id,failure_code) "
                    "VALUES (101,701,801,'IN_APP','2026-08-26',901,1001,"
                    "'DELIVERED',NULL,NULL,4,1101,NULL,NULL)"
                )
        finally:
            valid_connection.close()

        invalid_connection = self.connect_schema()
        try:
            with invalid_connection.cursor() as cursor:
                with self.assertRaises(pymysql.MySQLError) as caught:
                    cursor.execute(
                        "INSERT INTO merchant_crm_delivery_guard("
                        "id,merchant_id,member_id,channel,delivery_date,campaign_id,"
                        "recipient_id,status,lease_token,lease_until,version,"
                        "message_receipt_id,coupon_history_id,failure_code) "
                        "VALUES (102,701,802,'IN_APP','2026-08-26',902,1002,"
                        "'DELIVERED',NULL,NULL,0,NULL,1202,NULL)"
                    )
                self.assertEqual(3819, caught.exception.args[0])
                self.assertIn(
                    "chk_merchant_crm_delivery_receipt", caught.exception.args[1])
        finally:
            invalid_connection.close()

        readback_connection = self.connect_schema()
        try:
            with readback_connection.cursor() as cursor:
                cursor.execute(
                    "SELECT id,merchant_id,member_id,channel,"
                    "DATE_FORMAT(delivery_date,'%Y-%m-%d'),campaign_id,recipient_id,"
                    "status,lease_token,lease_until,version,message_receipt_id,"
                    "coupon_history_id,failure_code "
                    "FROM merchant_crm_delivery_guard ORDER BY id"
                )
                self.assertEqual(
                    ((101, 701, 801, "IN_APP", "2026-08-26", 901, 1001,
                      "DELIVERED", None, None, 4, 1101, None, None),),
                    cursor.fetchall(),
                )
        finally:
            readback_connection.close()

    def test_simple_check_not_enforced_fails_simple_readback(self):
        self.assert_success(self.run_migration())
        self.execute(
            "ALTER TABLE merchant_crm_campaign ALTER CHECK "
            "chk_merchant_crm_campaign_status NOT ENFORCED")
        self.assertEqual(
            "NO",
            self.read_enforcement(
                "merchant_crm_campaign", "chk_merchant_crm_campaign_status"),
        )

        self.assert_simple_readback_fails(self.run_migration())

    def test_previously_omitted_check_not_enforced_fails_simple_readback(self):
        self.assert_success(self.run_migration())
        self.execute(
            "ALTER TABLE merchant_crm_marketing_consent ALTER CHECK "
            "chk_merchant_crm_consent_event NOT ENFORCED")
        self.assertEqual(
            "NO",
            self.read_enforcement(
                "merchant_crm_marketing_consent", "chk_merchant_crm_consent_event"),
        )

        self.assert_simple_readback_fails(self.run_migration())

    def test_compound_check_not_enforced_fails_terminal_readback(self):
        self.assert_success(self.run_migration())
        self.execute(
            "ALTER TABLE merchant_crm_delivery_guard ALTER CHECK "
            "chk_merchant_crm_delivery_lease NOT ENFORCED")
        self.assertEqual(
            "NO",
            self.read_enforcement(
                "merchant_crm_delivery_guard", "chk_merchant_crm_delivery_lease"),
        )

        self.assert_compound_readback_fails(self.run_migration())

    def test_compound_antonym_clause_fails_terminal_readback(self):
        self.assert_success(self.run_migration())
        self.execute(
            "ALTER TABLE merchant_crm_delivery_guard "
            "DROP CHECK chk_merchant_crm_delivery_receipt, "
            "ADD CONSTRAINT chk_merchant_crm_delivery_receipt CHECK ("
            "status = 'DELIVERED' OR "
            "(channel = 'IN_APP' AND message_receipt_id IS NOT NULL "
            "AND coupon_history_id IS NULL) OR "
            "(channel = 'COUPON' AND message_receipt_id IS NULL "
            "AND coupon_history_id IS NOT NULL)) ENFORCED")

        self.assert_compound_readback_fails(self.run_migration())


if __name__ == "__main__":
    unittest.main()

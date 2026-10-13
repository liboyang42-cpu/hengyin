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
REPAIR_MIGRATION = pathlib.Path(
    "chengyinhub-admin/sql/migration_merchant_z_candidate_schema_repair_20260826.sql")
SCRATCH_SCHEMA_PREFIX = "cy_merchant_schema_repair_it_"

AFTERCARE_LEGACY_COLUMNS = (
    "id", "refund_application_id", "merchant_owner_member_id", "actor_member_id",
    "actor_role_code", "decision", "content", "evidence_url", "request_id",
    "create_time",
)
AFTERCARE_TARGET_COLUMNS = (
    "id", "refund_application_id", "merchant_id", "merchant_owner_member_id",
    "actor_member_id", "actor_role_code", "decision", "content", "evidence_url",
    "request_id", "create_time",
)
CRM_LEGACY_COLUMNS = (
    "id", "merchant_id", "merchant_row_id", "requester_member_id",
    "requester_role_code", "filter_json", "include_paid_amount", "status", "version",
    "row_limit", "row_count", "storage_key", "token_hash", "request_id",
    "error_message", "expires_at", "started_at", "finished_at", "downloaded_at",
    "download_count", "create_time", "update_time",
)
CRM_TARGET_COLUMNS = (
    "id", "merchant_id", "merchant_owner_member_id", "requester_member_id",
    "requester_role_code", "filter_json", "include_paid_amount", "status", "version",
    "row_limit", "row_count", "storage_key", "token_hash", "request_id",
    "error_message", "expires_at", "started_at", "finished_at", "downloaded_at",
    "download_count", "create_time", "update_time",
)

AFTERCARE_LEGACY_INDEXES = (
    ("idx_merchant_aftercare_refund", 1, "BTREE", "YES", 0,
     "merchant_owner_member_id,refund_application_id,id"),
    ("PRIMARY", 0, "BTREE", "YES", 0, "id"),
    ("uk_merchant_aftercare_request", 0, "BTREE", "YES", 0,
     "merchant_owner_member_id,request_id"),
)
AFTERCARE_TARGET_INDEXES = (
    ("idx_merchant_aftercare_refund", 1, "BTREE", "YES", 0,
     "merchant_id,refund_application_id,id"),
    ("idx_merchant_aftercare_refund_lookup", 1, "BTREE", "YES", 0,
     "refund_application_id,merchant_id,id"),
    ("PRIMARY", 0, "BTREE", "YES", 0, "id"),
    ("uk_merchant_aftercare_request", 0, "BTREE", "YES", 0,
     "merchant_id,request_id"),
)
CRM_LEGACY_INDEXES = (
    ("idx_merchant_crm_export_owner", 1, "BTREE", "YES", 0, "merchant_id,id"),
    ("idx_merchant_crm_export_runner", 1, "BTREE", "YES", 0,
     "status,expires_at,create_time,id"),
    ("PRIMARY", 0, "BTREE", "YES", 0, "id"),
    ("uk_merchant_crm_export_request", 0, "BTREE", "YES", 0,
     "merchant_id,request_id"),
)
CRM_TARGET_INDEXES = (
    ("idx_merchant_crm_export_owner", 1, "BTREE", "YES", 0, "merchant_id,id"),
    ("idx_merchant_crm_export_runner", 1, "BTREE", "YES", 0,
     "status,expires_at,create_time,id"),
    ("PRIMARY", 0, "BTREE", "YES", 0, "id"),
    ("uk_merchant_crm_export_request", 0, "BTREE", "YES", 0,
     "merchant_id,request_id"),
)
TARGET_TABLE_COLLATION = "utf8mb4_0900_ai_ci"


class MerchantCandidateSchemaRepairMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError(
                "MySQL IT fail closed: set CHENGYIN_MYSQL_IT=1 only in the explicit CI gate")
        cls.repo = pathlib.Path(os.environ.get("CHENGYIN_REPO", os.getcwd())).resolve()
        cls.migration = cls.repo / REPAIR_MIGRATION
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
        self.create_exact_legacy_schema()

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

    def create_exact_legacy_schema(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TABLE merchant_aftercare_response ("
                    "id BIGINT NOT NULL AUTO_INCREMENT, "
                    "refund_application_id BIGINT NOT NULL "
                    "COMMENT 'refund_application.id', "
                    "merchant_owner_member_id BIGINT NOT NULL "
                    "COMMENT 'refund_application.merchant_id 当前 owner memberId 口径', "
                    "actor_member_id BIGINT NOT NULL COMMENT '本次实际操作会员', "
                    "actor_role_code VARCHAR(32) NOT NULL, "
                    "decision VARCHAR(16) NOT NULL "
                    "COMMENT 'AGREE/REJECT/EVIDENCE；仅商家意见，不是平台退款决策', "
                    "content VARCHAR(500) DEFAULT NULL, "
                    "evidence_url VARCHAR(512) DEFAULT NULL "
                    "COMMENT '服务端 URL 安全策略验证后的凭证地址', "
                    "request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL "
                    "COMMENT '商家主体内幂等键', "
                    "create_time DATETIME NOT NULL, PRIMARY KEY (id), "
                    "UNIQUE KEY uk_merchant_aftercare_request "
                    "(merchant_owner_member_id,request_id), "
                    "KEY idx_merchant_aftercare_refund "
                    "(merchant_owner_member_id,refund_application_id,id), "
                    "CONSTRAINT chk_merchant_aftercare_decision "
                    "CHECK (decision IN ('AGREE','REJECT','EVIDENCE')), "
                    "CONSTRAINT chk_merchant_aftercare_actor_role CHECK "
                    "(actor_role_code IN "
                    "('MERCHANT_OWNER','MERCHANT_MANAGER','MERCHANT_FINANCE'))"
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                    "COMMENT='商家退款售后追加意见与凭证'")
                cursor.execute(
                    "CREATE TABLE merchant_crm_export_task ("
                    "id BIGINT NOT NULL AUTO_INCREMENT, "
                    "merchant_id BIGINT NOT NULL "
                    "COMMENT '商家主体 mms_merchant.member_id', "
                    "merchant_row_id BIGINT NOT NULL "
                    "COMMENT 'mms_merchant.id，仅供领域审计', "
                    "requester_member_id BIGINT NOT NULL COMMENT '真实操作员工/店主', "
                    "requester_role_code VARCHAR(32) NOT NULL, "
                    "filter_json JSON NOT NULL COMMENT '服务端白名单归一化筛选快照', "
                    "include_paid_amount TINYINT NOT NULL DEFAULT 0 "
                    "COMMENT '创建时是否持有CRM_SENSITIVE_READ', "
                    "status VARCHAR(16) NOT NULL "
                    "COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/EXPIRED', "
                    "version INT NOT NULL DEFAULT 0 COMMENT '状态CAS版本', "
                    "row_limit INT NOT NULL DEFAULT 5000, "
                    "row_count INT NOT NULL DEFAULT 0, "
                    "storage_key VARCHAR(64) DEFAULT NULL "
                    "COMMENT '私有目录随机文件键，非公开URL', "
                    "token_hash CHAR(64) NOT NULL COMMENT '短时随机token的SHA-256；绝不存明文', "
                    "request_id VARCHAR(64) NOT NULL, "
                    "error_message VARCHAR(200) DEFAULT NULL "
                    "COMMENT '稳定错误文案，不存异常/路径/敏感数据', "
                    "expires_at DATETIME NOT NULL, started_at DATETIME DEFAULT NULL, "
                    "finished_at DATETIME DEFAULT NULL, downloaded_at DATETIME DEFAULT NULL, "
                    "download_count INT NOT NULL DEFAULT 0, "
                    "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    "PRIMARY KEY (id), "
                    "UNIQUE KEY uk_merchant_crm_export_request (merchant_id,request_id), "
                    "KEY idx_merchant_crm_export_owner (merchant_id,id), "
                    "KEY idx_merchant_crm_export_runner "
                    "(status,expires_at,create_time,id), "
                    "CONSTRAINT chk_merchant_crm_export_paid "
                    "CHECK (include_paid_amount IN (0,1)), "
                    "CONSTRAINT chk_merchant_crm_export_status CHECK "
                    "(status IN ('PENDING','RUNNING','SUCCESS','FAILED','EXPIRED')), "
                    "CONSTRAINT chk_merchant_crm_export_limit CHECK "
                    "(row_limit BETWEEN 1 AND 5000 AND row_count BETWEEN 0 AND 5000)"
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                    "COMMENT='商家CRM异步脱敏导出任务'")
        finally:
            connection.close()

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

    def read_columns(self, table):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema=DATABASE() AND table_name=%s "
                    "ORDER BY ordinal_position",
                    (table,),
                )
                return tuple(row[0] for row in cursor.fetchall())
        finally:
            connection.close()

    def read_indexes(self, table):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT index_name,MIN(non_unique),MIN(index_type),MIN(is_visible),"
                    "COUNT(sub_part),"
                    "GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') "
                    "FROM information_schema.statistics "
                    "WHERE table_schema=DATABASE() AND table_name=%s "
                    "GROUP BY index_name ORDER BY index_name",
                    (table,),
                )
                return tuple(cursor.fetchall())
        finally:
            connection.close()

    def read_table_collation(self, table):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT table_collation FROM information_schema.tables "
                    "WHERE table_schema=DATABASE() AND table_name=%s",
                    (table,),
                )
                return cursor.fetchone()[0]
        finally:
            connection.close()

    def read_snapshot(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                snapshot = []
                for table in ("merchant_aftercare_response", "merchant_crm_export_task"):
                    cursor.execute(f"SHOW CREATE TABLE `{table}`")
                    create_sql = cursor.fetchone()[1]
                    cursor.execute(f"SELECT * FROM `{table}` ORDER BY id")
                    rows = tuple(cursor.fetchall())
                    snapshot.append((table, create_sql, rows))
                cursor.execute(
                    "SELECT trigger_name,event_manipulation,action_timing,"
                    "event_object_table,action_statement "
                    "FROM information_schema.triggers "
                    "WHERE event_object_schema=DATABASE() "
                    "AND event_object_table IN "
                    "('merchant_aftercare_response','merchant_crm_export_task') "
                    "ORDER BY event_object_table,trigger_name")
                snapshot.append(("triggers", tuple(cursor.fetchall())))
                return tuple(snapshot)
        finally:
            connection.close()

    def read_row_counts(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT COUNT(*) FROM merchant_aftercare_response")
                aftercare_count = cursor.fetchone()[0]
                cursor.execute("SELECT COUNT(*) FROM merchant_crm_export_task")
                crm_count = cursor.fetchone()[0]
                return aftercare_count, crm_count
        finally:
            connection.close()

    def assert_exact_legacy_shape(self):
        self.assertEqual(
            TARGET_TABLE_COLLATION,
            self.read_table_collation("merchant_aftercare_response"),
        )
        self.assertEqual(
            AFTERCARE_LEGACY_COLUMNS,
            self.read_columns("merchant_aftercare_response"),
        )
        self.assertEqual(
            AFTERCARE_LEGACY_INDEXES,
            self.read_indexes("merchant_aftercare_response"),
        )
        self.assertEqual(
            CRM_LEGACY_COLUMNS,
            self.read_columns("merchant_crm_export_task"),
        )
        self.assertEqual(
            TARGET_TABLE_COLLATION,
            self.read_table_collation("merchant_crm_export_task"),
        )
        self.assertEqual(
            CRM_LEGACY_INDEXES,
            self.read_indexes("merchant_crm_export_task"),
        )

    def assert_exact_target_shape(self):
        self.assertEqual(
            TARGET_TABLE_COLLATION,
            self.read_table_collation("merchant_aftercare_response"),
        )
        self.assertEqual(
            AFTERCARE_TARGET_COLUMNS,
            self.read_columns("merchant_aftercare_response"),
        )
        self.assertEqual(
            AFTERCARE_TARGET_INDEXES,
            self.read_indexes("merchant_aftercare_response"),
        )
        self.assertEqual(
            CRM_TARGET_COLUMNS,
            self.read_columns("merchant_crm_export_task"),
        )
        self.assertEqual(
            TARGET_TABLE_COLLATION,
            self.read_table_collation("merchant_crm_export_task"),
        )
        self.assertEqual(
            CRM_TARGET_INDEXES,
            self.read_indexes("merchant_crm_export_task"),
        )

    def assert_first_guard_failed_before_ddl(self, result):
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn("runner 断言 scalar-true 失败: readback=(0,)", output)
        self.assertEqual(1, output.count("-- runner: assert=scalar-true"), output)
        self.assertNotIn("merchant_aftercare_upgrade_stmt", output)
        self.assertNotIn("merchant_crm_export_upgrade_stmt", output)

    def assert_empty_drift_rejected_and_preserved(self):
        before = self.read_snapshot()

        result = self.run_migration()

        self.assert_first_guard_failed_before_ddl(result)
        self.assertEqual((0, 0), self.read_row_counts())
        self.assertEqual(before, self.read_snapshot())

    def test_empty_exact_legacy_upgrades_and_target_replay_is_noop(self):
        self.assert_exact_legacy_shape()
        self.assertEqual((0, 0), self.read_row_counts())

        first = self.run_migration()

        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self.assertEqual(
            2,
            first.stdout.count("断言通过:scalar-true; readback=1"),
            first.stdout,
        )
        self.assert_exact_target_shape()
        self.assertEqual((0, 0), self.read_row_counts())
        after_first = self.read_snapshot()

        replay = self.run_migration()

        self.assertEqual(0, replay.returncode, replay.stdout + replay.stderr)
        self.assertEqual(
            2,
            replay.stdout.count("断言通过:scalar-true; readback=1"),
            replay.stdout,
        )
        self.assert_exact_target_shape()
        self.assertEqual((0, 0), self.read_row_counts())
        self.assertEqual(after_first, self.read_snapshot())

    def test_nonempty_aftercare_legacy_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO merchant_aftercare_response("
                    "refund_application_id,merchant_owner_member_id,actor_member_id,"
                    "actor_role_code,decision,content,evidence_url,request_id,create_time) "
                    "VALUES (101,201,202,'MERCHANT_OWNER','EVIDENCE','evidence',NULL,"
                    "'aftercare-nonempty','2026-08-26 00:00:00')")
        finally:
            connection.close()
        self.assert_exact_legacy_shape()
        before = self.read_snapshot()

        result = self.run_migration()

        self.assert_first_guard_failed_before_ddl(result)
        self.assertEqual((1, 0), self.read_row_counts())
        self.assertEqual(before, self.read_snapshot())
        self.assert_exact_legacy_shape()

    def test_nonempty_crm_legacy_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO merchant_crm_export_task("
                    "merchant_id,merchant_row_id,requester_member_id,requester_role_code,"
                    "filter_json,include_paid_amount,status,token_hash,request_id,expires_at) "
                    "VALUES (301,401,302,'MERCHANT_OWNER','{}',0,'PENDING',%s,"
                    "'crm-nonempty','2026-08-27 00:00:00')",
                    ("0" * 64,),
                )
        finally:
            connection.close()
        self.assert_exact_legacy_shape()
        before = self.read_snapshot()

        result = self.run_migration()

        self.assert_first_guard_failed_before_ddl(result)
        self.assertEqual((0, 1), self.read_row_counts())
        self.assertEqual(before, self.read_snapshot())
        self.assert_exact_legacy_shape()

    def test_wrong_check_clause_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "ALTER TABLE merchant_aftercare_response "
                    "DROP CHECK chk_merchant_aftercare_decision, "
                    "ADD CONSTRAINT chk_merchant_aftercare_decision "
                    "CHECK (decision IN ('AGREE','REJECT'))")
        finally:
            connection.close()

        self.assert_empty_drift_rejected_and_preserved()

    def test_wrong_request_id_metadata_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "ALTER TABLE merchant_crm_export_task "
                    "MODIFY COLUMN request_id VARCHAR(64) "
                    "CHARACTER SET ascii COLLATE ascii_bin NOT NULL")
        finally:
            connection.close()

        self.assert_empty_drift_rejected_and_preserved()

    def test_literal_null_sentinel_default_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "ALTER TABLE merchant_aftercare_response "
                    "MODIFY COLUMN content VARCHAR(500) DEFAULT '<NULL>'")
        finally:
            connection.close()

        self.assert_empty_drift_rejected_and_preserved()

    def test_extra_unique_index_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "ALTER TABLE merchant_crm_export_task "
                    "ADD UNIQUE KEY uk_merchant_crm_export_third_shape (request_id)")
        finally:
            connection.close()

        self.assert_empty_drift_rejected_and_preserved()

    def test_trigger_third_shape_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TRIGGER trg_merchant_aftercare_third_shape "
                    "BEFORE INSERT ON merchant_aftercare_response FOR EACH ROW "
                    "SET NEW.content=COALESCE(NEW.content,'triggered')")
        finally:
            connection.close()

        self.assert_empty_drift_rejected_and_preserved()

    def test_wrong_table_collation_fails_before_ddl_and_preserves_state(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "ALTER TABLE merchant_aftercare_response "
                    "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
        finally:
            connection.close()

        self.assertEqual(
            "utf8mb4_bin",
            self.read_table_collation("merchant_aftercare_response"),
        )
        self.assert_empty_drift_rejected_and_preserved()
        self.assertEqual(
            "utf8mb4_bin",
            self.read_table_collation("merchant_aftercare_response"),
        )


if __name__ == "__main__":
    unittest.main()

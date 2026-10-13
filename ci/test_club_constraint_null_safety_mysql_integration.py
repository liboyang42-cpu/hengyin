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
POST_MIGRATION = pathlib.Path(
    "chengyinhub-admin/sql/migration_club_post_announcement_20260823.sql")
GOVERNANCE_MIGRATION = pathlib.Path(
    "chengyinhub-admin/sql/migration_club_role_governance_20260823.sql")
REPAIR_MIGRATION = pathlib.Path(
    "chengyinhub-admin/sql/migration_club_role_z_null_safety_repair_20260826.sql")
SCRATCH_SCHEMA_PREFIX = "cy_club_null_safety_it_"


class ClubConstraintNullSafetyMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError(
                "MySQL IT fail closed: set CHENGYIN_MYSQL_IT=1 only in the explicit CI gate")
        cls.repo = pathlib.Path(os.environ.get("CHENGYIN_REPO", os.getcwd())).resolve()
        missing = [
            migration for migration in
            (POST_MIGRATION, GOVERNANCE_MIGRATION, REPAIR_MIGRATION)
            if not (cls.repo / migration).is_file()
        ]
        if missing:
            raise RuntimeError(
                "MySQL IT fail closed: missing SQL: " + ", ".join(map(str, missing)))

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

    def run_migration(self, migration):
        env = os.environ.copy()
        env["CHENGYIN_DB_SSH_HOST"] = "local"
        env["CHENGYIN_MYSQL_IT_PASSWORD"] = self.password
        return subprocess.run(
            [
                sys.executable,
                str(RUNNER),
                "--config", self.config_file.name,
                "--sql", str(self.repo / migration),
            ],
            cwd=self.repo,
            env=env,
            text=True,
            capture_output=True,
            timeout=30,
        )

    def create_base_club_post(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TABLE club_post ("
                    "id BIGINT NOT NULL AUTO_INCREMENT, club_id BIGINT NOT NULL, "
                    "type INT DEFAULT NULL, del_flag TINYINT NOT NULL DEFAULT 0, "
                    "create_time DATETIME NOT NULL, PRIMARY KEY (id)"
                    ") ENGINE=InnoDB")
        finally:
            connection.close()

    def create_old_constraint_schema(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "CREATE TABLE club_post ("
                    "id BIGINT NOT NULL AUTO_INCREMENT, club_id BIGINT NOT NULL, "
                    "type INT DEFAULT NULL, is_pinned TINYINT NOT NULL DEFAULT 0, "
                    "create_time DATETIME NOT NULL, PRIMARY KEY (id), "
                    "CONSTRAINT chk_club_post_pinned CHECK "
                    "(is_pinned IN (0,1) AND (is_pinned=0 OR type=2)) ENFORCED"
                    ") ENGINE=InnoDB")
                cursor.execute(
                    "CREATE TABLE club_governance_case ("
                    "id BIGINT NOT NULL AUTO_INCREMENT, club_id BIGINT NOT NULL, "
                    "case_type VARCHAR(16) NOT NULL, filer_member_id BIGINT NOT NULL, "
                    "subject_member_id BIGINT DEFAULT NULL, target_type VARCHAR(16) NOT NULL, "
                    "target_id BIGINT NOT NULL, evidence_json VARCHAR(1000) NOT NULL DEFAULT '{}', "
                    "related_ban_id BIGINT DEFAULT NULL, reason VARCHAR(500) NOT NULL, "
                    "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
                    "submit_request_id VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL, "
                    "PRIMARY KEY (id), CONSTRAINT chk_club_governance_case_relation CHECK ("
                    "(case_type='REPORT' AND target_type IN ('CLUB','ACTIVITY','MEMBER') "
                    "AND related_ban_id IS NULL AND ((target_type='MEMBER' "
                    "AND subject_member_id=target_id) OR (target_type IN ('CLUB','ACTIVITY') "
                    "AND subject_member_id IS NULL))) OR (case_type='APPEAL' "
                    "AND target_type='BAN' AND related_ban_id IS NOT NULL "
                    "AND target_id=related_ban_id AND filer_member_id=subject_member_id)"
                    ") ENFORCED) ENGINE=InnoDB")
        finally:
            connection.close()

    @staticmethod
    def insert_pinned_null(cursor, club_id):
        cursor.execute(
            "INSERT INTO club_post(club_id,type,is_pinned,create_time) "
            "VALUES (%s,NULL,1,'2026-08-26 00:00:00')",
            (club_id,),
        )

    @staticmethod
    def insert_report_member_null(cursor, request_id):
        cursor.execute(
            "INSERT INTO club_governance_case("
            "club_id,case_type,filer_member_id,subject_member_id,target_type,target_id,"
            "evidence_json,related_ban_id,reason,status,submit_request_id,create_time) "
            "VALUES (1,'REPORT',11,NULL,'MEMBER',22,'{}',NULL,'report','PENDING',%s,"
            "'2026-08-26 00:00:00')",
            (request_id,),
        )

    @staticmethod
    def insert_appeal_ban_null(cursor, request_id):
        cursor.execute(
            "INSERT INTO club_governance_case("
            "club_id,case_type,filer_member_id,subject_member_id,target_type,target_id,"
            "evidence_json,related_ban_id,reason,status,submit_request_id,create_time) "
            "VALUES (1,'APPEAL',11,NULL,'BAN',33,'{}',33,'appeal','PENDING',%s,"
            "'2026-08-26 00:00:00')",
            (request_id,),
        )

    def assert_rejects_with_3819(self, insert):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                with self.assertRaises(pymysql.MySQLError) as caught:
                    insert(cursor)
                self.assertEqual(3819, caught.exception.args[0])
        finally:
            connection.close()

    def read_row_counts(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT COUNT(*) FROM club_post")
                post_count = cursor.fetchone()[0]
                cursor.execute("SELECT COUNT(*) FROM club_governance_case")
                governance_count = cursor.fetchone()[0]
                return post_count, governance_count
        finally:
            connection.close()

    def read_constraint_clauses(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT tc.table_name,cc.check_clause "
                    "FROM information_schema.table_constraints tc "
                    "JOIN information_schema.check_constraints cc "
                    "ON cc.constraint_schema=tc.constraint_schema "
                    "AND cc.constraint_name=tc.constraint_name "
                    "WHERE tc.constraint_schema=DATABASE() AND tc.constraint_type='CHECK' "
                    "AND ((tc.table_name='club_post' "
                    "AND tc.constraint_name='chk_club_post_pinned') "
                    "OR (tc.table_name='club_governance_case' "
                    "AND tc.constraint_name='chk_club_governance_case_relation')) "
                    "ORDER BY tc.table_name")
                return tuple(cursor.fetchall())
        finally:
            connection.close()

    def insert_all_null_bypasses(self, suffix):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                self.insert_pinned_null(cursor, 1)
                self.insert_report_member_null(cursor, f"report-{suffix}")
                self.insert_appeal_ban_null(cursor, f"appeal-{suffix}")
        finally:
            connection.close()

    def delete_all_rows(self):
        connection = self.connect_schema()
        try:
            with connection.cursor() as cursor:
                cursor.execute("DELETE FROM club_post")
                cursor.execute("DELETE FROM club_governance_case")
        finally:
            connection.close()

    def assert_all_null_bypasses_rejected(self, suffix):
        self.assert_rejects_with_3819(
            lambda cursor: self.insert_pinned_null(cursor, 10))
        self.assert_rejects_with_3819(
            lambda cursor: self.insert_report_member_null(cursor, f"report-{suffix}"))
        self.assert_rejects_with_3819(
            lambda cursor: self.insert_appeal_ban_null(cursor, f"appeal-{suffix}"))

    def test_corrected_original_migrations_reject_all_null_bypasses(self):
        self.create_base_club_post()
        post_result = self.run_migration(POST_MIGRATION)
        self.assertEqual(0, post_result.returncode, post_result.stdout + post_result.stderr)
        governance_result = self.run_migration(GOVERNANCE_MIGRATION)
        self.assertEqual(
            0, governance_result.returncode,
            governance_result.stdout + governance_result.stderr)

        self.assert_all_null_bypasses_rejected("original")
        self.assertEqual((0, 0), self.read_row_counts())

    def test_old_constraints_allow_null_then_repair_rejects_and_replays(self):
        self.create_old_constraint_schema()
        self.insert_all_null_bypasses("old")
        self.assertEqual((1, 2), self.read_row_counts())
        old_clauses = self.read_constraint_clauses()

        self.delete_all_rows()
        self.assertEqual((0, 0), self.read_row_counts())
        first = self.run_migration(REPAIR_MIGRATION)
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self.assertEqual(
            2, first.stdout.count("断言通过:scalar-true; readback=1"), first.stdout)
        self.assertNotEqual(old_clauses, self.read_constraint_clauses())
        self.assert_all_null_bypasses_rejected("repaired")

        replay = self.run_migration(REPAIR_MIGRATION)
        self.assertEqual(0, replay.returncode, replay.stdout + replay.stderr)
        self.assertEqual(
            2, replay.stdout.count("断言通过:scalar-true; readback=1"), replay.stdout)
        self.assert_all_null_bypasses_rejected("replayed")
        self.assertEqual((0, 0), self.read_row_counts())

    def test_invalid_preimage_fails_before_ddl_and_preserves_old_state(self):
        self.create_old_constraint_schema()
        self.insert_all_null_bypasses("preimage")
        before_rows = self.read_row_counts()
        before_clauses = self.read_constraint_clauses()

        result = self.run_migration(REPAIR_MIGRATION)

        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn("第 1 句 runner 断言 scalar-true 失败", output)
        self.assertNotIn("[2/", output)
        self.assertEqual(before_rows, self.read_row_counts())
        self.assertEqual(before_clauses, self.read_constraint_clauses())

    def test_third_constraint_shapes_fail_before_ddl_and_are_preserved(self):
        third_shapes = (
            (
                "club_post",
                "ALTER TABLE club_post "
                "DROP CHECK chk_club_post_pinned, "
                "ADD CONSTRAINT chk_club_post_pinned "
                "CHECK (is_pinned IN (0,1) AND club_id > 0) ENFORCED",
            ),
            (
                "club_governance_case",
                "ALTER TABLE club_governance_case "
                "DROP CHECK chk_club_governance_case_relation, "
                "ADD CONSTRAINT chk_club_governance_case_relation "
                "CHECK (club_id > 0) ENFORCED",
            ),
        )

        for table_name, statement in third_shapes:
            with self.subTest(table=table_name):
                with self.admin.cursor() as cursor:
                    cursor.execute(f"DROP DATABASE IF EXISTS `{self.schema}`")
                    cursor.execute(
                        f"CREATE DATABASE `{self.schema}` "
                        "CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
                self.create_old_constraint_schema()

                connection = self.connect_schema()
                try:
                    with connection.cursor() as cursor:
                        cursor.execute(statement)
                finally:
                    connection.close()

                before_rows = self.read_row_counts()
                before_clauses = self.read_constraint_clauses()

                result = self.run_migration(REPAIR_MIGRATION)

                output = result.stdout + result.stderr
                self.assertEqual(1, result.returncode, output)
                self.assertIn("第 1 句 runner 断言 scalar-true 失败", output)
                self.assertNotIn("[2/", output)
                self.assertEqual(before_rows, self.read_row_counts())
                self.assertEqual(before_clauses, self.read_constraint_clauses())


if __name__ == "__main__":
    unittest.main()

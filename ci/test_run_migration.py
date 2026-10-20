import contextlib
import importlib.util
import io
import os
import pathlib
import sys
import tempfile
import types
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("run_migration.py")


def load_runner():
    spec = importlib.util.spec_from_file_location("chengyin_run_migration", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeMysqlError(Exception):
    pass


class FakeCursor:
    def __init__(self, fail_on=None, engine_rows=None, server_variables=None,
                 mysql_version="8.0.45", check_probe_error_code=3819,
                 check_probe_drop_error=None,
                 check_probe_cleanup_error_code=1146,
                 check_probe_drop_leaves_table=False,
                 mysql_version_comment="MySQL Community Server - GPL",
                 close_error=None, statement_results=None, statement_rowcounts=None):
        self.fail_on = fail_on
        self.engine_rows = engine_rows
        self.server_variables = server_variables or (
            "OFF", "OFF", "ROW", "REPEATABLE-READ")
        self.mysql_version = mysql_version
        self.mysql_version_comment = mysql_version_comment
        self.check_probe_error_code = check_probe_error_code
        self.check_probe_drop_error = check_probe_drop_error
        self.check_probe_cleanup_error_code = check_probe_cleanup_error_code
        self.check_probe_drop_leaves_table = check_probe_drop_leaves_table
        self.check_probe_exists = False
        self.close_error = close_error
        self.statement_results = statement_results or {}
        self.statement_rowcounts = statement_rowcounts or {}
        self.executed = []
        self.last_statement = None
        self.active_results = []
        self.active_results_defined = False
        self.rowcount = -1
        self.fetchone_count = 0

    def execute(self, statement, params=None):
        self.executed.append((statement, params))
        self.last_statement = statement
        self.active_results = list(self.statement_results.get(statement, ()))
        self.active_results_defined = statement in self.statement_results
        self.rowcount = self.statement_rowcounts.get(statement, -1)
        if statement == self.fail_on:
            raise RuntimeError("synthetic statement failure")
        if statement.startswith("CREATE TEMPORARY TABLE `__chengyin_check_probe_"):
            self.check_probe_exists = True
        if statement.startswith("INSERT INTO `__chengyin_check_probe_"):
            if self.check_probe_error_code is not None:
                raise FakeMysqlError(
                    self.check_probe_error_code, "synthetic CHECK rejection")
        if (statement.startswith("DROP TEMPORARY TABLE `__chengyin_check_probe_")
                and self.check_probe_drop_error):
            raise self.check_probe_drop_error
        if statement.startswith("DROP TEMPORARY TABLE `__chengyin_check_probe_"):
            if not self.check_probe_drop_leaves_table:
                self.check_probe_exists = False
        if statement.startswith("SELECT 1 FROM `__chengyin_check_probe_"):
            if self.check_probe_exists:
                return
            if self.check_probe_cleanup_error_code is not None:
                raise FakeMysqlError(
                    self.check_probe_cleanup_error_code,
                    "synthetic missing temporary table")

    def fetchone(self):
        self.fetchone_count += 1
        if "@@GLOBAL.gtid_mode" in self.last_statement:
            return self.server_variables
        if "@@version_comment" in self.last_statement:
            return (self.mysql_version, self.mysql_version_comment)
        if self.active_results:
            return self.active_results.pop(0)
        if self.active_results_defined:
            return None
        return (self.mysql_version,)

    def fetchall(self):
        return self.engine_rows or ()

    def close(self):
        if self.close_error:
            raise self.close_error


class FakeConnection:
    def __init__(self, fail_on=None, rollback_error=None, commit_error=None,
                 engine_rows=(("cms_topic", "InnoDB"),), server_variables=None,
                 mysql_version="8.0.45", check_probe_error_code=3819,
                 check_probe_drop_error=None,
                 check_probe_cleanup_error_code=1146,
                 check_probe_drop_leaves_table=False,
                 mysql_version_comment="MySQL Community Server - GPL",
                 cursor_close_error=None, connection_close_error=None,
                 statement_results=None, statement_rowcounts=None):
        self.cursor_instance = FakeCursor(
            fail_on=fail_on,
            engine_rows=engine_rows,
            server_variables=server_variables,
            mysql_version=mysql_version,
            check_probe_error_code=check_probe_error_code,
            check_probe_drop_error=check_probe_drop_error,
            check_probe_cleanup_error_code=check_probe_cleanup_error_code,
            check_probe_drop_leaves_table=check_probe_drop_leaves_table,
            mysql_version_comment=mysql_version_comment,
            close_error=cursor_close_error,
            statement_results=statement_results,
            statement_rowcounts=statement_rowcounts,
        )
        self.rollback_error = rollback_error
        self.commit_error = commit_error
        self.connection_close_error = connection_close_error
        self.autocommit_values = []
        self.begin_count = 0
        self.commit_count = 0
        self.rollback_count = 0

    def cursor(self):
        return self.cursor_instance

    def autocommit(self, value):
        self.autocommit_values.append(value)

    def begin(self):
        self.begin_count += 1

    def commit(self):
        self.commit_count += 1
        if self.commit_error:
            raise self.commit_error

    def rollback(self):
        self.rollback_count += 1
        if self.rollback_error:
            raise self.rollback_error

    def close(self):
        if self.connection_close_error:
            raise self.connection_close_error


class TransactionCliTest(unittest.TestCase):
    def invoke(self, statements, connection):
        runner = load_runner()
        with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
            sql_file.write("-- runner: requires=transaction\nSELECT 1;")
            sql_file.flush()
            argv = [
                "run_migration.py", "--config", "/tmp/unit-scratch.yml",
                "--sql", sql_file.name, "--transaction",
            ]
            output = io.StringIO()
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(runner, "connect", return_value=(connection, "scratch")), \
                    mock.patch.object(runner, "load_split_statements",
                                      return_value=(lambda _: statements, "test-splitter")), \
                    contextlib.redirect_stdout(output):
                try:
                    runner.main()
                    exit_code = None
                except SystemExit as raised:
                    exit_code = raised.code
        return exit_code, output.getvalue()

    def test_statement_failure_rolls_back_once_and_never_commits(self):
        failing_statement = "INSERT INTO cms_topic(id) VALUES (99)"
        connection = FakeConnection(fail_on=failing_statement)

        exit_code, output = self.invoke(
            ["UPDATE cms_topic SET product_type = 1 WHERE id = 23", failing_statement], connection)

        self.assertEqual(1, exit_code)
        self.assertEqual([False], connection.autocommit_values)
        self.assertEqual(1, connection.begin_count)
        self.assertEqual(1, connection.rollback_count)
        self.assertEqual(0, connection.commit_count)
        self.assertIn("ROLLBACK OK", output)

    def test_success_commits_once_and_never_rolls_back(self):
        connection = FakeConnection()

        exit_code, output = self.invoke([
            "CREATE TEMPORARY TABLE migration_guard(value INT)",
            "INSERT INTO migration_guard(value) VALUES (1)",
            "UPDATE cms_topic SET product_type = 1 WHERE id = 23",
            "DROP TEMPORARY TABLE migration_guard",
        ], connection)

        self.assertIsNone(exit_code)
        self.assertEqual([False], connection.autocommit_values)
        self.assertEqual(1, connection.begin_count)
        self.assertEqual(0, connection.rollback_count)
        self.assertEqual(1, connection.commit_count)
        self.assertIn("COMMIT OK", output)

    def test_rollback_receipt_failure_is_unknown_and_forbids_retry(self):
        failing_statement = "INSERT INTO cms_topic(id) VALUES (99)"
        connection = FakeConnection(
            fail_on=failing_statement,
            rollback_error=RuntimeError("connection lost during rollback"),
        )

        exit_code, output = self.invoke(
            ["UPDATE cms_topic SET product_type = 1 WHERE id = 23", failing_statement], connection)

        self.assertEqual(2, exit_code)
        self.assertEqual(1, connection.rollback_count)
        self.assertEqual(0, connection.commit_count)
        self.assertIn("ROLLBACK UNKNOWN", output)
        self.assertIn("禁止自动重跑", output)

    def test_commit_receipt_failure_is_unknown_and_forbids_retry(self):
        connection = FakeConnection(commit_error=RuntimeError("connection lost during commit"))

        exit_code, output = self.invoke(
            ["UPDATE cms_topic SET product_type = 1 WHERE id = 23"], connection)

        self.assertEqual(2, exit_code)
        self.assertEqual(0, connection.rollback_count)
        self.assertEqual(1, connection.commit_count)
        self.assertIn("COMMIT UNKNOWN", output)
        self.assertIn("禁止自动重跑", output)

    def test_non_innodb_reference_is_rejected_before_begin_or_migration_execution(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        connection = FakeConnection(engine_rows=(("cms_topic", "MyISAM"),))

        exit_code, output = self.invoke([statement], connection)

        self.assertEqual(1, exit_code)
        self.assertEqual(0, connection.begin_count)
        self.assertEqual(0, connection.commit_count)
        self.assertEqual(0, connection.rollback_count)
        self.assertNotIn((statement, None), connection.cursor_instance.executed)
        self.assertIn("非 InnoDB", output)

    def test_check_probe_rejects_invalid_value_and_confirms_drop_in_same_session(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        connection = FakeConnection()

        exit_code, output = self.invoke([statement], connection)

        self.assertIsNone(exit_code)
        probe_statements = [
            sql for sql, _ in connection.cursor_instance.executed
            if "__chengyin_check_probe_" in sql
        ]
        self.assertEqual(4, len(probe_statements))
        self.assertTrue(probe_statements[0].startswith("CREATE TEMPORARY TABLE"))
        self.assertTrue(probe_statements[1].startswith("INSERT INTO"))
        self.assertTrue(probe_statements[2].startswith("DROP TEMPORARY TABLE"))
        self.assertTrue(probe_statements[3].startswith("SELECT 1 FROM"))
        self.assertIn("CHECK probe=3819", output)
        self.assertIn("cleanup=1146", output)

    def test_check_probe_uses_distinct_bounded_identifiers(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        probe_names = []
        for _ in range(2):
            connection = FakeConnection()

            exit_code, _ = self.invoke([statement], connection)

            self.assertIsNone(exit_code)
            create_statement = next(
                sql for sql, _ in connection.cursor_instance.executed
                if sql.startswith("CREATE TEMPORARY TABLE `__chengyin_check_probe_"))
            probe_name = create_statement.split("`", 2)[1]
            self.assertRegex(probe_name, r"^__chengyin_check_probe_[0-9a-f]{16}$")
            self.assertLessEqual(len(probe_name), 64)
            probe_names.append(probe_name)
        self.assertEqual(2, len(set(probe_names)))

    def test_check_probe_fails_closed_when_invalid_value_is_accepted(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        connection = FakeConnection(check_probe_error_code=None)

        exit_code, output = self.invoke([statement], connection)

        self.assertEqual(1, exit_code)
        self.assertEqual(0, connection.begin_count)
        self.assertNotIn((statement, None), connection.cursor_instance.executed)
        self.assertTrue(connection.cursor_instance.executed[-2][0].startswith(
            "DROP TEMPORARY TABLE"))
        self.assertTrue(connection.cursor_instance.executed[-1][0].startswith(
            "SELECT 1 FROM"))
        self.assertIn("非法值未被 CHECK 拒绝", output)

    def test_check_probe_fails_closed_when_mysql_error_code_drifts(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        connection = FakeConnection(check_probe_error_code=4025)

        exit_code, output = self.invoke([statement], connection)

        self.assertEqual(1, exit_code)
        self.assertEqual(0, connection.begin_count)
        self.assertNotIn((statement, None), connection.cursor_instance.executed)
        self.assertTrue(connection.cursor_instance.executed[-2][0].startswith(
            "DROP TEMPORARY TABLE"))
        self.assertTrue(connection.cursor_instance.executed[-1][0].startswith(
            "SELECT 1 FROM"))
        self.assertIn("错误码 4025", output)
        self.assertIn("预期 3819", output)

    def test_check_probe_cleanup_failure_is_rejected_before_begin(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        cases = (
            ({"check_probe_drop_error": RuntimeError("synthetic DROP failure")},
             ("临时表清理失败", "synthetic DROP failure")),
            ({"check_probe_drop_leaves_table": True},
             ("DROP 后仍可查询",)),
            ({"check_probe_cleanup_error_code": 1051},
             ("错误码 1051", "预期 1146")),
        )
        for connection_args, expected_messages in cases:
            with self.subTest(connection_args=connection_args):
                connection = FakeConnection(**connection_args)

                exit_code, output = self.invoke([statement], connection)

                self.assertEqual(1, exit_code)
                self.assertEqual(0, connection.begin_count)
                self.assertNotIn((statement, None), connection.cursor_instance.executed)
                for message in expected_messages:
                    self.assertIn(message, output)

    def test_oracle_mysql_8045_allows_check_probe(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        connection = FakeConnection(mysql_version="8.0.45")

        exit_code, output = self.invoke([statement], connection)

        self.assertIsNone(exit_code)
        self.assertEqual(1, connection.begin_count)
        self.assertIn("CHECK probe=3819", output)
        self.assertIn("cleanup=1146", output)

    def test_unsupported_mysql_versions_are_rejected_before_probe_and_begin(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        # ★ 2026-08-19：("8.0.45", "Source distribution") 从**拒绝**清单里挪走了 ——
        #   那正是生产自己的 identity(宝塔自编译)，旧判据把生产挡在门外。
        #   现在它进了下面的 test_production_source_build_identity_is_accepted。
        #   期望的错误理由逐条钉死，防止「红了但红错了原因」。
        cases = (
            ("5.7.44", "MySQL Community Server - GPL", "版本不符"),
            ("8.0.44", "MySQL Community Server - GPL", "版本不符"),
            ("8.0.46", "MySQL Community Server - GPL", "版本不符"),
            ("8.4.0", "MySQL Community Server - GPL", "版本不符"),
            ("9.7.1", "MySQL Community Server - GPL", "版本不符"),
            ("9.7.1", "Homebrew", "版本不符"),
            ("8.0.45-fork", "MySQL Community Server - GPL", "版本不符"),
            ("8.0.45-MariaDB", "MariaDB Server", "mariadb"),
            ("10.11.4-MariaDB", "MariaDB Server", "mariadb"),
            # 自编译的 MariaDB 同样自称 Source distribution，只有版本串暴露血统。
            ("8.0.45-MariaDB", "Source distribution", "mariadb"),
            ("8.0.45", "Percona Server (GPL)", "percona"),
            ("8.0.45", "MySQL Community Server - Percona", "percona"),
            ("8.0.45", "Aurora (MySQL)", "aurora"),
            # 读不到 / 空 ⇒ 必须红：没验 ≠ 没问题。
            ("8.0.45", "", "为空或读不到"),
            ("8.0.45", "   ", "为空或读不到"),
            ("8.0.45", None, "为空或读不到"),
        )
        for mysql_version, version_comment, expected_reason in cases:
            with self.subTest(
                    mysql_version=mysql_version, version_comment=version_comment):
                connection = FakeConnection(
                    mysql_version=mysql_version,
                    mysql_version_comment=version_comment)

                exit_code, output = self.invoke([statement], connection)

                self.assertEqual(1, exit_code)
                self.assertEqual(0, connection.begin_count)
                self.assertFalse(any(
                    "__chengyin_check_probe_" in sql
                    for sql, _ in connection.cursor_instance.executed
                ))
                self.assertNotIn((statement, None), connection.cursor_instance.executed)
                self.assertIn("Oracle MySQL 8.0.45", output)
                self.assertIn(expected_reason, output)

    def test_gtid_incompatible_temporary_table_transaction_is_rejected_before_begin(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        connection = FakeConnection(
            server_variables=("ON", "ON", "MIXED", "REPEATABLE-READ"))

        exit_code, output = self.invoke([
            "CREATE TEMPORARY TABLE migration_guard(value INT)",
            statement,
        ], connection)

        self.assertEqual(1, exit_code)
        self.assertEqual(0, connection.begin_count)
        self.assertNotIn((statement, None), connection.cursor_instance.executed)
        self.assertIn("GTID", output)
        self.assertIn("binlog_format=ROW", output)

    def test_gtid_with_row_binlog_allows_temporary_table_transaction(self):
        connection = FakeConnection(
            server_variables=("ON", "ON", "ROW", "REPEATABLE-READ"))

        exit_code, output = self.invoke([
            "CREATE TEMPORARY TABLE migration_guard(value INT)",
            "UPDATE cms_topic SET product_type = 1 WHERE id = 23",
            "DROP TEMPORARY TABLE migration_guard",
        ], connection)

        self.assertIsNone(exit_code)
        self.assertEqual(1, connection.begin_count)
        self.assertIn("gtid_mode=ON", output)

    def test_all_read_and_write_sources_must_be_proven_innodb(self):
        connection = FakeConnection(engine_rows=(
            ("cms_topic", "InnoDB"),
            ("oms_ticket", "InnoDB"),
            ("topic_snapshot", "InnoDB"),
        ))
        statement = (
            "INSERT INTO topic_snapshot(topic_id) "
            "SELECT t.id FROM cms_topic t STRAIGHT_JOIN oms_ticket o ON o.owner_id=t.id"
        )

        exit_code, _ = self.invoke([statement], connection)

        self.assertIsNone(exit_code)
        engine_queries = [
            params for sql, params in connection.cursor_instance.executed
            if "INFORMATION_SCHEMA.TABLES" in sql
        ]
        self.assertEqual(
            [("scratch", "cms_topic", "oms_ticket", "topic_snapshot")],
            engine_queries,
        )

    def test_implicit_commit_and_transaction_control_are_rejected_before_connect(self):
        unsafe_statements = [
            "CREATE TABLE durable_table(id INT)",
            "ALTER TABLE cms_topic ADD COLUMN unsafe INT",
            "DROP TABLE cms_topic",
            "TRUNCATE TABLE cms_topic",
            "RENAME TABLE cms_topic TO cms_topic_old",
            "LOCK TABLES cms_topic WRITE",
            "UNLOCK TABLES",
            "START TRANSACTION",
            "BEGIN",
            "COMMIT",
            "ROLLBACK",
            "SET autocommit = 0",
            "/*! SET autocommit = 0 */",
            "INSERT IGNORE INTO audit_myisam SELECT * FROM cms_topic",
            "INSERT LOW_PRIORITY INTO audit_myisam SELECT * FROM cms_topic",
            "REPLACE INTO audit_myisam SELECT * FROM cms_topic",
            "DELETE FROM cms_topic WHERE id = 23",
            "UPDATE cms_topic t, audit_myisam a SET t.status=1,a.x=1",
            "UPDATE LOW_PRIORITY cms_topic SET status=1",
            "UPDATE IGNORE cms_topic SET status=1",
            "INSERT INTO audit_log(id) SELECT t.id FROM cms_topic t, audit_myisam a",
        ]
        for statement in unsafe_statements:
            with self.subTest(statement=statement):
                runner = load_runner()
                with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
                    sql_file.write("SELECT 1;")
                    sql_file.flush()
                    argv = [
                        "run_migration.py", "--config", "/tmp/unit-scratch.yml",
                        "--sql", sql_file.name, "--transaction",
                    ]
                    connect = mock.Mock(return_value=(FakeConnection(), "scratch"))
                    with mock.patch.object(sys, "argv", argv), \
                            mock.patch.object(runner, "connect", connect), \
                            mock.patch.object(runner, "load_split_statements",
                                              return_value=(lambda _: [statement], "test-splitter")):
                        with self.assertRaises(SystemExit):
                            runner.main()
                connect.assert_not_called()

    def test_dry_run_validates_and_prints_without_connecting(self):
        runner = load_runner()
        statements = [
            "CREATE TEMPORARY TABLE migration_guard(value INT)",
            "DROP TEMPORARY TABLE migration_guard",
        ]
        with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
            sql_file.write("SELECT 1;")
            sql_file.flush()
            argv = [
                "run_migration.py", "--sql", sql_file.name, "--transaction", "--dry-run"]
            connect = mock.Mock(return_value=(FakeConnection(), "scratch"))
            output = io.StringIO()
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(runner, "connect", connect), \
                    mock.patch.object(runner, "load_split_statements",
                                      return_value=(lambda _: statements, "test-splitter")), \
                    contextlib.redirect_stdout(output):
                runner.main()

        connect.assert_not_called()
        self.assertEqual(2, output.getvalue().count("(dry-run 未执行)"))
        self.assertIn("不证明服务器变量", output.getvalue())

    def test_unquoted_semicolon_is_rejected_but_literals_and_comments_are_safe(self):
        runner = load_runner()
        with self.assertRaisesRegex(ValueError, "引号外分号"):
            runner.validate_transaction_statements([
                "UPDATE cms_topic SET product_type=1; DROP TABLE cms_topic"
            ])

        runner.validate_transaction_statements([
            "UPDATE cms_topic SET update_by='safe;value' /* safe;comment */ WHERE id=23"
        ])

    def test_executable_comment_inside_allowed_dml_is_rejected(self):
        runner = load_runner()
        with self.assertRaisesRegex(ValueError, "可执行注释"):
            runner.validate_transaction_statements([
                "UPDATE cms_topic SET product_type=1 "
                "/*!80000 , status=9 */ WHERE id=23"
            ])

    def test_update_without_where_is_rejected_before_connect(self):
        runner = load_runner()
        with self.assertRaisesRegex(ValueError, "WHERE"):
            runner.validate_transaction_statements([
                "UPDATE cms_topic SET product_type=1"
            ])

    def test_single_inner_join_update_with_where_is_accepted(self):
        runner = load_runner()

        # 白名单里唯一被允许的多表 UPDATE 形状:带别名与不带别名都得照旧放行,
        # 否则收紧别名槽会误伤 migration_20260815_tandianri_h8_rate_convergence.sql 自己那条搬钱 UPDATE。
        for first_table, target in (("settlement_detail AS detail", "detail"),
                                    ("settlement_detail", "settlement_detail")):
            with self.subTest(first_table=first_table):
                runner.validate_transaction_statements([
                    "UPDATE " + first_table + " "
                    "JOIN tmp_move AS move ON move.merchant_detail_id = " + target + ".id "
                    "SET " + target + ".settlement_amount = "
                    + target + ".settlement_amount + move.amount "
                    "WHERE " + target + ".status = 0"
                ])

    def test_join_update_shapes_outside_the_narrow_allowlist_are_rejected(self):
        runner = load_runner()
        # ★ 2026-08-16:本用例原先 5 个形状【全部带 AS detail】,别名槽被 detail 占掉、
        # LEFT/RIGHT/CROSS 无处安放才失配 —— 洞恰好被负控绕开。不带别名时
        # `UPDATE t LEFT JOIN x ON …` 的 LEFT 会被当成第一张表的别名吃掉,
        # 剩下的 ` JOIN x ON … SET …` 正好命中 INNER JOIN 分支被【放行】。
        # 多表 LEFT JOIN UPDATE 是能静默写坏账本的形状(未匹配的左表行照样被更新、右表列取 NULL),
        # 所以每个形状必须【带别名 / 不带别名】各跑一次。
        def shapes(first_table, target):
            head = "UPDATE " + first_table + " "
            on = "ON move.merchant_detail_id = " + target + ".id "
            body = "SET " + target + ".settlement_amount = 1 "
            where = "WHERE " + target + ".status = 0"
            joined = {
                "无 WHERE": head + "JOIN tmp_move AS move " + on + body.rstrip(),
                "两次 JOIN": head + "JOIN tmp_move AS move " + on
                             + "JOIN tmp_other AS other ON other.id = " + target + ".id "
                             + body + where,
                "复合 ON": head + "JOIN tmp_move AS move " + on
                           + "OR move.creator_detail_id = " + target + ".id "
                           + body + where,
            }
            for join_keyword in ("LEFT JOIN", "LEFT OUTER JOIN", "RIGHT JOIN",
                                 "RIGHT OUTER JOIN", "CROSS JOIN", "NATURAL JOIN",
                                 "STRAIGHT_JOIN"):
                joined[join_keyword] = head + join_keyword + " tmp_move AS move " + on + body + where
            return joined

        rejected = {}
        for alias_label, (first_table, target) in (
                ("有别名", ("settlement_detail AS detail", "detail")),
                ("无别名", ("settlement_detail", "settlement_detail"))):
            for label, statement in shapes(first_table, target).items():
                rejected[label + "/" + alias_label] = statement
        rejected["逗号多表/有别名"] = ("UPDATE settlement_detail detail, tmp_move move "
                                       "SET detail.settlement_amount = 1 WHERE move.id = detail.id")
        rejected["逗号多表/无别名"] = ("UPDATE settlement_detail, tmp_move "
                                       "SET settlement_detail.settlement_amount = 1 "
                                       "WHERE tmp_move.id = settlement_detail.id")

        for label, statement in rejected.items():
            with self.subTest(shape=label):
                with self.assertRaises(ValueError):
                    runner.validate_transaction_statements([statement])

    def test_temporary_shadow_is_applied_in_statement_order(self):
        runner = load_runner()
        statements = [
            "UPDATE cms_topic SET product_type=1 WHERE id=23",
            "CREATE TEMPORARY TABLE cms_topic(id BIGINT)",
            "INSERT INTO cms_topic(id) VALUES (1)",
            "DROP TEMPORARY TABLE cms_topic",
            "INSERT INTO audit_log(topic_id) SELECT id FROM cms_topic",
        ]

        self.assertEqual(
            ["audit_log", "cms_topic"],
            runner.referenced_persistent_tables(statements, "scratch"),
        )

    def test_multi_table_temporary_drop_restores_every_persistent_shadow(self):
        runner = load_runner()
        statements = [
            "CREATE TEMPORARY TABLE tmp_a(id BIGINT)",
            "CREATE TEMPORARY TABLE oms_ticket(id BIGINT)",
            "DROP TEMPORARY TABLE tmp_a, oms_ticket",
            "UPDATE cms_topic SET product_type=1 WHERE id=23",
            "INSERT INTO oms_ticket(id) VALUES (1)",
        ]

        self.assertEqual(
            ["cms_topic", "oms_ticket"],
            runner.referenced_persistent_tables(statements, "scratch"),
        )

    def test_only_temporary_tables_are_rejected_before_begin(self):
        connection = FakeConnection(engine_rows=())
        statements = [
            "CREATE TEMPORARY TABLE tmp_a(id BIGINT)",
            "INSERT INTO tmp_a(id) VALUES (1)",
            "DROP TEMPORARY TABLE tmp_a",
        ]

        exit_code, output = self.invoke(statements, connection)

        self.assertEqual(1, exit_code)
        self.assertEqual(0, connection.begin_count)
        self.assertIn("未识别出任何持久表", output)

    def test_table_parser_ignores_literals_and_on_duplicate_but_covers_straight_join(self):
        runner = load_runner()
        statements = [
            "INSERT INTO sys_job(job_id,remark) VALUES (1,'update evil_tbl; from ghost') "
            "ON DUPLICATE KEY UPDATE status='0'",
            "INSERT INTO audit_log(topic_id) SELECT t.id FROM cms_topic t "
            "STRAIGHT_JOIN oms_ticket o ON o.owner_id=t.id",
        ]

        self.assertEqual(
            ["audit_log", "cms_topic", "oms_ticket", "sys_job"],
            runner.referenced_persistent_tables(statements, "scratch"),
        )

    def payee_key_tables(self):
        return [
            "CREATE TEMPORARY TABLE tmp_cy_payee_roles(role_key VARCHAR(100), role_id BIGINT)",
            "CREATE TEMPORARY TABLE tmp_cy_payee_menus(perms VARCHAR(100), menu_id BIGINT)",
        ]

    def payee_delete(self, role="cy_operator", permission="business:clubpayee:verify"):
        return ("DELETE FROM sys_role_menu WHERE role_id = "
                "(SELECT role_id FROM tmp_cy_payee_roles WHERE role_key = '" + role + "') "
                "AND menu_id = (SELECT menu_id FROM tmp_cy_payee_menus WHERE perms = '"
                + permission + "')")

    def test_only_two_payee_separation_deletes_commit_with_declared_temporary_keys(self):
        for role, permission in (("cy_operator", "business:clubpayee:verify"),
                                 ("cy_finance", "business:clubpayee:payout")):
            with self.subTest(role=role):
                statement = self.payee_delete(role, permission)
                connection = FakeConnection(engine_rows=(("sys_role_menu", "InnoDB"),))
                exit_code, output = self.invoke(self.payee_key_tables() + [statement], connection)
                self.assertIsNone(exit_code)
                self.assertEqual(1, connection.commit_count)
                self.assertEqual(0, connection.rollback_count)
                self.assertIn((statement, None), connection.cursor_instance.executed)
                self.assertIn("COMMIT OK", output)

    def test_payee_delete_rejects_every_broader_shape_before_connect(self):
        runner = load_runner()
        exact = self.payee_delete()
        rejected = [
            "DELETE FROM sys_role_menu",
            "DELETE FROM sys_role_menu WHERE 1=1",
            "DELETE FROM sys_role_menu WHERE role_id=100 AND menu_id=2543",
            exact + " OR 1=1",
            exact.replace(" AND menu_id", " OR menu_id"),
            exact.replace("role_id =", "role_id >"),
            exact.replace("sys_role_menu WHERE", "sys_role_menu rm WHERE"),
            exact.replace("DELETE FROM", "DELETE rm FROM"),
            exact.replace("DELETE FROM sys_role_menu", "DELETE FROM sys_role_menu USING sys_role_menu"),
            exact.replace("DELETE FROM sys_role_menu", "DELETE FROM sys_role_menu JOIN sys_role"),
            exact.replace("DELETE FROM sys_role_menu", "DELETE FROM sys_role_menu, sys_role"),
            exact.replace("DELETE FROM sys_role_menu", "DELETE FROM other.sys_role_menu"),
            exact.replace("tmp_cy_payee_roles WHERE", "other.tmp_cy_payee_roles WHERE"),
            exact.replace("tmp_cy_payee_roles", "unreviewed_roles"),
            exact.replace("cy_operator", "cy_support"),
            exact.replace("cy_operator", "CY_OPERATOR"),
            exact.replace("business:clubpayee:verify", "business:clubpayee:payout"),
            exact.replace("business:clubpayee:verify", "business:clubpayable:confirm"),
            exact + " LIMIT 1",
            exact + "; DELETE FROM sys_role_menu",
            exact.replace(" WHERE role_id", " /*!80000 LOW_PRIORITY */ WHERE role_id", 1),
            "SELECT role_id FROM tmp_cy_payee_roles",
        ]
        for statement in rejected:
            with self.subTest(statement=statement):
                with self.assertRaises(ValueError):
                    runner.validate_transaction_statements(self.payee_key_tables() + [statement])

    def test_payee_delete_requires_both_temporary_keys_still_live(self):
        runner = load_runner()
        for prefix in ([], self.payee_key_tables()[:1],
                       self.payee_key_tables() + ["DROP TEMPORARY TABLE tmp_cy_payee_menus"],
                       self.payee_key_tables() + ["DROP TEMPORARY TABLE tmp_cy_payee_roles, tmp_cy_payee_menus"],
                       self.payee_key_tables() + ["CREATE TEMPORARY TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT)"]):
            with self.subTest(prefix=prefix):
                with self.assertRaises(ValueError):
                    runner.validate_transaction_statements(prefix + [self.payee_delete()])

    def test_payee_delete_target_must_be_innodb_before_begin(self):
        for rows, message in (((("sys_role_menu", "MyISAM"),), "非 InnoDB"),
                              ((("some_other_table", "InnoDB"),), "表不存在")):
            with self.subTest(rows=rows):
                connection = FakeConnection(engine_rows=rows)
                statement = self.payee_delete()
                exit_code, output = self.invoke(self.payee_key_tables() + [statement], connection)
                self.assertEqual(1, exit_code)
                self.assertEqual(0, connection.begin_count)
                self.assertNotIn((statement, None), connection.cursor_instance.executed)
                self.assertIn(message, output)

    def test_failure_after_payee_delete_rolls_back_without_commit(self):
        statement = self.payee_delete()
        later = "INSERT INTO sys_role_menu(role_id,menu_id) VALUES (100,999)"
        connection = FakeConnection(fail_on=later, engine_rows=(("sys_role_menu", "InnoDB"),))
        exit_code, output = self.invoke(self.payee_key_tables() + [statement, later], connection)
        self.assertEqual(1, exit_code)
        self.assertEqual(1, connection.begin_count)
        self.assertEqual(1, connection.rollback_count)
        self.assertEqual(0, connection.commit_count)
        self.assertIn((statement, None), connection.cursor_instance.executed)
        self.assertIn("ROLLBACK OK", output)

    def test_payee_delete_engine_references_exclude_only_live_temporary_keys(self):
        runner = load_runner()
        self.assertEqual(["sys_role_menu"], runner.referenced_persistent_tables(
            self.payee_key_tables() + [self.payee_delete()], "scratch"))

    def test_unknown_exit_two_survives_cursor_and_connection_close_errors(self):
        failing_statement = "INSERT INTO cms_topic(id) VALUES (99)"
        connection = FakeConnection(
            fail_on=failing_statement,
            rollback_error=RuntimeError("connection lost during rollback"),
            cursor_close_error=RuntimeError("cursor close failed"),
            connection_close_error=RuntimeError("connection close failed"),
        )

        exit_code, output = self.invoke([failing_statement], connection)

        self.assertEqual(2, exit_code)
        self.assertIn("ROLLBACK UNKNOWN", output)
        self.assertIn("cursor关闭失败（保留原始退出语义）", output)
        self.assertIn("connection关闭失败（保留原始退出语义）", output)

    def test_successful_commit_does_not_hide_cursor_close_failure(self):
        connection = FakeConnection(
            cursor_close_error=RuntimeError("cursor close failed"))

        with self.assertRaisesRegex(RuntimeError, "cursor close failed"):
            self.invoke(
                ["UPDATE cms_topic SET product_type = 1 WHERE id = 23"],
                connection,
            )

        self.assertEqual(1, connection.commit_count)
        self.assertEqual(0, connection.rollback_count)


class RequiredModeDeclarationTest(unittest.TestCase):
    def invoke_sql(self, sql_text, extra_args):
        runner = load_runner()
        connection = FakeConnection()
        connect = mock.Mock(return_value=(connection, "scratch"))
        with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
            sql_file.write(sql_text)
            sql_file.flush()
            argv = [
                "run_migration.py", "--config", "/tmp/unit-scratch.yml",
                "--sql", sql_file.name, *extra_args,
            ]
            errors = io.StringIO()
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(runner, "connect", connect), \
                    mock.patch.object(runner, "load_split_statements", return_value=(
                        lambda _: ["UPDATE cms_topic SET product_type=1"], "test-splitter")), \
                    contextlib.redirect_stderr(errors):
                try:
                    runner.main()
                    exit_code = None
                except SystemExit as raised:
                    exit_code = raised.code
        return exit_code, connect, errors.getvalue()

    def test_required_transaction_declaration_rejects_missing_flag_before_connect(self):
        exit_code, connect, errors = self.invoke_sql(
            "-- runner: requires=transaction\nUPDATE cms_topic SET product_type=1;", [])

        self.assertNotIn(exit_code, (None, 0))
        connect.assert_not_called()
        self.assertIn("requires=transaction", errors)

    def test_ordinary_migration_without_declaration_is_not_forced_into_transaction(self):
        exit_code, connect, _ = self.invoke_sql(
            "UPDATE cms_topic SET product_type=1;", [])

        self.assertIsNone(exit_code)
        connect.assert_called_once()


class AllWriteProvenanceCliTest(unittest.TestCase):
    def invoke(self, argv, connection, statements=None):
        runner = load_runner()
        output = io.StringIO()
        patches = [
            mock.patch.object(sys, "argv", ["run_migration.py", *argv]),
            mock.patch.object(runner, "connect", return_value=(connection, "scratch")),
        ]
        if statements is not None:
            patches.append(mock.patch.object(
                runner,
                "load_split_statements",
                return_value=(lambda _: statements, "test-splitter"),
            ))
        with contextlib.ExitStack() as stack:
            for patcher in patches:
                stack.enter_context(patcher)
            stack.enter_context(contextlib.redirect_stdout(output))
            try:
                runner.main()
                exit_code = None
            except SystemExit as raised:
                exit_code = raised.code
        return exit_code, output.getvalue()

    def test_production_source_build_identity_is_accepted_on_all_write_paths(self):
        """生产真身(8.0.45 + Source distribution)必须能走到语句执行。

        ★ 这条是今天真跑落库撞出来的：旧判据要求 version_comment 含
          "MySQL Community Server"，把宝塔自编译的生产**挡在自己门外**，
          第 1 条迁移就没跑成。删掉这个用例 = 那个错判会原样回来。
        """
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
            sql_file.write(statement + ";")
            sql_file.flush()
            write_paths = (
                (["--config", "/tmp/unit-scratch.yml", "--exec", statement], None),
                (["--config", "/tmp/unit-scratch.yml", "--sql", sql_file.name],
                 [statement]),
            )
            for argv, statements in write_paths:
                with self.subTest(mode=argv[-2]):
                    connection = FakeConnection(
                        mysql_version="8.0.45",
                        mysql_version_comment="Source distribution")

                    exit_code, output = self.invoke(
                        argv, connection, statements=statements)

                    self.assertIsNone(exit_code, output)
                    self.assertEqual(
                        1, connection.cursor_instance.executed.count((statement, None)),
                        "生产 identity 必须放行到真正执行语句")

    def test_untrusted_servers_are_rejected_before_all_autocommit_write_paths(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
            sql_file.write(statement + ";")
            sql_file.flush()
            write_paths = (
                (["--config", "/tmp/unit-scratch.yml", "--exec", statement], None),
                (["--config", "/tmp/unit-scratch.yml", "--sql", sql_file.name],
                 [statement]),
            )
            identities = (
                ("8.0.45-MariaDB", "MariaDB Server"),
                ("8.0.45", "MySQL Community Server - MariaDB"),
                ("8.0.45-MariaDB", "Source distribution"),
                ("8.0.45", "Percona Server (GPL)"),
                ("8.0.45", "MySQL Community Server - Percona"),
                ("8.0.44", "MySQL Community Server - GPL"),
                ("8.0.45-fork", "MySQL Community Server - GPL"),
                ("8.0.45", ""),
                ("8.0.45", None),
            )
            for argv, statements in write_paths:
                for mysql_version, version_comment in identities:
                    with self.subTest(
                            mode=argv[-2], mysql_version=mysql_version,
                            version_comment=version_comment):
                        connection = FakeConnection(
                            mysql_version=mysql_version,
                            mysql_version_comment=version_comment)

                        exit_code, output = self.invoke(
                            argv, connection, statements=statements)

                        self.assertEqual(1, exit_code)
                        self.assertEqual(0, connection.begin_count)
                        self.assertFalse(any(
                            "__chengyin_check_probe_" in sql
                            for sql, _ in connection.cursor_instance.executed
                        ))
                        self.assertEqual(
                            0,
                            connection.cursor_instance.executed.count((statement, None)),
                        )
                        self.assertEqual(
                            [("SELECT VERSION(), @@version_comment", None)],
                            connection.cursor_instance.executed,
                        )
                        self.assertIn("Oracle MySQL 8.0.45", output)

    def test_trusted_comments_allow_autocommit_without_transaction_only_probe(self):
        statement = "UPDATE cms_topic SET product_type = 1 WHERE id = 23"
        for version_comment in (
                "MySQL Community Server - GPL", "MySQL Enterprise Server",
                # ★ 生产(宝塔自编译 8.0.45)的真实 version_comment，必须能走到语句执行。
                "Source distribution"):
            with self.subTest(version_comment=version_comment):
                connection = FakeConnection(
                    mysql_version_comment=version_comment)

                exit_code, _ = self.invoke(
                    ["--config", "/tmp/unit-scratch.yml", "--exec", statement],
                    connection,
                )

                self.assertIsNone(exit_code)
                self.assertEqual(0, connection.begin_count)
                self.assertFalse(any(
                    "__chengyin_check_probe_" in sql
                    for sql, _ in connection.cursor_instance.executed
                ))
                self.assertEqual(
                    1, connection.cursor_instance.executed.count((statement, None)))


class SshDestinationTest(unittest.TestCase):
    def test_empty_ssh_host_env_cannot_fall_back_to_local_mysql(self):
        runner = load_runner()
        pymysql_connect = mock.Mock(return_value=object())
        fake_pymysql = types.SimpleNamespace(connect=pymysql_connect)
        with mock.patch.object(
                runner, "load_db_config",
                return_value=("127.0.0.1", 3306, "chengyinhubdb", "dbuser", "<unit-placeholder>")), \
                mock.patch.object(runner, "_open_ssh_tunnel", return_value=13306) as tunnel, \
                mock.patch.dict(sys.modules, {"pymysql": fake_pymysql}), \
                mock.patch.dict(os.environ, {"CHENGYIN_DB_SSH_HOST": ""}):

            with self.assertRaises(SystemExit):
                runner.connect("/tmp/unit-scratch.yml")

        tunnel.assert_not_called()
        pymysql_connect.assert_not_called()


    def test_only_an_explicit_token_disables_the_tunnel(self):
        """★ 2026-08-19 加的「显式本机」档不许把 fail-closed 语义弄丢。

        判据分两半,缺哪半这道闸都会退化:
          · 显式 token(local/localhost/none/127.0.0.1/::1)⇒ 不开隧道,目标就是本机;
          · **未设置 / 空串 / 纯空白直接退出** —— 它们不是「选择本机」,
            而是「没说」,而没说时连上操作者自己的 MySQL 是本仓明令禁止的形态。
            (2026-10-19 起不再默认打到生产库主机:本仓公开,源码里不写生产地址。)
        """
        runner = load_runner()
        for token in ("local", "LOCAL", "localhost", "none", "127.0.0.1", "::1"):
            self.assertEqual("", runner.resolve_ssh_host(token),
                             f"显式本机 token {token!r} 没能关掉隧道")
        for silent in (None, "", "   "):
            with self.assertRaises(SystemExit,
                                   msg=f"{silent!r} 没有 fail-closed —— 沉默不是选择,不许回落到本机"):
                runner.resolve_ssh_host(silent)
        self.assertEqual("10.0.0.9", runner.resolve_ssh_host(" 10.0.0.9 "),
                         "显式给了别的跳板机却没被采用")

    def test_explicit_local_token_connects_directly_without_tunnel(self):
        """端到端:显式本机时 connect() 直连 127.0.0.1,一次隧道都不开。"""
        runner = load_runner()
        pymysql_connect = mock.Mock(return_value=object())
        fake_pymysql = types.SimpleNamespace(connect=pymysql_connect)
        with mock.patch.object(
                runner, "load_db_config",
                return_value=("127.0.0.1", 3306, "scratch_db", "root", "<unit-placeholder>")), \
                mock.patch.object(runner, "_open_ssh_tunnel") as tunnel, \
                mock.patch.dict(sys.modules, {"pymysql": fake_pymysql}), \
                mock.patch.dict(os.environ, {"CHENGYIN_DB_SSH_HOST": "local"}):
            _connection, db = runner.connect("/tmp/unit-scratch.yml")
        self.assertEqual("scratch_db", db)
        tunnel.assert_not_called()
        pymysql_connect.assert_called_once_with(
            host="127.0.0.1", port=3306, user="root", password="<unit-placeholder>",
            database="scratch_db", connect_timeout=8, autocommit=True)


class MutuallyExclusiveCliTest(unittest.TestCase):
    def test_dry_run_rejects_check_and_describe_before_connect(self):
        for read_args in (["--check"], ["--describe", "cms_topic"]):
            with self.subTest(read_args=read_args):
                runner = load_runner()
                connect = mock.Mock()
                errors = io.StringIO()
                with mock.patch.object(
                        sys, "argv", ["run_migration.py", "--dry-run", *read_args]), \
                        mock.patch.object(runner, "connect", connect), \
                        contextlib.redirect_stderr(errors):
                    with self.assertRaises(SystemExit) as raised:
                        runner.main()
                self.assertNotEqual(0, raised.exception.code)
                connect.assert_not_called()
                self.assertIn("--dry-run", errors.getvalue())


class ResultAssertionCliTest(unittest.TestCase):
    def invoke(self, statements, connection, transaction=False):
        runner = load_runner()
        with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
            sql_file.write("-- runner: requires=transaction\nSELECT 1;"
                           if transaction else "SELECT 1;")
            sql_file.flush()
            argv = [
                "run_migration.py", "--config", "/tmp/unit-scratch.yml",
                "--sql", sql_file.name,
            ]
            if transaction:
                argv.append("--transaction")
            output = io.StringIO()
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(runner, "connect", return_value=(connection, "scratch")), \
                    mock.patch.object(runner, "load_split_statements",
                                      return_value=(lambda _: statements, "test-splitter")), \
                    contextlib.redirect_stdout(output):
                try:
                    runner.main()
                    exit_code = None
                except SystemExit as raised:
                    exit_code = raised.code
        return exit_code, output.getvalue()

    def test_permission_insert_zero_rows_fails(self):
        statement = "-- runner: assert=rowcount-positive\nINSERT INTO sys_menu(menu_id) VALUES (1)"
        connection = FakeConnection(
            statement_rowcounts={statement: 0},
            engine_rows=(("sys_menu", "InnoDB"),),
        )

        exit_code, output = self.invoke([statement], connection)

        self.assertEqual(1, exit_code)
        self.assertIn("rowcount-positive", output)
        self.assertIn("0", output)

    def test_wrong_scalar_readback_fails(self):
        statement = "-- runner: assert=scalar-true\nSELECT 0 AS assertion_ok"
        connection = FakeConnection(statement_results={statement: [(0,)]})

        exit_code, output = self.invoke([statement], connection)

        self.assertEqual(1, exit_code)
        self.assertIn("scalar-true", output)

    def test_missing_index_readback_fails(self):
        statement = ("-- runner: assert=scalar-true\n"
                     "SELECT 0 AS idx_roam_session_started_at_exists")
        connection = FakeConnection(statement_results={statement: [(0,)]})

        exit_code, output = self.invoke([statement], connection)

        self.assertEqual(1, exit_code)
        self.assertIn("scalar-true", output)

    def test_scalar_true_readback_passes(self):
        statement = "-- runner: assert=scalar-true\nSELECT 1 AS assertion_ok"
        connection = FakeConnection(statement_results={statement: [(1,)]})

        exit_code, output = self.invoke([statement], connection)

        self.assertIsNone(exit_code)
        self.assertIn("断言通过", output)

    def test_ordinary_select_is_not_implicitly_asserted_or_fetched(self):
        statement = "SELECT 0 AS informational_only"
        connection = FakeConnection(statement_results={statement: [(0,)]})

        exit_code, _ = self.invoke([statement], connection)

        self.assertIsNone(exit_code)
        self.assertEqual([(0,)], connection.cursor_instance.active_results)

    def test_assertion_failure_in_transaction_rolls_back(self):
        statement = ("-- runner: assert=rowcount-positive\n"
                     "UPDATE cms_topic SET product_type = 1 WHERE id = 99")
        connection = FakeConnection(statement_rowcounts={statement: 0})

        exit_code, output = self.invoke([statement], connection, transaction=True)

        self.assertEqual(1, exit_code)
        self.assertEqual(1, connection.rollback_count)
        self.assertEqual(0, connection.commit_count)
        self.assertIn("ROLLBACK OK", output)


class ExplicitConfigCliTest(unittest.TestCase):
    def invoke(self, argv, statements=None):
        runner = load_runner()
        connection = FakeConnection()
        connect = mock.Mock(return_value=(connection, "scratch"))
        output = io.StringIO()
        errors = io.StringIO()
        patches = [
            mock.patch.object(sys, "argv", ["run_migration.py", *argv]),
            mock.patch.object(runner, "connect", connect),
        ]
        if statements is not None:
            patches.append(mock.patch.object(
                runner,
                "load_split_statements",
                return_value=(lambda _: statements, "test-splitter"),
            ))
        with contextlib.ExitStack() as stack:
            for patcher in patches:
                stack.enter_context(patcher)
            stack.enter_context(contextlib.redirect_stdout(output))
            stack.enter_context(contextlib.redirect_stderr(errors))
            try:
                runner.main()
                exit_code = None
            except SystemExit as raised:
                exit_code = raised.code
        return runner, connect, exit_code, output.getvalue(), errors.getvalue()

    def test_sql_write_without_explicit_config_is_rejected_before_connect(self):
        with tempfile.NamedTemporaryFile("w", suffix=".sql", encoding="utf-8") as sql_file:
            sql_file.write("UPDATE cms_topic SET product_type = 1;")
            sql_file.flush()
            _, connect, exit_code, _, errors = self.invoke(
                ["--sql", sql_file.name],
                statements=["UPDATE cms_topic SET product_type = 1"],
            )

        self.assertNotIn(exit_code, (None, 0))
        connect.assert_not_called()
        self.assertIn("必须显式传 --config", errors)

    def test_exec_write_without_explicit_config_is_rejected_before_connect(self):
        _, connect, exit_code, _, errors = self.invoke(
            ["--exec", "UPDATE cms_topic SET product_type = 1"])

        self.assertNotIn(exit_code, (None, 0))
        connect.assert_not_called()
        self.assertIn("必须显式传 --config", errors)

    def test_explicit_config_forms_enter_connect_seam_even_at_default_path(self):
        runner = load_runner()
        for config_args in (
                ["--config", runner.DEFAULT_CONFIG],
                [f"--config={runner.DEFAULT_CONFIG}"],
        ):
            with self.subTest(config_args=config_args):
                _, connect, exit_code, _, _ = self.invoke(
                    [*config_args, "--exec", "UPDATE cms_topic SET product_type = 1"])
                self.assertIsNone(exit_code)
                connect.assert_called_once_with(runner.DEFAULT_CONFIG)


class CommunityLegacyOwnerPreflightTest(unittest.TestCase):
    def test_comment_counter_scope_requires_the_complete_reviewed_transaction(self):
        runner = load_runner()
        from ci.sql_splitter import split_statements
        path = SCRIPT_PATH.parent.parent / 'chengyinhub-admin/sql/migration_community_legacy_comment_like_counts_20261015.sql'
        statements = split_statements(path.read_text())
        self.assertTrue(runner.exact_community_comment_counter_scope(statements, True))
        for candidate, transaction in (
                (statements, False), (statements[:1], True),
                (statements + ['SELECT 1'], True),
                (statements + ['DELETE FROM community_post_reference'], True),
                ([s.replace("action_type='LIKE'", "action_type='BOOKMARK'") for s in statements], True),
                ([s.replace('UPDATE community_post_comment', 'UPDATE other.community_post_comment') for s in statements], True)):
            self.assertFalse(runner.exact_community_comment_counter_scope(candidate, transaction))

    def test_comment_counter_scope_checks_identity_but_not_unrelated_snapshots(self):
        runner = load_runner()
        cursor = mock.Mock()
        cursor.fetchall.return_value = [(name,) for name in (
            'ums_creative_square', 'ums_creative_square_like', 'ums_comment', 'ums_comment_like',
            'community_post', 'community_post_comment', 'community_post_comment_action',
            'community_post_reference', 'community_post_media', 'community_post_member_action')]
        cursor.fetchone.return_value = (1,)
        runner.assert_community_legacy_owner_preflight(cursor, 'scratch', comment_counter=True)
        sql = '\n'.join(str(call.args[0]) for call in cursor.execute.call_args_list)
        self.assertIn('legacy-square-comment-', sql)
        self.assertIn('old_comment.member_id=n.author_id', sql)
        self.assertNotIn('community_post_reference', sql)
        self.assertNotIn('community_post_media', sql)
        self.assertNotIn('community_post_member_action', sql)
        cursor.reset_mock()
        runner.assert_community_legacy_counter_preflight(cursor, 'scratch', [], comment_counter=True)
        sql = '\n'.join(str(call.args[0]) for call in cursor.execute.call_args_list)
        self.assertIn("a.request_id LIKE 'legacy-comment-like-%'", sql)
        self.assertNotIn('community_post_member_action', sql)

    def test_actual_write_shapes_cover_renamed_sql_and_inline_extracts(self):
        runner = load_runner()
        for statement in (
            "INSERT IGNORE INTO community_post SELECT * FROM ums_creative_square",
            "INSERT IGNORE INTO community_post_media SELECT * FROM ums_creative_square",
            "INSERT IGNORE INTO community_post_comment SELECT * FROM ums_comment",
            "INSERT IGNORE INTO community_post_comment_action SELECT * FROM ums_comment_like",
            "INSERT IGNORE INTO scratch.community_post_media SELECT * FROM scratch.ums_creative_square",
            "INSERT IGNORE INTO `scratch`.`community_post_media` SELECT * FROM `scratch`.`ums_creative_square`",
            "INSERT IGNORE INTO `scratch` . `community_post_media` SELECT * FROM `scratch` . `ums_creative_square`",
        ):
            self.assertTrue(runner.requires_community_legacy_owner_guard([statement]))
        self.assertFalse(runner.requires_community_legacy_owner_guard(["SELECT 1"]))
        runner.assert_community_legacy_statement_scope([
            "INSERT INTO scratch.community_post SELECT * FROM scratch.ums_creative_square"], "scratch")
        with self.assertRaisesRegex(ValueError, "cross-schema"):
            runner.assert_community_legacy_statement_scope([
                "INSERT INTO other.community_post SELECT * FROM scratch.ums_creative_square"], "scratch")

    def test_guard_failure_precedes_every_statement_and_transaction_probe(self):
        runner = load_runner()
        connection = FakeConnection()
        args = types.SimpleNamespace(transaction=True, check=False, describe=None)
        statements = ["CREATE TABLE marker(id INT)",
                      "INSERT INTO community_post_media SELECT * FROM ums_creative_square"]
        with mock.patch.object(runner, "assert_community_legacy_owner_preflight",
                               side_effect=ValueError("conflict")) as guard:
            with self.assertRaises(SystemExit) as error:
                runner.run_connected(args, statements, None, connection, "scratch")
        self.assertEqual(1, error.exception.code)
        guard.assert_called_once()
        self.assertFalse(any(sql in statements or sql.startswith("CREATE")
                             for sql, _ in connection.cursor_instance.executed))

    def test_false_missing_or_ambiguous_observation_never_passes(self):
        runner = load_runner()
        for observed in ((0,), None, (1, 1)):
            cursor = mock.Mock()
            cursor.fetchall.return_value = [("ums_creative_square",), ("ums_comment",),
                                           ("ums_creative_square_like",), ("community_post",)]
            cursor.fetchone.return_value = observed
            with self.assertRaisesRegex(ValueError, "preflight conflict"):
                runner.assert_community_legacy_owner_preflight(cursor, "scratch")
        cursor = mock.Mock()
        cursor.fetchall.return_value = [("community_post",)]
        cursor.fetchone.return_value = (1,)
        with self.assertRaisesRegex(ValueError, "missing source"):
            runner.assert_community_legacy_owner_preflight(cursor, "scratch")


if __name__ == "__main__":
    unittest.main()

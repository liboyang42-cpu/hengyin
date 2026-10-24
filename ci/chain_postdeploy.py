"""Strict registration checks on the chain's disposable database, after its JVM exits."""
import hashlib
from decimal import Decimal
from datetime import datetime
import json
import time
from pathlib import Path
import re

try:
    from sql_splitter import split_statements, executable_statement
    from run_migration import statement_assertion, assert_statement_result
except ModuleNotFoundError:
    from ci.sql_splitter import split_statements, executable_statement
    from ci.run_migration import statement_assertion, assert_statement_result

SCHEMA = "cy_e2e_51"
SQL_FILES = (
    ("migration_20261012_merchant_commerce_reconcile_job.sql",
     "71c35ebe8b0e4c7d57ce94d57fd561086643e6e1d7cc4b008f71a29823c6bde3", 3, 2),
    ("migration_20261012_roam_presence_revoke_job.sql",
     "cd83ff76cd1367cf24e5127460761efa702ee975a05849c3a9a4b1fee9a688ea", 5, 4),
    ("migration_20261012_monitor_missing_jobs.sql",
     "b78a252952cc2f6c4e71e9579f4c8c68ece92b621949be3ed64ae353f63121dc", 7, 3),
)
BEANS = {
    "merchantCommerceReconcileTask": "com.chengyinhub.business.task.MerchantCommerceReconcileTask",
    "roamPresenceRevokeAuditTask": "com.chengyinhub.business.task.RoamPresenceRevokeAuditTask",
    "metricsProbeTask": "com.chengyinhub.web.task.MetricsProbeTask",
    "auditBacklogMonitorTask": "com.chengyinhub.business.task.AuditBacklogMonitorTask",
    "withdrawReconciliationTask": "com.chengyinhub.business.task.WithdrawReconciliationTask",
    "settlementReconciliationTask": "com.chengyinhub.business.task.SettlementReconciliationTask",
}
# group, cron, misfire, concurrent, status; existing operator-owned rows must stay byte-for-byte equal.
NEW_ROWS = {
    "merchantCommerceReconcileTask.reconcile()": ("DEFAULT", "0 0/5 * * * ?", "3", "1", "0"),
    "roamPresenceRevokeAuditTask.run()": ("COMPLIANCE", "0 * * * * ?", "3", "1", "0"),
    "metricsProbeTask.run()": ("DEFAULT", "0 0/5 * * * ?", "3", "1", "0"),
    "auditBacklogMonitorTask.run()": ("COMPLIANCE", "0 0/30 * * * ?", "3", "1", "0"),
    "withdrawReconciliationTask.run()": ("FINANCE", "0 0 4 * * ?", "3", "1", "0"),
    "settlementReconciliationTask.run()": ("DEFAULT", "0 10 3 * * ?", "3", "1", "0"),
}
READ_ROWS = ("SELECT job_id,job_name,job_group,invoke_target,cron_expression,misfire_policy,"
             "concurrent,status,create_by,create_time,remark,update_by,update_time FROM sys_job WHERE "
             "CAST(invoke_target AS BINARY) IN (" + ",".join(["CAST(%s AS BINARY)"] * 6)
             + ") ORDER BY invoke_target,job_id")
COMMERCE = "merchantCommerceReconcileTask.reconcile()"
OPERATOR_SCENARIO = "EXISTING_OPERATOR_ROWS"
OPERATOR_CRON = "0 17 2 * * ?"
FIXTURE_INSERT = ("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,"
                  "concurrent,status,create_by,create_time,remark,update_by,update_time) VALUES ("
                  + ",".join(["%s"] * 12) + ")")
COMMERCE_PAUSE = ("UPDATE sys_job SET cron_expression=%s,status=%s WHERE job_id=%s "
                  "AND CAST(invoke_target AS BINARY)=CAST(%s AS BINARY)")


def operator_values(target):
    schedule = NEW_ROWS[target][1:] if target == COMMERCE else (OPERATOR_CRON, "2", "0", "1")
    return ["SYNTHETIC_OPERATOR_" + str(list(NEW_ROWS).index(target)), "OPERATOR_FIXTURE", target,
            *schedule, "synthetic-ci-operator", "2026-10-20 12:34:56",
            "SYNTHETIC_OPERATOR_FIXTURE_NO_TASK_EXECUTION", "synthetic-ci-update", "2026-10-20 12:35:56"]


class PostDeployError(ValueError):
    def __init__(self, message, evidence=None):
        super().__init__(message)
        self.code = message
        self.evidence = evidence or {}


def require(condition, code):
    if not condition:
        raise PostDeployError(code)


def validate_observation(info, health, beans, source):
    commit = info.get("git", {}).get("commit", {}).get("id")
    if isinstance(commit, dict):
        commit = commit.get("full")
    require(commit == source, "live_source_mismatch")
    require(health.get("status") == "UP", "isolated_health_not_up")
    components = health.get("components", {})
    for name in ("db", "redis", "livenessState", "readinessState"):
        require(components.get(name, {}).get("status") == "UP", "health_component_not_up")
    found = {name: [] for name in BEANS}
    for context in beans.get("contexts", {}).values():
        for name, entry in context.get("beans", {}).items():
            if name in found:
                found[name].append(entry.get("type"))
    for name, expected in BEANS.items():
        require(len(found[name]) == 1, "task_bean_missing_or_duplicate")
        actual = found[name][0]
        require(isinstance(actual, str) and re.fullmatch(
            re.escape(expected) + r"(?:\$\$SpringCGLIB\$\$[A-Za-z0-9_]+)?", actual),
            "task_bean_type_mismatch")
    return {"source_sha": source, "health": "UP", "db": "UP", "redis": "UP",
            "livenessState": "UP", "readinessState": "UP",
            "beans": {name: rows[0] for name, rows in found.items()}}


def prepared_sql(source):
    prepared = []
    for name, digest, count, assertions in SQL_FILES:
        raw = (Path(source) / "chengyinhub-admin" / "sql" / name).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == digest, "post_sql_fingerprint_mismatch")
        statements = split_statements(raw.decode("utf-8"))
        require(len(statements) == count and
                sum(statement_assertion(sql) == "scalar-true" for sql in statements) == assertions,
                "post_sql_assertion_contract_mismatch")
        prepared.append((name, digest, statements))
    return prepared


def identity(cursor, expected_uuid=None):
    cursor.execute("SELECT @@server_uuid,DATABASE()")
    row = cursor.fetchone()
    require(row is not None and len(row) == 2 and row[1] == SCHEMA and
            isinstance(row[0], str) and re.fullmatch(
                r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", row[0]) and
            (expected_uuid is None or row[0] == expected_uuid), "post_database_identity_mismatch")
    return row[0]


def read_state(connect, expected_uuid=None):
    connection = connect()
    try:
        cursor = connection.cursor()
        try:
            server = identity(cursor, expected_uuid)
            cursor.execute(READ_ROWS, tuple(NEW_ROWS))
            rows = [list(row) for row in cursor.fetchall()]
            require(all(len(row) == 13 and row[3] in NEW_ROWS for row in rows), "job_readback_shape")
            require(len({row[0] for row in rows}) == len(rows) and
                    len({row[3] for row in rows}) == len(rows), "duplicate_job_target")
            return {"server_uuid": server, "schema": SCHEMA,
                    "rows": [[v if isinstance(v, (int, str, type(None))) else str(v) for v in row]
                             for row in rows]}
        finally:
            cursor.close()
    finally:
        connection.close()


def verify_rows(before, after):
    by_id = {row[0]: row for row in after}
    require(all(by_id.get(row[0]) == row for row in before), "existing_job_changed")
    by_target = {row[3]: row for row in after}
    require(len(after) == 6 and set(by_target) == set(NEW_ROWS), "job_cardinality_mismatch")
    existing = {row[3] for row in before}
    for target, row in by_target.items():
        if target not in existing:
            require(tuple(row[i] for i in (2, 4, 5, 6, 7)) == NEW_ROWS[target], "new_job_schedule_mismatch")


def validate_result(result, proof, source, jar_sha256):
    """A summary needs observations, not only a PASS label and declared totals."""
    require(isinstance(proof, dict) and proof.get("source_sha") == source and
            isinstance(source, str) and re.fullmatch(r"[0-9a-f]{40}", source) and
            proof.get("jar_sha256") == jar_sha256 and isinstance(jar_sha256, str) and
            re.fullmatch(r"[0-9a-f]{64}", jar_sha256) and proof.get("schema") == SCHEMA,
            "post_candidate_proof_incomplete")
    validate_observation({"git": {"commit": {"id": proof["source_sha"]}}},
                         {"status": proof.get("health"), "components": {
                             name: {"status": proof.get(name)} for name in
                             ("db", "redis", "livenessState", "readinessState")}},
                         {"contexts": {"observed": {"beans": {
                             name: {"type": value} for name, value in proof.get("beans", {}).items()}}}}, source)
    require(set(proof.get("beans", {})) == set(BEANS), "post_bean_proof_incomplete")
    process = proof.get("process", {})
    listeners = process.get("listener_inodes", [])
    require(type(process.get("pid")) is int and process["pid"] > 0 and
            isinstance(process.get("starttime"), str) and process["starttime"].isdigit() and
            bool(re.fullmatch(r"[0-9a-f]{64}", process.get("command_sha256", ""))) and
            isinstance(listeners, list) and bool(listeners) and
            all(isinstance(inode, str) and inode.isdigit() and int(inode) > 0 for inode in listeners) and
            listeners == sorted(set(listeners)), "post_pid_listener_proof_incomplete")
    server_uuid = proof.get("server_uuid", "")
    require(isinstance(server_uuid, str) and re.fullmatch(
        r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", server_uuid), "post_uuid_missing")
    def checked_state(state):
        require(isinstance(state, dict) and state.get("server_uuid") == server_uuid and
                state.get("schema") == SCHEMA, "post_readback_identity_incomplete")
        rows = state.get("rows")
        require(isinstance(rows, list) and all(isinstance(row, list) and len(row) == 13 and
                row[3] in NEW_ROWS for row in rows), "post_readback_rows_incomplete")
        require(len({row[0] for row in rows}) == len(rows) and len({row[3] for row in rows}) == len(rows),
                "post_readback_duplicate")
        return rows
    require(isinstance(result, dict) and result.get("status") == "PASS" and result.get("schema") == SCHEMA and
            (result.get("statement_count"), result.get("scalar_count")) == (15, 9) and
            result.get("existing_rows_preserved") is True, "post_result_incomplete")
    before, after = checked_state(result.get("before")), checked_state(result.get("after"))
    verify_rows(before, after)
    files = result.get("files")
    require(isinstance(files, list) and len(files) == 3, "post_files_incomplete")
    for facts, spec, indices in zip(files, SQL_FILES, ((1, 3), (1, 2, 3, 5), (1, 2, 7))):
        require(isinstance(facts, dict) and facts.get("file") == spec[0] and facts.get("sha256") == spec[1]
                and facts.get("statements") == spec[2] and facts.get("scalars") ==
                [{"index": index, "actual": [1]} for index in indices], "post_statement_evidence_incomplete")
        rows = checked_state(facts.get("independent_readback"))
        by_id = {row[0]: row for row in rows}
        require(all(by_id.get(row[0]) == row for row in before), "post_existing_readback_drift")
    require(files[-1]["independent_readback"] == result["after"], "post_final_readback_mismatch")


def register_jobs(source, connect, expected_uuid):
    """Execute every original statement once; no schema-only filtering, retries or assertion downgrades."""
    prepared = prepared_sql(source)  # All fingerprints checked before opening a write connection.
    result = {"status": "FAIL", "schema": SCHEMA, "files": [], "statement_count": 0,
              "scalar_count": 0, "task_methods_invoked_by_adapter": False,
              "actual_scheduled_execution": "NOT_VERIFIED", "scheduler_restarted_by_adapter": False}
    try:
        before = read_state(connect, expected_uuid)
        result["before"] = before
        connection = connect()
        try:
            cursor = connection.cursor()
            try:
                identity(cursor, expected_uuid)
                for name, digest, statements in prepared:
                    actual = {"file": name, "sha256": digest, "statements": 0, "scalars": []}
                    result["files"].append(actual)
                    for index, statement in enumerate(statements, 1):
                        sql = executable_statement(statement).strip().rstrip(";")
                        require(bool(sql), "empty_post_statement")
                        cursor.execute(sql)
                        actual["statements"] += 1
                        result["statement_count"] += 1
                        assertion = statement_assertion(statement)
                        if assertion:
                            require(assertion == "scalar-true", "unexpected_post_assertion")
                            # Shared migration assertion reads the real cursor, never a model result.
                            assert_statement_result(cursor, assertion, index)
                            actual["scalars"].append({"index": index, "actual": [1]})
                            result["scalar_count"] += 1
                    actual["independent_readback"] = read_state(connect, expected_uuid)
                    by_id = {row[0]: row for row in actual["independent_readback"]["rows"]}
                    require(all(by_id.get(row[0]) == row for row in before["rows"]), "existing_job_changed")
            finally:
                cursor.close()
        finally:
            connection.close()
        after = read_state(connect, expected_uuid)
        verify_rows(before["rows"], after["rows"])
        require((result["statement_count"], result["scalar_count"]) == (15, 9), "post_coverage_incomplete")
        result.update(status="PASS", after=after, existing_rows_preserved=True)
        return result
    except Exception as error:
        result.update(error_type=type(error).__name__, recovery="UNKNOWN_PARTIAL_KEEP_ORIGINAL_STATE_NO_RETRY")
        raise PostDeployError("strict_post_sql_failed", result) from None


def fixture_write(connect, expected_uuid, writes, expected_jobs=None):
    """Disposable schema fixtures only; unknown writes are never retried."""
    connection = connect()
    try:
        cursor = connection.cursor()
        try:
            identity(cursor, expected_uuid)
            if expected_jobs is not None:
                cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            connection.begin()
            if expected_jobs is not None:
                cursor.execute(ALL_JOBS + " FOR UPDATE")
                locked = [[v if isinstance(v, (int, float, str, type(None))) else str(v) for v in row]
                          for row in cursor.fetchall()]
                require(locked == expected_jobs, "restart_seed_locked_preimage_changed")
            for sql, args in writes:
                cursor.execute(sql, args)
                require(cursor.rowcount == 1, "operator_fixture_write_cardinality")
            if expected_jobs is not None:
                cursor.execute(ALL_JOBS + " FOR UPDATE")
                require(not cursor.fetchall(), "restart_seed_nonempty_before_commit")
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            cursor.close()
    finally:
        connection.close()


def prepare_operator_fixture(connect, expected_uuid):
    evidence = {"status": "FAIL", "scenario": OPERATOR_SCENARIO,
                "recovery": "UNKNOWN_PARTIAL_KEEP_ORIGINAL_STATE_NO_RETRY"}
    try:
        evidence["before"] = read_state(connect, expected_uuid)
        require(evidence["before"]["rows"] == [], "operator_fixture_requires_empty_targets")
        fixture_write(connect, expected_uuid,
                      [(FIXTURE_INSERT, tuple(operator_values(target))) for target in NEW_ROWS])
        evidence["after"] = read_state(connect, expected_uuid)
        validate_operator_fixture(evidence, expected_uuid)
        evidence.update(status="PASS", inserted_targets=6)
        return evidence
    except Exception as error:
        evidence["error_type"] = type(error).__name__
        raise PostDeployError("operator_fixture_failed", evidence) from None


def validate_operator_fixture(fixture, expected_uuid):
    require(fixture.get("scenario") == OPERATOR_SCENARIO, "operator_fixture_scenario_missing")
    for label in ("before", "after"):
        state = fixture.get(label, {})
        require(state.get("schema") == SCHEMA and state.get("server_uuid") == expected_uuid,
                "operator_fixture_identity_mismatch")
    require(fixture["before"].get("rows") == [], "operator_fixture_not_fresh")
    rows = fixture["after"].get("rows", [])
    require(len(rows) == 6 and len({row[0] for row in rows}) == 6 and
            {row[3] for row in rows} == set(NEW_ROWS) and
            all(len(row) == 13 and row[1:] == operator_values(row[3]) for row in rows),
            "operator_fixture_readback_mismatch")


def validate_commerce_rejection(result, positive):
    require(result.get("status") == "PASS_EXPECTED_REJECTION" and
            result.get("scenario") == "COMMERCE_INCOMPATIBLE_OPERATOR_ROWS" and
            result.get("unique_migration_count") == 3, "commerce_rejection_missing")
    expected = dict(positive["after"], rows=[row[:] for row in positive["after"]["rows"]])
    commerce = [row for row in expected["rows"] if row[3] == COMMERCE]
    require(len(commerce) == 1, "commerce_fixture_missing")
    commerce[0][4], commerce[0][7] = OPERATOR_CRON, "1"
    require(result.get("before") == expected and result.get("after") == expected,
            "commerce_rejection_changed_rows")
    require(result.get("scalar_readback") == {"server_uuid": expected["server_uuid"], "schema": SCHEMA,
            "file": SQL_FILES[0][0], "sha256": SQL_FILES[0][1], "index": 3, "actual": [0]},
            "commerce_false_scalar_not_observed")
    rejected = result.get("rejected_migration", {})
    require(rejected.get("status") == "FAIL" and rejected.get("schema") == SCHEMA and
            rejected.get("before") == expected and rejected.get("error_type") == "ValueError" and
            rejected.get("recovery") == "UNKNOWN_PARTIAL_KEEP_ORIGINAL_STATE_NO_RETRY" and
            (rejected.get("statement_count"), rejected.get("scalar_count")) == (3, 1) and
            rejected.get("files") == [{"file": SQL_FILES[0][0], "sha256": SQL_FILES[0][1],
                "statements": 3, "scalars": [{"index": 1, "actual": [1]}]}] and
            "after" not in rejected, "commerce_did_not_stop_at_original_assertion")


def characterize_commerce_rejection(source, connect, expected_uuid, positive):
    prepared = prepared_sql(source)
    result = {"status": "FAIL", "scenario": "COMMERCE_INCOMPATIBLE_OPERATOR_ROWS",
              "unique_migration_count": 3, "recovery": "UNKNOWN_PARTIAL_KEEP_ORIGINAL_STATE_NO_RETRY"}
    try:
        current = read_state(connect, expected_uuid)
        require(current == positive["after"], "operator_rows_changed_before_negative_control")
        commerce = next(row for row in current["rows"] if row[3] == COMMERCE)
        fixture_write(connect, expected_uuid,
                      [(COMMERCE_PAUSE, (OPERATOR_CRON, "1", commerce[0], COMMERCE))])
        result["before"] = read_state(connect, expected_uuid)
        try:
            register_jobs(source, connect, expected_uuid)
        except PostDeployError as rejected:
            result["rejected_migration"] = rejected.evidence
        else:
            raise PostDeployError("commerce_incompatible_configuration_was_accepted")
        connection = connect()
        try:
            cursor = connection.cursor()
            try:
                identity(cursor, expected_uuid)
                cursor.execute(executable_statement(prepared[0][2][2]).strip().rstrip(";"))
                rows = cursor.fetchall()
                require(len(rows) == 1 and len(rows[0]) == 1 and type(rows[0][0]) is int and rows[0][0] == 0,
                        "commerce_false_scalar_not_observed")
                result["scalar_readback"] = {"server_uuid": expected_uuid, "schema": SCHEMA,
                    "file": SQL_FILES[0][0], "sha256": SQL_FILES[0][1], "index": 3, "actual": [0]}
            finally:
                cursor.close()
        finally:
            connection.close()
        result["after"] = read_state(connect, expected_uuid)
        result["status"] = "PASS_EXPECTED_REJECTION"
        validate_commerce_rejection(result, positive)
        return result
    except Exception as error:
        result.update(status="FAIL", error_type=type(error).__name__)
        raise PostDeployError("commerce_rejection_control_failed", result) from None


# This scenario only owns the disposable chain schema and these six persisted definitions.
RESTART_MODE = "post_restart"
ALL_JOBS = READ_ROWS.split(" WHERE ", 1)[0] + " ORDER BY job_id"
JOB_COLUMNS = ("job_id", "job_name", "job_group", "invoke_target", "cron_expression", "misfire_policy",
               "concurrent", "status", "create_by", "create_time", "remark", "update_by", "update_time")
FIXTURE_ID = 9932001
ROAM_TARGET = "roamPresenceRevokeAuditTask.run()"


def restart_rows(connect, server, sql, args=()):
    connection = connect()
    try:
        cursor = connection.cursor()
        try:
            identity(cursor, server)
            cursor.execute(sql, args)
            return [[v if isinstance(v, (int, float, str, type(None))) else str(v) for v in row]
                    for row in cursor.fetchall()]
        finally:
            cursor.close()
    finally:
        connection.close()


def restart_clock(connect, server):
    before = time.time()
    rows = restart_rows(connect, server, "SELECT UNIX_TIMESTAMP()")
    after = time.time()
    require(len(rows) == 1 and len(rows[0]) == 1, "restart_clock_shape")
    value = {"epoch": rows[0][0], "host_before": before, "host_after": after}
    validate_restart_clock(value)
    return value


def validate_restart_clock(value):
    epoch, before, after = (value.get(k) for k in ("epoch", "host_before", "host_after"))
    require(type(epoch) is int and epoch >= 1600000000 and
            all(type(v) in (int, float) and 1600000000 <= v < 10000000000 for v in (before, after)) and
            0 <= after - before <= 30 and before - 1 <= epoch <= after + 1,
            "restart_database_clock_outside_actual_host_read_window")


def passive_queues(connect, server):
    rows = restart_rows(connect, server, "SELECT (SELECT COUNT(*) FROM merchant_crm_export_task),"
                        "(SELECT COUNT(*) FROM account_deregistration)")
    require(rows == [[0, 0]], "restart_unknown_background_queue")
    return {"crm_export_tasks": 0, "account_deregistrations": 0}


RESTART_SEED_SOURCE = '6cd9b026ccba50a9d7eb8fdd1cf3228af95628c2'
# Fixed source replay, including the final three pause repairs; unknown rows must stop before deletion.
RESTART_SEED_ROWS = {
    'playTeamExpireTask.run()': [
        '玩家队伍超时结束', 'DEFAULT', 'playTeamExpireTask.run()', '0 0/10 * * * ?', '3', '1', '0',
        'admin', None, '逐team幂等结束已到场次开始时间的邀请制队伍并关闭群聊；不读取订单、不触发退款', '', None],
    'imNotificationDeliveryTask.deliverPending()': [
        '原站内通知意图投递', 'DEFAULT', 'imNotificationDeliveryTask.deliverPending()', '0 0/1 * * * ?', '3', '1', '1',
        'system', None, '默认暂停；仅原站内消息，不投外部渠道；待测试库事务和原事件回读验证后启用', '', None],
    'roamRewardDeliveryTask.run()': [
        '原漫游掉落补交付', 'DEFAULT', 'roamRewardDeliveryTask.run()', '0 0/10 * * * ?', '3', '1', '1',
        'system', None, '暂停；负责人:C包；启用条件:A原来源发券、掉落原日志与C当次XP金额快照已集成并完成测试账回读；生产启用另验。', '', None],
    'dataRetentionCleanupTask.run()': [
        '数据留存清理', 'COMPLIANCE', 'dataRetentionCleanupTask.run()', '0 30 3 * * ?', '3', '1', '1',
        'system', None, '仅补缺失登记且默认暂停；核对隐私指引、留存配置与候选后再由责任人启用', '', None],
    'aiQuotaRecoveryTask.run()': [
        'AI原日配额恢复', 'DEFAULT', 'aiQuotaRecoveryTask.run()', '0 */1 * * * ?', '3', '1', '1',
        'admin', None, 'C包原日原nonce补偿；默认暂停，开发测试数据库/Redis和崩溃场景回读后另行启用', '', None],
    'merchantAftercareTimeoutTask.run()': [
        '售后-商家超时转平台客服', 'DEFAULT', 'merchantAftercareTimeoutTask.run()', '0 0/10 * * * ?', '3', '1', '0',
        'merchant-aftercare-migrate', None, '拍板2026-09-16#12 MerchantAftercareTimeoutTask', '', None],
    'merchantCrmDeliveryRecoveryTask.run()': [
        '商家CRM触达恢复', 'DEFAULT', 'merchantCrmDeliveryRecoveryTask.run()', '0 * * * * ?', '3', '1', '1',
        'admin', None, '每分钟恢复过期PROCESSING；version+token CAS；未知外部结果不自动重发；默认暂停；触发时机=全部前置schema新连接回读通过+生产jar自报commit等于本次master SHA+sha256指纹/唯一进程/启动时间回读通过+三个Quartz bean存在性回读通过，另行独立授权启用；负责人=城瘾发布负责人；到期日=2026-08-31（到期未启用必须复核，不得自动启用）', 'admin', None],
    'communityPostMaintenanceTask.run()': [
        '广场帖文投影治理与擦除', 'DEFAULT', 'communityPostMaintenanceTask.run()', '0 * * * * ?', '3', '1', '0',
        'admin', None, '社区平台长期任务：新 jar 部署并回读后启用；消费帖文 outbox、应用/到期治理处置、执行删除搜索通知媒体擦除', '', None],
    'analyticsAggregationTask.aggregateRecentWindow()': [
        '每日行为漏斗聚合', 'DEFAULT', 'analyticsAggregationTask.aggregateRecentWindow()', '0 10 0 * * ?', '2', '1', '0',
        'admin', None, '社区平台长期任务：每日重算最近15个完整自然日，吸收允许时间窗内迟到事件；删除与重建同事务', '', None],
    'predictVoidExpiredTask.run()': [
        '竞猜轮次超时作废', 'DEFAULT', 'predictVoidExpiredTask.run()', '0 0 3 * * ?', '3', '1', '0',
        'system', None, '商家超过48小时不给竞猜答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑', '', None],
    'clubEventOpsTask.run()': [
        '俱乐部活动运营维护', 'DEFAULT', 'clubEventOpsTask.run()', '0 * * * * ?', '3', '1', '1',
        'admin', None, '每分钟维护候补到期/报名终态/晋级与站内通知失败项；CAS有界批处理；默认暂停；触发时机=全部前置schema新连接回读通过+生产jar自报commit等于本次master SHA+sha256指纹/唯一进程/启动时间回读通过+三个Quartz bean存在性回读通过，另行独立授权启用；负责人=城瘾发布负责人；到期日=2026-08-31（到期未启用必须复核，不得自动启用）', 'admin', None],
    'registrationAutoVerifyTask.run()': [
        '到期未核销场次票自动核销', 'DEFAULT', 'registrationAutoVerifyTask.run()', '0 30 * * * ?', '3', '1', '0',
        'system', None, 'RUN-01 拍板#5:活动结束满7天仍已支付未核销的场次票自动置已核销收口(不退款;退款在途/缺锚点不扫)', '', None],
    'orderCloseTask.reconcileExpiredPaying()': [
        '支付中过期订单对账', 'DEFAULT', 'orderCloseTask.reconcileExpiredPaying()', '0 0/10 * * * ?', '3', '1', '0',
        'system', None, '§5-3 PAYING 过支付窗口:查微信真实态——已支付补记/未支付关单回补/查单失败告警不猜', '', None],
    'roamHangoutExpireTask.run()': [
        '漫游附近的局过期关闭', 'DEFAULT', 'roamHangoutExpireTask.run()', '0 0/30 * * * ?', '3', '1', '0',
        'admin', None, '关闭已过期的玩家自组局并关闭其群聊;不读取订单、不触发退款', '', None],
    'storeVerifyGrowthTask.run()': [
        '核销成长奖励恢复', 'DEFAULT', 'storeVerifyGrowthTask.run()', '0 * * * * ?', '3', '1', '0',
        'system', None, '每分钟最多50条，恢复已入队核销成长奖励；不扫描历史核销补造奖励', '', None],
    'refundReconcileTask.reconcile()': [
        '退款对账兜底', 'DEFAULT', 'refundReconcileTask.reconcile()', '0 0/15 * * * ?', '3', '1', '1',
        'system', None, '仅补缺失登记且默认暂停：除按微信终态回写外还会发起保证金退款与审核后出款，须由资金owner核对目标环境后启用', '', None],
    'clubSessionReconciliationTask.run()': [
        '俱乐部带队场次对账', 'DEFAULT', 'clubSessionReconciliationTask.run()', '0 20 3 * * ?', '3', '1', '0',
        'system', None, '仅补缺失登记(原 migration_club_session_reconciliation_job_20260807)；只告警，不自动补场次', '', None],
    'settlementTask.run()': [
        '分润结算（活动+主题）', 'DEFAULT', 'settlementTask.run()', '0 0 * * * ?', '3', '1', '1',
        'system', None, '仅补缺失登记且默认暂停：资金任务须由资金owner核对目标环境后在「定时任务」页启用', '', None],
    'coopSettlementPayoutTask.run()': [
        '商家结算T+7打款', 'DEFAULT', 'coopSettlementPayoutTask.run()', '0 0 3 * * ?', '3', '1', '1',
        'system', None, '仅补缺失登记且默认暂停：会真实出款，须由资金owner核对目标环境与商户号后启用', '', None],
    'beforeStartNotifyTask.run()': [
        '出发前提醒', 'DEFAULT', 'beforeStartNotifyTask.run()', '0 0 * * * ?', '3', '1', '1',
        'system', None, '仅补缺失登记且默认暂停：会向用户发订阅消息，须核对订阅模板与目标环境后启用', '', None],
    'clubPostMediaReviewTask.run()': [
        '俱乐部动态图片送审恢复', 'DEFAULT', 'clubPostMediaReviewTask.run()', '0 * * * * ?', '3', '1', '1',
        'admin', None, '每分钟最多50条；运行25秒后不再启动下一条；瞬时故障本轮熔断；CAS lease可重入；持续积压告警；默认暂停；触发时机=全部前置schema新连接回读通过+生产jar自报commit等于本次master SHA+sha256指纹/唯一进程/启动时间回读通过+三个Quartz bean存在性回读通过，另行独立授权启用；负责人=城瘾发布负责人；到期日=2026-08-31（到期未启用必须复核，不得自动启用）', 'admin', None],
    'topicRecruitDeadlineTask.run()': [
        '双死线-招募到期判定', 'DEFAULT', 'topicRecruitDeadlineTask.run()', '0 0/10 * * * ?', '3', '1', '0',
        'e2e-seed', None, 'E2E TopicRecruitDeadlineTask', '', None],
}


def restart_seed_datetime(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}", value),
            "restart_seed_datetime_invalid")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        raise PostDeployError("restart_seed_datetime_invalid")
    require(parsed.year >= 2020, "restart_seed_datetime_invalid")
    return parsed


def prepare_restart_seed(connect, fresh):
    require(fresh.get("build_exit") == 0 and fresh.get("seed_exit") == 0 and
            fresh.get("source_sha") == RESTART_SEED_SOURCE and
            fresh.get("containers") and set(fresh["containers"]) == {"mysql", "redis"} and
            all(re.fullmatch(r"cy-chain-[0-9]+-[0-9]+-" + kind, n)
                for kind, n in fresh["containers"].items()), "restart_requires_fresh_chain_provenance")
    server = read_state(connect)["server_uuid"]
    before, after = (fresh.get(key, {}) for key in ("clock_before", "clock_after"))
    require(all(clock.get("server_uuid") == server for clock in (before, after)) and
            all(isinstance(before.get(key), str) and before[key] and before[key] == after.get(key)
                for key in ("session_timezone", "system_timezone")), "restart_seed_clock_identity_mismatch")
    begin, end = (restart_seed_datetime(clock.get("datetime")) for clock in (before, after))
    require(begin <= end, "restart_seed_clock_reversed")
    rows = restart_rows(connect, server, ALL_JOBS)
    require(len(rows) == len(RESTART_SEED_ROWS) and all(len(row) == 13 for row in rows),
            "restart_unexpected_seed_jobs")
    require(all(type(row[0]) is int and row[0] > 0 for row in rows) and
            len({row[0] for row in rows}) == len(rows) and
            all(isinstance(row[3], str) for row in rows) and
            {row[3] for row in rows} == set(RESTART_SEED_ROWS), "restart_seed_job_identity_mismatch")
    for row in rows:
        expected = RESTART_SEED_ROWS[row[3]]
        require(row[1:9] == expected[:8] and row[10:12] == expected[9:11],
                "restart_seed_static_fields_mismatch")
        created = restart_seed_datetime(row[9])
        require(begin <= created <= end, "restart_seed_create_time_outside_preparation")
        if expected[10] == "admin":
            updated = restart_seed_datetime(row[12])
            require(created <= updated <= end, "restart_seed_update_time_outside_preparation")
        else:
            require(row[12] is None, "restart_seed_unexpected_update_time")
    fingerprints = [hashlib.sha256(json.dumps(row, ensure_ascii=False).encode()).hexdigest() for row in rows]
    where = " AND ".join("`%s` <=> %%s" % column for column in JOB_COLUMNS)
    fixture_write(connect, server, [("DELETE FROM sys_job WHERE " + where, tuple(row)) for row in rows],
                  expected_jobs=rows)
    require(restart_rows(connect, server, ALL_JOBS) == [], "restart_seed_removal_unknown")
    passive = passive_queues(connect, server)
    return {"status": "PASS", "server_uuid": server, "schema": SCHEMA, "fresh": fresh,
            "removed_seed_job_ids": [row[0] for row in rows], "removed_seed_fingerprints": fingerprints,
            "jobs_before_jvm1": 0, "passive_queues": passive}


def prepare_restart_effects(connect, server):
    rows = restart_rows(connect, server, ALL_JOBS)
    verify_rows([], rows)
    require(len(rows) == 6, "restart_not_exact_six_jobs")
    # All six are exact fresh rows. Pausing is an explicit synthetic scheduling fixture, after SQL proof.
    fixture_write(connect, server, [("UPDATE sys_job SET status='1' WHERE job_id=%s "
                  "AND CAST(invoke_target AS BINARY)=CAST(%s AS BINARY) AND status='0'", (r[0], r[3]))
                  for r in rows])
    paused = restart_rows(connect, server, ALL_JOBS)
    expected = [r[:7] + ["1"] + r[8:] for r in rows]
    require(paused == expected, "restart_pause_snapshot_mismatch")
    passive_queues(connect, server)
    empty = restart_rows(connect, server, "SELECT (SELECT COUNT(*) FROM audit_task),"
                         "(SELECT COUNT(*) FROM member_cash_withdraw),(SELECT COUNT(*) FROM settlement_batch),"
                         "(SELECT COUNT(*) FROM settlement_detail),(SELECT COUNT(*) FROM metrics_snapshot),"
                         "(SELECT COUNT(*) FROM oms_order WHERE biz_type IS NOT NULL)")
    require(empty == [[0, 0, 0, 0, 0, 0]], "restart_effect_fixture_not_fresh")
    request = "post-restart-" + str(FIXTURE_ID)
    writes = [
        ("INSERT INTO audit_task(id,biz_type,biz_id,submitter_id,status,submitted_at,deadline_at,create_by,create_time) "
         "VALUES(%s,'community_post',%s,9009,0,NOW(),DATE_SUB(NOW(),INTERVAL 1 MINUTE),'e2e-post-restart',NOW())", (FIXTURE_ID, FIXTURE_ID)),
        ("INSERT INTO member_cash_withdraw(id,member_id,amount,status,create_time,del_flag) VALUES(%s,9009,1,3,NOW(),0)", (FIXTURE_ID,)),
        ("INSERT INTO settlement_batch(id,batch_no,merchant_id,total_amount,idempotency_key) VALUES(%s,%s,8001,1,%s)",
         (FIXTURE_ID, request, request)),
        ("INSERT INTO settlement_detail(id,batch_id,merchant_id,source_type,source_id,receiver_type,receiver_id,"
         "settlement_amount,idempotency_key) VALUES(%s,%s,8001,'order',%s,'merchant',8001,1,%s)",
         (FIXTURE_ID, FIXTURE_ID, FIXTURE_ID, request)),
        ("INSERT INTO roam_session(id,member_id,status,presence_visible,started_at,create_time) VALUES(%s,9009,0,0,NOW(),NOW())", (FIXTURE_ID,)),
        ("INSERT INTO roam_presence_revoke_intent(request_id,member_id,event_type,occurred_at,state,next_attempt_at) "
         "VALUES(%s,9009,'REVOKE',NOW(6),0,NOW(6))", (request,)),
        ("INSERT INTO oms_order(id,order_sn,member_id,biz_type,status,pay_amount,create_time,del_flag) "
         "VALUES(%s,%s,9009,'premium_template',0,1,DATE_SUB(NOW(),INTERVAL 6 MINUTE),0)",
         (FIXTURE_ID, "TPLTM_" + str(FIXTURE_ID))),
    ]
    fixture_write(connect, server, writes)
    before = restart_effect_state(connect, server)
    require(before["roam"] == [[0, 0, 0]] and before["metrics"] == [], "restart_initial_effect_already_present")
    return {"status": "PASS", "jobs": paused, "server_uuid": server, "schema": SCHEMA,
            "fixture_id": FIXTURE_ID, "request_id": request, "before": before,
            "scope": "SYNTHETIC_DATABASE_FACTS_NOT_REAL_CONSENT_OR_PAYMENT"}


def restart_effect_state(connect, server):
    request = "post-restart-" + str(FIXTURE_ID)
    return {
        "roam": restart_rows(connect, server, "SELECT e.state,(SELECT COUNT(*) FROM consent_record c WHERE "
                   "c.request_id=e.request_id AND c.member_id=9009 AND c.doc_type='privacy_policy' "
                   "AND c.doc_version='roam-presence-v1' AND c.scene='roam_presence' AND c.event_type='REVOKE'),"
                   "r.presence_visible FROM roam_presence_revoke_intent e JOIN roam_session r ON r.id=%s "
                   "WHERE e.request_id=%s AND e.member_id=9009", (FIXTURE_ID, request)),
        "metrics": restart_rows(connect, server, "SELECT metric_key,source,window_start,window_end,status,value "
                     "FROM metrics_snapshot ORDER BY metric_key,window_start,window_end"),
        "audit": restart_rows(connect, server, "SELECT id,status,biz_id,submitter_id FROM audit_task WHERE id=%s", (FIXTURE_ID,)),
        "withdraw": restart_rows(connect, server, "SELECT id,member_id,amount,status,del_flag FROM member_cash_withdraw WHERE id=%s", (FIXTURE_ID,)),
        "settlement": restart_rows(connect, server, "SELECT b.id,b.total_amount,COALESCE(SUM(d.settlement_amount),0) "
                       "FROM settlement_batch b LEFT JOIN settlement_detail d ON d.batch_id=b.id WHERE b.id=%s GROUP BY b.id,b.total_amount", (FIXTURE_ID,)),
        "commerce": restart_rows(connect, server, "SELECT id,order_sn,member_id,biz_type,status,pay_amount,pay_type,payment_transaction_id,payment_time,update_time FROM oms_order WHERE id=%s", (FIXTURE_ID,)),
        "assets": restart_rows(connect, server, "SELECT balance FROM ums_member WHERE id=9009"),
        "entitlements": restart_rows(connect, server, "SELECT id,merchant_id,subscription_type,start_date,end_date,max_usage,used_count,is_active,remark,create_time,update_time FROM merchant_subscription WHERE merchant_id=9009 ORDER BY id"),
    }


def restart_log(connect, server, job, baseline, started):
    rows = restart_rows(connect, server, "SELECT job_log_id,status,UNIX_TIMESTAMP(create_time),"
                        "CASE WHEN exception_info LIKE %s THEN 'COMMERCE_AGGREGATE_FAILURE' "
                        "WHEN exception_info LIKE %s THEN 'SETTLEMENT_MISMATCH' ELSE 'OTHER' END,job_name,job_group,invoke_target "
                        "FROM sys_job_log WHERE job_log_id>%s AND CAST(invoke_target AS BINARY)=CAST(%s AS BINARY) "
                        "AND CAST(job_name AS BINARY)=CAST(%s AS BINARY) AND CAST(job_group AS BINARY)=CAST(%s AS BINARY) ORDER BY job_log_id",
                        ("%商业化查单补偿失败 1 单%", "%Settlement mismatch count=1%", baseline, job[3], job[1], job[2]))
    require(all(len(r) == 7 and r[0] > baseline and r[2] is not None and float(r[2]) >= int(started)
                and r[4:] == [job[1], job[2], job[3]] for r in rows), "restart_stale_or_malformed_log")
    require(len(rows) <= 1, "restart_ambiguous_duplicate_log")
    return rows[0] if rows else None


def commerce_cause(path, offset, job_log_id):
    end = Path(path).stat().st_size
    require(type(offset) is int and 0 <= offset < end and end - offset <= 8 * 1024 * 1024,
            "restart_commerce_log_window_missing")
    with Path(path).open("rb") as handle:
        handle.seek(offset)
        raw = handle.read(end - offset)
    text = raw.decode("utf-8", errors="strict")
    cause = "Caused by: java.lang.IllegalStateException: 商业化查单结果无法确认"
    causes = [line.strip() for line in text.splitlines() if line.strip().startswith("Caused by:")]
    allowed = {cause, "Caused by: java.lang.reflect.InvocationTargetException",
               "Caused by: java.lang.IllegalStateException: 商业化查单补偿失败 1 单"}
    require(causes.count(cause) == 1 and set(causes) <= allowed and
            "java.lang.IllegalStateException: 商业化查单补偿失败 1 单" in text and
            re.search(r"(?m)^\s*at com\.chengyinhub\.business\.service\.impl\.MerchantCommerceService\.reconcileOrder\(MerchantCommerceService\.java:177\)\s*$", text),
            "restart_commerce_specific_cause_not_observed")
    return {"code": "COMMERCE_QUERY_UNCONFIRMED", "job_log_id": job_log_id,
            "start_offset": offset, "end_offset": end,
            "cause_class": "java.lang.IllegalStateException",
            "source_frame": "MerchantCommerceService.reconcileOrder:177"}


def verify_restart_jobs(fixture, current, roam_status="1"):
    require(roam_status in ("0", "1") and len(fixture) == len(current) == 6 and
            all(len(row) == 13 for row in fixture + current), "restart_job_snapshot_shape")
    expected = {row[0]: row for row in fixture}
    require(len(expected) == 6 and len({row[0] for row in current}) == 6 and
            {row[3] for row in fixture} == set(NEW_ROWS), "restart_job_snapshot_identity")
    for row in current:
        original = expected.get(row[0])
        require(original is not None, "restart_unexpected_job_id")
        if row[3] == ROAM_TARGET:
            # Normal changeStatus carries the stored definition unchanged; only status/update_time can change.
            require(row[:7] == original[:7] and row[8:12] == original[8:12] and row[7] == roam_status and
                    (row[12] == original[12] or isinstance(row[12], str) and
                     re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?", row[12])),
                    "restart_roam_definition_drift")
        else:
            require(row == original, "restart_paused_definition_drift")


def validate_restart_effect(target, log, before, after, alarm=None):
    require(log is not None and log[1] == ("1" if target == COMMERCE else "0"), "restart_job_did_not_complete")
    require(all(after[name] == before[name] for name in ("audit", "withdraw", "settlement", "commerce", "assets", "entitlements")), "restart_unexpected_business_write")
    if target == ROAM_TARGET:
        require(after["roam"] == [[2, 1, 0]], "restart_revoke_effect_missing_or_duplicate")
    elif target == "metricsProbeTask.run()":
        metrics = after["metrics"]
        require(bool(metrics) and any(row[0] == "jvm.heap.used.ratio" and row[1] == "jvm" for row in metrics)
                and all(len(row) == 6 and row[4] == "OK" and row[5] is not None
                        and Decimal(str(row[5])).is_finite() for row in metrics)
                and len({tuple(row[:1] + row[2:4]) for row in metrics}) == len(metrics), "restart_metrics_effect_missing")
    elif target in ("auditBacklogMonitorTask.run()", "withdrawReconciliationTask.run()"):
        require(alarm is not None and alarm.get("value") == "1" and 0 < alarm.get("ttl_ms", 0) <= 600000,
                "restart_monitor_attempt_missing")
    elif target == "settlementReconciliationTask.run()":
        require(after["settlement"] == [[FIXTURE_ID, "1.00", "1.00"]], "restart_settlement_fixture_not_balanced")
    elif target == COMMERCE:
        require(log[3] == "COMMERCE_UNKNOWN", "restart_wrong_commerce_failure")
    else:
        raise PostDeployError("restart_unknown_task")


def validate_restart_result(result):
    require(result.get("status") == "PASS" and result.get("schema") == SCHEMA and
            result.get("jvm1_stopped") is True and result.get("jvm2_stopped") is True and
            type(result.get("jvm2_exit_code")) is int and result.get("jvm2_new_identity") is True and
            result.get("automatic_roam_observed") is True, "restart_lifecycle_evidence_missing")
    proof = result.get("proof", {})
    fixture = result.get("fixture", {})
    require(fixture.get("server_uuid") == proof.get("server_uuid") == result.get("server_uuid") and
            isinstance(result.get("server_uuid"), str) and
            re.fullmatch(r"[0-9a-f-]{36}", result["server_uuid"]), "restart_fixture_database_identity_mismatch")
    clocks = result.get("clock", {})
    for key in ("before_start", "after_live", "before_stop"):
        validate_restart_clock(clocks.get(key, {}))
    begin, live, stop = (clocks[k] for k in ("before_start", "after_live", "before_stop"))
    observed = proof.get("observed_at")
    require(type(observed) in (int, float) and begin["host_after"] <= observed <= live["host_before"] + 1 and
            begin["host_after"] <= live["host_before"] <= stop["host_before"] and
            live["host_after"] <= stop["host_after"] and
            result.get("jvm2_started_database_epoch") == begin["epoch"] and
            begin["epoch"] <= live["epoch"] <= stop["epoch"] and
            result.get("controlled_stop") is True and result.get("stop_precondition") == proof.get("process") and
            isinstance(proof.get("process"), dict) and bool(proof["process"]),
            "restart_original_jvm_time_or_controlled_stop_binding_missing")
    jobs = fixture.get("jobs", [])
    require(fixture.get("status") == "PASS" and fixture.get("schema") == SCHEMA and
            len(jobs) == 6 and all(len(row) == 13 and row[7] == "1" for row in jobs) and
            {row[3] for row in jobs} == set(NEW_ROWS), "restart_fixture_evidence_missing")
    verify_restart_jobs(jobs, result.get("final_jobs", []))
    verify_restart_jobs(jobs, result.get("after_stop_jobs", []))
    by_target = {r[3]: r for r in jobs}
    def checked(effect):
        target = effect["target"]
        job = by_target[target]
        log = effect["log"]
        require(effect.get("job_id") == job[0] and len(log) == 7 and
                type(effect.get("baseline_log_id")) is int and log[0] > effect["baseline_log_id"] and
                begin["epoch"] <= float(log[2]) <= stop["epoch"] + 1 and
                log[4:] == [job[1], job[2], target] and
                effect.get("new_jvm_start_database_epoch") == result["jvm2_started_database_epoch"],
                "restart_log_binding_missing")
        return target
    effects = result.get("effects", [])
    require(len(effects) == 6 and {e.get("target") for e in effects} == set(NEW_ROWS), "restart_six_effects_missing")
    for effect in effects:
        target = checked(effect)
        validate_restart_effect(target, effect.get("log"), effect["before"], effect["after"], effect.get("alarm"))
        if target == COMMERCE:
            cause = effect.get("specific_cause", {})
            require(cause.get("code") == "COMMERCE_QUERY_UNCONFIRMED" and cause.get("job_log_id") == effect["log"][0] and
                    cause.get("cause_class") == "java.lang.IllegalStateException" and
                    cause.get("source_frame") == "MerchantCommerceService.reconcileOrder:177" and
                    type(cause.get("start_offset")) is int and type(cause.get("end_offset")) is int and
                    0 <= cause["start_offset"] < cause["end_offset"] and
                    result.get("payment_stub_binding") == {"task": True, "service": True,
                        "type": "com.chengyinhub.e2e.E2eWechatPayStub",
                        "source_sha256": "62daf04bc0e6aac703e7536fe17b79999b86d92ddec722cfb88c59cadba87161"},
                    "restart_commerce_unknown_cause_or_actual_stub_binding_missing")
        require(effect.get("invocation") == ("ORIGINAL_MINUTE_CRON" if target == ROAM_TARGET else "SINGLE_NORMAL_ADMIN_PUT"),
                "restart_invocation_scope_missing")
    for name, target in (("roam_repeat", ROAM_TARGET), ("metrics_repeat", "metricsProbeTask.run()"),
                         ("audit_repeat", "auditBacklogMonitorTask.run()")):
        effect = result.get(name, {})
        require(checked(effect) == target and effect.get("invocation") == "SINGLE_NORMAL_ADMIN_PUT", "restart_repeat_evidence_missing")
        validate_restart_effect(target, effect["log"], effect["before"], effect["after"], effect.get("alarm"))
    metric = next(e for e in effects if e["target"] == "metricsProbeTask.run()")
    require([r[:4] for r in result["metrics_repeat"]["after"]["metrics"]] ==
            [r[:4] for r in metric["after"]["metrics"]], "restart_metric_repeat_not_same_window")
    audit = next(e for e in effects if e["target"] == "auditBacklogMonitorTask.run()")
    require(result["audit_repeat"]["alarm"]["ttl_ms"] <= audit["alarm"]["ttl_ms"], "restart_alarm_repeat_extended_lease")
    rejected = result.get("settlement_rejection", {})
    require(checked(rejected) == "settlementReconciliationTask.run()" and rejected["log"][1:2] == ["1"] and
            rejected["log"][3] == "SETTLEMENT_MISMATCH" and rejected["before"] == rejected["after"] and
            rejected["after"]["settlement"] == [[FIXTURE_ID, "2.00", "1.00"]] and
            rejected.get("alarm", {}).get("value") == "1" and rejected["alarm"].get("ttl_ms", 0) > 0,
            "restart_settlement_negative_evidence_missing")
    require(result.get("final_readback") == result.get("after_stop_readback") and
            result["after_stop_readback"]["roam"] == [[2, 1, 0]] and
            result.get("final_jobs") == result.get("after_stop_jobs") and
            len(result["after_stop_jobs"]) == 6 and all(r[7] == "1" for r in result["after_stop_jobs"]) and
            result.get("passive_queues") == {"crm_export_tasks": 0, "account_deregistrations": 0} and
            result.get("local_files") == result.get("after_stop_local_files") == {"crm_export_directory": "EMPTY_TASK_OWNED"},
            "restart_final_readback_drift")

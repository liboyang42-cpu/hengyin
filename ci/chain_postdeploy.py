"""Strict registration checks on the chain's disposable database, after its JVM exits."""
import hashlib
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


class PostDeployError(ValueError):
    def __init__(self, message, evidence=None):
        super().__init__(message)
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

#!/usr/bin/env python3
"""从零重建一个可跑的城瘾 schema:base schema 基线快照 + 快照后 migration(按台账拓扑序)。

用法:
  python3 scripts/e2e/build_schema.py --schema cy_e2e_51 [--host 127.0.0.1 --port 3316 --user root --password ""]

设计要点(别顺手改):
  · 迁移顺序取自 ci/migration_ledger.py 的 build_manifest —— 与发布台账同一套拓扑序,
    不另写一份排序,否则本地跑通不代表生产顺序跑得通。
  · 分句用 ci/sql_splitter.py 唯一真源;`-- runner: assert=` 由 ci/run_migration.py 的
    statement_assertion 识别并【真的求值】。纯 schema 回放中，只查业务数据的断言
    单独记为 DATA_ASSERT；混合 information_schema 与业务数据的断言仍 fail-closed。
  · 每条失败都记下来继续(--keep-going)或立即停,默认立即停;冲突逐条打印,
    「migration 与 base schema 冲突」本身就是有价值的发现(说明生产被手工改过而迁移没同步)。
  · 只连回环地址且只允许 cy_e2e_* scratch schema。
"""
import argparse, hashlib, io, json, pathlib, re, subprocess, sys, tarfile, time

REPO = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from ci.sql_splitter import split_statements, executable_statement
from ci.run_migration import (assert_statement_result, required_transaction_declared,
                              statement_assertion, validate_transaction_statements)
from ci.migration_ledger import build_manifest, EXPLICIT_MANAGED_SQL, MIGRATION_PATH
from scripts.e2e.policy_source_custody import SourceCustody

BASELINE = REPO / "sql/base_schema_20260818.sql"
BASELINE_SEED = REPO / "sql/base_seed_20260818.sql"
BASELINE_MANIFEST = REPO / "scripts/e2e/base_schema_20260818_migrations.json"
SCHEMA_REPLAY_POLICY = REPO / "scripts/e2e/schema_replay_policy_20260921.json"
POLICY_CUSTODY = None
ALLOWED_HOSTS = {"127.0.0.1", "localhost", "::1"}
SCRATCH_SCHEMA = re.compile(r"cy_e2e_[A-Za-z0-9_]+\Z")
SEED_NEGATIVE_CONTROLS = (
    ("missing-admin", "DELETE FROM sys_role WHERE role_id = 1"),
    ("wrong-finance", "UPDATE sys_role SET role_key = 'wrong' WHERE role_id = 103"),
    ("missing-audit-parent", "DELETE FROM sys_menu WHERE menu_id = 2210"),
    ("missing-funds-parent", "DELETE FROM sys_menu WHERE menu_id = 2214"),
    ("missing-topic-parent", "DELETE FROM sys_menu WHERE menu_id = 2007"),
    ("missing-club-parent", "DELETE FROM sys_menu WHERE menu_id = 2211"),
)
MYSQL_DUMP_DEFINER = re.compile(
    r"/\*!50013\s+DEFINER=`[^`]+`@`[^`]+`\s+SQL SECURITY DEFINER\s+\*/",
    re.IGNORECASE,
)


def scratch_schema_sql(sql):
    """让生产 dump 的视图在本机 scratch 用户下可执行。"""
    return MYSQL_DUMP_DEFINER.sub("/*!50013 SQL SECURITY INVOKER */", sql)


def validate_target(a):
    if a.host not in ALLOWED_HOSTS:
        raise SystemExit("只允许连接本机 MySQL loopback")
    if not SCRATCH_SCHEMA.fullmatch(a.schema):
        raise SystemExit("只允许操作 cy_e2e_* scratch schema")


def connect(a, db=None):
    validate_target(a)
    import pymysql
    return pymysql.connect(host=a.host, port=a.port, user=a.user, password=a.password,
                           database=db, autocommit=True, charset="utf8mb4")


def assertion_is_data_only(sql):
    sources = re.findall(r"\b(?:FROM|JOIN)\s+`?([a-zA-Z_][a-zA-Z0-9_]*)", sql,
                         re.IGNORECASE)
    lowered = {source.lower() for source in sources}
    return (bool(lowered - {"information_schema", "dual"}) and
            "information_schema" not in lowered)


def run_file(cur, path, label, results, keep_going, schema_only=False, observations=None):
    text = pathlib.Path(path).read_text(encoding="utf-8")
    statements = split_statements(text)
    transactional = required_transaction_declared(text)
    if transactional:
        try:
            validate_transaction_statements(statements)
        except ValueError as error:
            results.append((label, 0, "PREFLIGHT", str(error)[:200], "requires=transaction"))
            return False

    connection = cur.connection
    # A caller's open transaction must never be committed implicitly by this file's BEGIN/autocommit switch.
    server_status = getattr(connection, "server_status", None)
    if transactional and isinstance(server_status, int) and server_status & 1:
        results.append((label, 0, "PREFLIGHT", "caller transaction already active", "requires=transaction"))
        return False
    repeatable_read = "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ"
    committed = False
    ok = True
    try:
        # A preceding file's explicit RC is local to that file, as in a fresh runner session.
        cur.execute(repeatable_read)
        if transactional:
            connection.autocommit(False)
            connection.begin()
        for idx, st in enumerate(statements, 1):
            sql = scratch_schema_sql(executable_statement(st).strip().rstrip(";"))
            if not sql:
                continue
            kind = "EXEC"
            try:
                assertion = statement_assertion(st)
                cur.execute(sql)
                if assertion == "scalar-true":
                    kind = "ASSERT"
                    row = cur.fetchone()
                    if row is None or len(row) != 1 or row[0] not in (0, 1):
                        raise ValueError("scalar-true readback has an invalid shape or value: %r" % (row,))
                    if row[0] != 1:
                        if schema_only and assertion_is_data_only(sql):
                            if observations is not None:
                                observations.append((label, idx, "DATA_ASSERT",
                                                     "scalar-true readback=0", sql.splitlines()[0][:110]))
                            continue
                        raise ValueError("scalar-true readback=0")
                elif assertion:
                    kind = "ASSERT"
                    assert_statement_result(cur, assertion, idx)
            except Exception as error:
                results.append((label, idx, kind,
                                "%s: %s" % (type(error).__name__, str(error)[:200]),
                                sql.splitlines()[0][:110]))
                ok = False
                # A declared transaction cannot continue after any failed statement or assertion.
                if transactional or not keep_going:
                    return False
        if transactional:
            try:
                connection.commit()
                committed = True
            except Exception as error:
                results.append((label, 0, "COMMIT_UNKNOWN",
                                "%s: %s" % (type(error).__name__, str(error)[:200]), "COMMIT"))
                raise
        return ok
    finally:
        # Also discard unfinished hand-written transactions in ordinary SQL files.
        # If rollback/session restoration fails, abort instead of continuing on an unknown session.
        if not committed:
            connection.rollback()
        connection.autocommit(True)
        cur.execute(repeatable_read)


def verify_seed_negative_controls(connection):
    assertions = []
    for st in split_statements(BASELINE_SEED.read_text(encoding="utf-8")):
        if statement_assertion(st) == "scalar-true":
            assertions.append(executable_statement(st).strip().rstrip(";"))
    if len(assertions) != 1:
        raise RuntimeError("base seed 必须且只能有一条 scalar-true 断言")

    cur = connection.cursor()
    for label, mutation in SEED_NEGATIVE_CONTROLS:
        try:
            connection.begin()
            if cur.execute(mutation) != 1:
                raise RuntimeError("base seed 负控未命中:%s" % label)
            cur.execute(assertions[0])
            if int((cur.fetchone() or [1])[0] or 0) != 0:
                raise RuntimeError("base seed 负控未转红:%s" % label)
        finally:
            connection.rollback()
    cur.execute(assertions[0])
    if int((cur.fetchone() or [0])[0] or 0) != 1:
        raise RuntimeError("base seed 负控回滚后未恢复")
    return len(SEED_NEGATIVE_CONTROLS)


def source_provenance_files(commit):
    # Public snapshots retain verified SQL hashes, never the credential-bearing Git history.
    raw = (REPO / "scripts/e2e/source_provenance_20260818.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != "de66c97ad1e18603e6b63448634d68ffc5253440cb4bd94039dbcac3a7ee7192":
        raise RuntimeError("schema source provenance 字节漂移")
    metadata = json.loads(raw)
    if metadata.get("schemaVersion") != 1 or metadata.get("sourceCommit") != commit:
        raise RuntimeError("schema source provenance commit 不匹配")
    files = metadata["files"]
    if any(not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")) for item in files):
        raise RuntimeError("schema source provenance fingerprint 无效")
    result = {item["path"]: item["sha256"] for item in files}
    if len(result) != len(files):
        raise RuntimeError("schema source provenance 路径重复")
    return result



def source_migration_fingerprints(commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit or ""):
        raise RuntimeError("schema replay policy source commit 格式无效")
    if POLICY_CUSTODY is not None:
        if POLICY_CUSTODY.commit != commit:
            raise RuntimeError("Custody source commit mismatch")
        return POLICY_CUSTODY.migrations
    archived = subprocess.run(
        ["git", "archive", "--format=tar", commit, "--", "chengyinhub-admin/sql"],
        cwd=REPO, capture_output=True)
    if archived.returncode != 0:
        return {path: digest for path, digest in source_provenance_files(commit).items()
                if path.startswith("chengyinhub-admin/sql/") and
                (MIGRATION_PATH.fullmatch(path) or path in EXPLICIT_MANAGED_SQL)}
    fingerprints = {}
    with tarfile.open(fileobj=io.BytesIO(archived.stdout), mode="r:") as archive:
        for member in archive.getmembers():
            path = member.name
            if not member.isfile() or not (
                    MIGRATION_PATH.fullmatch(path) or path in EXPLICIT_MANAGED_SQL):
                continue
            fingerprints[path] = hashlib.sha256(archive.extractfile(member).read()).hexdigest()
    return fingerprints


def source_file_bytes(commit, path):
    if (not re.fullmatch(r"[0-9a-f]{40}", commit or "") or
            pathlib.PurePosixPath(path).is_absolute() or
            ".." in pathlib.PurePosixPath(path).parts):
        raise RuntimeError("schema replay policy source file 格式无效")
    if POLICY_CUSTODY is not None:
        if POLICY_CUSTODY.commit != commit or path not in POLICY_CUSTODY.files:
            raise RuntimeError("Custody source file missing or wrong commit")
        return POLICY_CUSTODY.files[path]
    shown = subprocess.run(
        ["git", "show", "%s:%s" % (commit, path)], cwd=REPO, capture_output=True)
    if shown.returncode != 0:
        # Only this reviewed source snippet is retained as bytes in the public snapshot.
        approved = "chengyinhub-admin/sql/migration_menu_2050_coupon_to_supply_20260726.sql"
        expected = source_provenance_files(commit).get(path)
        if path == approved and expected is not None:
            retained = (REPO / "scripts/e2e/fixtures/baseline_menu_2050.sql.txt").read_bytes()
            if hashlib.sha256(retained).hexdigest() != expected:
                raise RuntimeError("schema retained source bytes 指纹不一致:%s" % path)
            return retained
        raise RuntimeError("schema replay policy source file 不可读:%s" % path)
    return shown.stdout


def source_file_fingerprint(commit, path):
    if (not re.fullmatch(r"[0-9a-f]{40}", commit or "") or
            pathlib.PurePosixPath(path).is_absolute() or
            ".." in pathlib.PurePosixPath(path).parts):
        raise RuntimeError("schema replay policy source file 格式无效")
    if POLICY_CUSTODY is not None:
        return hashlib.sha256(source_file_bytes(commit, path)).hexdigest()
    shown = subprocess.run(
        ["git", "show", "%s:%s" % (commit, path)], cwd=REPO, capture_output=True)
    if shown.returncode != 0:
        digest = source_provenance_files(commit).get(path)
        if digest is None:
            raise RuntimeError("schema replay policy source file 不可读:%s" % path)
        return digest
    return hashlib.sha256(shown.stdout).hexdigest()


def verified_policy_custody(directory):
    metadata = json.loads(BASELINE_MANIFEST.read_text(encoding="utf-8"))
    policy = json.loads(SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
    if policy["sourceCommit"] != metadata["sourceCommit"]:
        raise RuntimeError("Custody policy source mismatch")
    expected = {item["path"]: item["sourceSha256"] for item in metadata["migrations"]}
    expected.update(policy["baselineMissingMigrations"])
    if len(expected) != policy["sourceMigrationCount"]:
        raise RuntimeError("Custody policy migration count mismatch")
    return SourceCustody(directory, policy["sourceCommit"], MIGRATION_PATH, EXPLICIT_MANAGED_SQL,
                         expected, policy["baselineSeed"]["sourceFiles"])


def migrations_after_baseline(migrations):
    try:
        metadata = json.loads(BASELINE_MANIFEST.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise RuntimeError("无法读取 base schema migration manifest:%s" % e)
    baseline = metadata.get("baseSchema", {})
    baseline_path = BASELINE.relative_to(REPO).as_posix()
    if (metadata.get("schemaVersion") != 1 or baseline.get("path") != baseline_path or
            hashlib.sha256(BASELINE.read_bytes()).hexdigest() != baseline.get("sha256")):
        raise RuntimeError("base schema 与 migration manifest 不一致")

    try:
        policy = json.loads(SCHEMA_REPLAY_POLICY.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise RuntimeError("无法读取 schema replay policy:%s" % e)

    recorded = metadata.get("migrations", [])
    reconciled = metadata.get("reconciledMigrations", [])
    if (len(recorded) != metadata.get("migrationCount") or
            len(reconciled) != metadata.get("reconciledCount") or
            any(len(item.get("sourceSha256", "")) != 64 or
                not set(item["sourceSha256"]) <= set("0123456789abcdef")
                for item in recorded) or
            any(not item.get("reason") for item in reconciled)):
        raise RuntimeError("base schema migration manifest 计数或 reconciliation 无效")

    included = set()
    for item in recorded:
        path = item.get("path")
        expected = item.get("currentSourceSha256", item.get("sourceSha256"))
        if "currentSourceSha256" in item and not item.get("reason"):
            raise RuntimeError("base schema migration 当前源码校验缺少理由:%s" % path)
        if not path or path in included:
            raise RuntimeError("base schema migration manifest 路径重复或缺失:%s" % path)
        target = REPO / path
        if (not target.is_file() or
                hashlib.sha256(target.read_bytes()).hexdigest() != expected):
            raise RuntimeError("base schema migration 指纹不一致:%s" % path)
        included.add(path)

    for item in reconciled:
        path, expected = item.get("path"), item.get("sha256")
        if not path or path in included:
            raise RuntimeError("base schema migration manifest 路径重复或缺失:%s" % path)
        target = REPO / path
        if (not target.is_file() or
                hashlib.sha256(target.read_bytes()).hexdigest() != expected):
            raise RuntimeError("base schema migration reconciliation 指纹不一致:%s" % path)
        included.add(path)

    current = {m["path"] for m in migrations}
    missing = included - current
    if missing:
        raise RuntimeError("base schema migration 已从台账缺失:%s" % sorted(missing))

    baseline_missing = policy.get("baselineMissingMigrations", {})
    data_only = policy.get("dataOnlyMigrations", {})
    baseline_seed = policy.get("baselineSeed", {})
    if (policy.get("schemaVersion") != 1 or
            policy.get("sourceCommit") != metadata.get("sourceCommit") or
            policy.get("baselineManifestMigrationCount") != len(recorded) or
            policy.get("sourceMigrationCount") != len(recorded) + len(baseline_missing) or
            not policy.get("baselineMissingReason") or
            not policy.get("dataOnlyReason") or
            baseline_seed.get("path") != BASELINE_SEED.relative_to(REPO).as_posix() or
            not baseline_seed.get("reason") or
            not isinstance(baseline_seed.get("sourceFiles"), dict) or
            not isinstance(baseline_missing, dict) or not isinstance(data_only, dict)):
        raise RuntimeError("schema replay policy 版本、计数或理由无效")

    if (hashlib.sha256(BASELINE_SEED.read_bytes()).hexdigest() !=
            baseline_seed.get("sha256")):
        raise RuntimeError("schema replay policy baseline seed 指纹不一致")
    for path, expected in baseline_seed["sourceFiles"].items():
        if source_file_fingerprint(policy["sourceCommit"], path) != expected:
            raise RuntimeError("schema replay policy seed source 指纹不一致:%s" % path)

    expected_source = {item["path"]: item["sourceSha256"] for item in recorded}
    expected_source.update(baseline_missing)
    if source_migration_fingerprints(policy["sourceCommit"]) != expected_source:
        raise RuntimeError("schema replay policy 与 source commit migration 集合或指纹不一致")

    overlap = ((included & (set(baseline_missing) | set(data_only))) |
               (set(baseline_missing) & set(data_only)))
    if overlap:
        raise RuntimeError("schema replay policy 分类重叠:%s" % sorted(overlap))
    for label, entries in (("baseline missing", baseline_missing), ("data-only", data_only)):
        for path, expected in entries.items():
            target = REPO / path
            if path not in current:
                raise RuntimeError("schema replay policy %s 已从台账缺失:%s" % (label, path))
            if (not target.is_file() or not isinstance(expected, str) or len(expected) != 64 or
                    hashlib.sha256(target.read_bytes()).hexdigest() != expected):
                raise RuntimeError("schema replay policy %s 指纹不一致:%s" % (label, path))

    return [m for m in migrations
            if m["path"] not in included and m["path"] not in data_only]


# One reviewed, mutually exclusive pair for a newly created scratch build only.
# These are not baseline/applied/data-only records and do not alter the production ledger.
NOTICE_FORWARD_FILES = {
    "20261024_d_committed_official_notice_wx": (
        "chengyinhub-admin/sql/migration_20261024_d_committed_official_notice_wx.sql",
        "132e2d74d13a3729e40266969d6501aa48a37bd8264361eb81b1f8f0e85b88f3"),
    "20261024_c_before_start_notice_typed": (
        "chengyinhub-admin/sql/migration_20261024_c_before_start_notice_typed.sql",
        "615c8c7883a97ca70e1cc14ff4ac965a895db9ccfea91f6f08e269dbf99e889a"),
    "20261024_g_before_start_notice_metadata_v2": (
        "chengyinhub-admin/sql/migration_20261024_g_before_start_notice_metadata_v2.sql",
        "a36889f13d1de308edfcba06409ef153438521268f355881006864ae842bc02c"),
}
NOTICE_PRODUCER = "20261024_d_committed_official_notice_wx"
NOTICE_OLD = "20261024_c_before_start_notice_typed"
NOTICE_V2 = "20261024_g_before_start_notice_metadata_v2"


def notice_forward_plan(migrations, selected):
    """Validate the exact pending pair before connecting; do not filter or execute it yet."""
    pair_ids = {NOTICE_OLD, NOTICE_V2}
    pair_paths = {NOTICE_FORWARD_FILES[key][0] for key in pair_ids}
    if not any(row.get("migrationId") in pair_ids or row.get("path") in pair_paths
               for row in migrations + selected):
        return None

    positions = {}
    pending_positions = {}
    for migration_id, (path, expected_sha) in NOTICE_FORWARD_FILES.items():
        rows = [(index, row) for index, row in enumerate(migrations)
                if row.get("migrationId") == migration_id or row.get("path") == path]
        pending = [(index, row) for index, row in enumerate(selected)
                   if row.get("migrationId") == migration_id or row.get("path") == path]
        if len(rows) != 1 or len(pending) != 1:
            raise RuntimeError("notice forward requires one complete pending producer/old/v2: " + migration_id)
        index, entry = rows[0]
        pending_index, pending_entry = pending[0]
        target = REPO / path
        if (entry.get("migrationId") != migration_id or entry.get("path") != path
                or entry.get("sha256") != expected_sha or pending_entry != entry
                or entry.get("order") != index + 1 or entry.get("executionPhase") is not None
                or target.is_symlink() or not target.is_file()
                or hashlib.sha256(target.read_bytes()).hexdigest() != expected_sha):
            raise RuntimeError("notice forward identity/fingerprint/order drift: " + migration_id)
        expected_deps = (["20261013_b_im_notification_intent"] if migration_id == NOTICE_PRODUCER
                         else [NOTICE_PRODUCER])
        if entry.get("dependsOn") != expected_deps:
            raise RuntimeError("notice forward dependency drift: " + migration_id)
        positions[migration_id] = index
        pending_positions[migration_id] = pending_index

    if not (positions[NOTICE_PRODUCER] < positions[NOTICE_OLD] < positions[NOTICE_V2]
            and pending_positions[NOTICE_PRODUCER] < pending_positions[NOTICE_OLD] < pending_positions[NOTICE_V2]):
        raise RuntimeError("notice forward producer/old/v2 sequence drift")
    upstream = [index for index, row in enumerate(migrations)
                if row.get("migrationId") == "20261013_b_im_notification_intent"]
    if len(upstream) != 1 or upstream[0] >= positions[NOTICE_PRODUCER]:
        raise RuntimeError("notice forward producer prerequisite is missing or out of order")
    return {
        "fingerprints": {key: {"path": value[0], "sha256": value[1]}
                         for key, value in NOTICE_FORWARD_FILES.items()},
        "selected": [row for row in selected if row["migrationId"] != NOTICE_OLD],
    }


# Broadcast union is another exact, pending-only composition, not a new migration engine.
# Retain BOTH original SQL bodies/checksums; only an absent scratch schema may use this plan.
BROADCAST_D = "20261023_d_official_broadcast_delivery"
BROADCAST_OLD = "20261026_official_broadcast_delivery"
BROADCAST_UNION = "20261028_official_broadcast_protocol_union"
BROADCAST_FORWARD_FILES = {
    BROADCAST_D: ("chengyinhub-admin/sql/migration_20261023_d_official_broadcast_delivery.sql",
                  "c822ad772727ed4ae0328998f7a99be1ece5d6c881f6259fc63de3ebb0822ba1"),
    BROADCAST_OLD: ("chengyinhub-admin/sql/migration_20261026_official_broadcast_delivery.sql",
                    "ca5538fae79b01ac4690065da6cde4ac3b7922dce5c0d09a04a5ff58bca1076f"),
    BROADCAST_UNION: ("chengyinhub-admin/sql/migration_20261028_official_broadcast_protocol_union.sql",
                      "44dc1ee52ab1591f0e893f26aaed8b6b1af4948ab53f98307c41a30c637b8b8b"),
}


def broadcast_forward_plan(migrations, selected):
    ids = {BROADCAST_OLD, BROADCAST_UNION}
    paths = {BROADCAST_FORWARD_FILES[key][0] for key in ids}
    if not any(row.get("migrationId") in ids or row.get("path") in paths
               for row in migrations + selected):
        return None
    positions, pending_positions = {}, {}
    for key, (path, expected_sha) in BROADCAST_FORWARD_FILES.items():
        rows = [(i, row) for i, row in enumerate(migrations)
                if row.get("migrationId") == key or row.get("path") == path]
        pending = [(i, row) for i, row in enumerate(selected)
                   if row.get("migrationId") == key or row.get("path") == path]
        if len(rows) != 1 or len(pending) != 1:
            raise RuntimeError("broadcast forward requires complete pending D/old/union: " + key)
        index, entry = rows[0]
        pending_index, pending_entry = pending[0]
        target = REPO / path
        deps = [BROADCAST_D, BROADCAST_OLD] if key == BROADCAST_UNION else ["official_broadcast"]
        if (entry.get("migrationId") != key or entry.get("path") != path
                or entry.get("sha256") != expected_sha or pending_entry != entry
                or entry.get("order") != index + 1 or entry.get("executionPhase") is not None
                or entry.get("dependsOn") != deps
                or target.is_symlink() or not target.is_file()
                or hashlib.sha256(target.read_bytes()).hexdigest() != expected_sha):
            raise RuntimeError("broadcast forward identity/fingerprint/dependency/order drift: " + key)
        positions[key], pending_positions[key] = index, pending_index
    upstream = [i for i, row in enumerate(migrations) if row.get("migrationId") == "official_broadcast"]
    if (len(upstream) != 1 or upstream[0] >= positions[BROADCAST_D]
            or not positions[BROADCAST_D] < positions[BROADCAST_OLD] < positions[BROADCAST_UNION]
            or not pending_positions[BROADCAST_D] < pending_positions[BROADCAST_OLD] < pending_positions[BROADCAST_UNION]):
        raise RuntimeError("broadcast forward prerequisite/sequence drift")
    return {
        "fingerprints": {key: {"path": value[0], "sha256": value[1]}
                         for key, value in BROADCAST_FORWARD_FILES.items()},
        "selected": [row for row in selected if row["migrationId"] != BROADCAST_OLD],
    }


def main():
    global POLICY_CUSTODY
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema", required=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3316)
    ap.add_argument("--user", default="root")
    ap.add_argument("--password", default="")
    ap.add_argument("--drop", action="store_true", help="先 DROP 再建(默认复用已存在的库)")
    ap.add_argument("--keep-going", action="store_true", help="出错继续,把全部冲突一次收集完")
    ap.add_argument("--policy-source-custody", type=pathlib.Path,
                    help="explicit original SQL Merkle proof; all source object and policy hashes must verify")
    a = ap.parse_args()

    validate_target(a)
    if a.policy_source_custody:
        POLICY_CUSTODY = verified_policy_custody(a.policy_source_custody)
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO,
                          capture_output=True, text=True, check=True).stdout.strip()
    manifest = build_manifest(REPO, target_commit=head,
                              dependency_path=REPO / "ci/migration-dependencies.json")
    all_migrations = manifest["migrations"]
    selected = migrations_after_baseline(all_migrations)
    notice_forward = notice_forward_plan(all_migrations, selected)
    selected = notice_forward["selected"] if notice_forward else selected
    broadcast_forward = broadcast_forward_plan(all_migrations, selected)
    selected = broadcast_forward["selected"] if broadcast_forward else selected
    deferred = [m for m in selected if m.get("executionPhase") == "POST_DEPLOY"]
    ordered = [m for m in selected if m.get("executionPhase") != "POST_DEPLOY"]
    if deferred:
        print("DEFERRED_POST_DEPLOY: " + ", ".join(m["migrationId"] for m in deferred))

    c = connect(a)
    try:
        cur = c.cursor()
        cur.execute("SELECT VERSION(), @@version_comment")
        ver = cur.fetchone()
        print("目标实例: %s / %s  @ %s:%d" % (ver[0], ver[1], a.host, a.port))
        if notice_forward or broadcast_forward:
            # This check precedes every DROP/CREATE/import, including explicit --drop.
            cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (a.schema,))
            fresh = cur.fetchone()
            if (not isinstance(fresh, (tuple, list)) or len(fresh) != 1
                    or type(fresh[0]) is not int or fresh[0] != 0):
                raise RuntimeError("notice forward requires a nonexistent fresh scratch schema; --drop cannot bypass")
            # No IF NOT EXISTS: a concurrent creator must fail, never adopt an unknown/typed schema.
            cur.execute("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % a.schema)
            for forward, old, new in ((notice_forward, NOTICE_OLD, NOTICE_V2),
                                      (broadcast_forward, BROADCAST_OLD, BROADCAST_UNION)):
                if forward:
                    print("REPLACED_UNAPPLIED %s -> %s fingerprints=%s" %
                          (old, new, json.dumps(forward["fingerprints"], sort_keys=True)))
        else:
            if a.drop:
                cur.execute("DROP DATABASE IF EXISTS `%s`" % a.schema)
            cur.execute("CREATE DATABASE IF NOT EXISTS `%s` DEFAULT CHARACTER SET utf8mb4" % a.schema)
    finally:
        c.close()

    c = connect(a, a.schema)
    try:
        cur = c.cursor()
        results = []
        observations = []
        t0 = time.time()
        print("\n[1/3] 导入 base schema 基线快照 …")
        run_file(cur, BASELINE, "baseline", results, keep_going=True)
        cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s", (a.schema,))
        print("      建表数: %d   基线阶段问题: %d" % (cur.fetchone()[0], len(results)))

        print("\n[2/3] 导入指纹化 base seed …")
        baseline_problems = len(results)
        seed_ok = run_file(cur, BASELINE_SEED, "baseline-seed", results, keep_going=False)
        seed_problems = len(results) - baseline_problems
        print("      seed 阶段问题: %d" % seed_problems)
        if not seed_ok:
            for label, idx, kind, msg, sql_head in results:
                print("  [%s] %s #%d  %s\n        %s" %
                      (kind, label, idx, msg, sql_head))
            return 1
        print("      seed 负控: %d/%d 可转红且回滚恢复" %
              (verify_seed_negative_controls(c), len(SEED_NEGATIVE_CONTROLS)))

        print("\n[3/3] 按台账拓扑序叠加 migration …")
        print("      台账内迁移数: %d   快照后待回放: %d" %
              (len(all_migrations), len(ordered)))
        pre_migration_problems = len(results)
        for m in ordered:
            strict_notice = ((bool(notice_forward) and m["migrationId"] == NOTICE_V2)
                             or (bool(broadcast_forward) and m["migrationId"] == BROADCAST_UNION))
            ok = run_file(cur, REPO / m["path"], m["migrationId"], results,
                          keep_going=False if strict_notice else a.keep_going,
                          schema_only=True, observations=observations)
            if not ok and (strict_notice or not a.keep_going):
                break
        cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s", (a.schema,))
        tables = cur.fetchone()[0]

        print("\n===== 结果 =====")
        print("最终表数: %d   耗时: %.1fs" % (tables, time.time() - t0))
        print("基线阶段问题: %d   seed 阶段问题: %d   迁移阶段问题: %d" %
              (baseline_problems, seed_problems, len(results) - pre_migration_problems))
        for label, idx, kind, msg, head in results:
            print("  [%s] %s #%d  %s\n        %s" % (kind, label, idx, msg, head))
        print("数据前置观察: %d（纯 schema 快照不包含业务行，不冒充数据迁移通过）" % len(observations))
        for label, idx, kind, msg, head in observations:
            print("  [%s] %s #%d  %s\n        %s" % (kind, label, idx, msg, head))
        return 0 if not results else 1
    finally:
        c.close()


if __name__ == "__main__":
    sys.exit(main())

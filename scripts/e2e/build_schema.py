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
from ci.run_migration import (statement_assertion, required_transaction_declared,
                              validate_transaction_statements, assert_innodb_references,
                              assert_statement_result)
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
    if required_transaction_declared(text):
        statements = split_statements(text)
        connection = None
        started = False
        idx = 0
        try:
            validate_transaction_statements(statements)
            connection = cur.connection
            if not connection.get_autocommit():
                raise ValueError("transaction-marked file requires an idle autocommit connection")
            server_status = getattr(connection, "server_status", None)
            if not isinstance(server_status, int) or server_status & 1:
                raise ValueError("transaction-marked file cannot own an already active transaction")
            cur.execute("SELECT DATABASE()")
            current_db = cur.fetchone()[0]
            assert_innodb_references(cur, current_db, statements)
            connection.begin()
            started = True
            for idx, statement in enumerate(statements, 1):
                sql = scratch_schema_sql(executable_statement(statement).strip().rstrip(";"))
                if not sql:
                    continue
                cur.execute(sql)
                assertion = statement_assertion(statement)
                if assertion:
                    assert_statement_result(cur, assertion, idx)
            connection.commit()
            return True
        except Exception as failure:
            results.append((label, idx, "TRANSACTION",
                            "%s: %s" % (type(failure).__name__, str(failure)[:200]),
                            "transaction-marked file failed"))
            if started:
                try:
                    connection.rollback()
                except Exception as rollback_failure:
                    results.append((label, idx, "ROLLBACK",
                                    type(rollback_failure).__name__, "rollback could not be confirmed"))
                    raise RuntimeError("transaction rollback could not be confirmed; stop schema rebuild") from rollback_failure
            return False
    for idx, st in enumerate(split_statements(text), 1):
        sql = scratch_schema_sql(executable_statement(st).strip().rstrip(";"))
        if not sql:
            continue
        assertion = statement_assertion(st)
        try:
            cur.execute(sql)
        except Exception as e:
            results.append((label, idx, "EXEC", "%s: %s" % (type(e).__name__, str(e)[:200]),
                            sql.splitlines()[0][:110]))
            if not keep_going:
                return False
            continue
        if assertion == "scalar-true":
            row = cur.fetchone()
            if int((row or [0])[0] or 0) != 1:
                target = results
                kind = "ASSERT"
                if schema_only and assertion_is_data_only(sql):
                    target = observations if observations is not None else []
                    kind = "DATA_ASSERT"
                target.append((label, idx, kind, "scalar-true 求值不为 1",
                               sql.splitlines()[0][:110]))
                if kind == "DATA_ASSERT":
                    continue
                if not keep_going:
                    return False
    return True


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
        if a.drop:
            cur.execute("DROP DATABASE IF EXISTS `%s`" % a.schema)
        cur.execute("CREATE DATABASE IF NOT EXISTS `%s` DEFAULT CHARACTER SET utf8mb4" % a.schema)
    finally:
        c.close()

    c = connect(a, a.schema)
    try:
        cur = c.cursor()
        cur.execute("SELECT @@SESSION.transaction_isolation")
        session_isolation = (cur.fetchone() or [None])[0]
        if session_isolation not in {"READ-UNCOMMITTED", "READ-COMMITTED", "REPEATABLE-READ", "SERIALIZABLE"}:
            raise RuntimeError("unknown initial session isolation; stop schema rebuild")
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
            ok = run_file(cur, REPO / m["path"], m["migrationId"], results,
                          keep_going=a.keep_going, schema_only=True, observations=observations)
            # A file may need READ COMMITTED internally; later range-lock guards must not inherit it.
            server_status = getattr(c, "server_status", None)
            if not c.get_autocommit() or not isinstance(server_status, int) or server_status & 1:
                raise RuntimeError("session isolation restore requires an idle autocommit connection; stop schema rebuild")
            try:
                cur.execute("SET SESSION TRANSACTION ISOLATION LEVEL " + session_isolation.replace("-", " "))
                cur.execute("SELECT @@SESSION.transaction_isolation")
                if (cur.fetchone() or [None])[0] != session_isolation:
                    raise RuntimeError("session isolation readback mismatch")
            except Exception as failure:
                raise RuntimeError("session isolation restore failed; stop schema rebuild") from failure
            if not ok and not a.keep_going:
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

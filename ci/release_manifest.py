#!/usr/bin/env python3
"""发布清单(release-manifest.json)生成器 —— 只读 git 对象,不连任何库/主机。

对给定 commit 输出:公开 SHA + tree、后端模块、按 SHA 查 CI 的命令、JAR 占位、
小程序导出身份(chengyinhub-xcx 的 tree hash + 路径清单)、appid/API 目标占位、
该 commit 上全部 migration_*.sql 的有序清单(sha256 + applied=UNKNOWN)、备份/回滚占位。

⚠️ 本脚本永远不判断「是否已在生产执行」:applied 恒为 UNKNOWN,只有 owner 走
chengyin-db-migrate 只读预检后才能填。JAR 指纹/内嵌 git id 也只留占位,从 CI 制品回填。
不读 project.config.json 的 appid,不写任何主机/IP/凭据。

用法:
  python3 ci/release_manifest.py manifest --commit <rev> [--output release-manifest.json] [--lookup-ci]
  python3 ci/release_manifest.py diff --base <rev> --head <rev>      # 迁移差异(台账用)
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from migration_gates import check_sql_text  # noqa: E402
from migration_ledger import EXPLICIT_MANAGED_SQL, LedgerError, _managed_migration_id, _ordered_migrations  # noqa: E402
from sql_splitter import executable_statement, split_statements_with_delimiters  # noqa: E402

SCHEMA_VERSION = 1
DEFAULT_REPO = "liboyang42-cpu/hengyin"
MINI_DIR = "chengyinhub-xcx"
MIGRATION_DIR = "chengyinhub-admin/sql"
DEPENDENCY_FILE = "ci/migration-dependencies.json"
WORKFLOW_DIR = ".github/workflows"
UNKNOWN = "UNKNOWN"
TO_FILL = "TO_FILL_FROM_CI_ARTEFACT"
OWNER_INPUT = "OWNER_INPUT_REQUIRED"


class ManifestError(RuntimeError):
    pass


def _git(root, *args, binary=False):
    proc = subprocess.run(["git", "-C", root] + list(args), capture_output=True)
    if proc.returncode != 0:
        raise ManifestError("git %s failed: %s" % (args[0], proc.stderr.decode("utf-8", "replace").strip()))
    return proc.stdout if binary else proc.stdout.decode("utf-8")


def resolve_commit(root, rev):
    return _git(root, "rev-parse", "--verify", "%s^{commit}" % rev).strip()


def ls_tree(root, commit, path=None):
    """[(path, blob_sha, size)],只含 blob,按路径字节序。"""
    args = ["ls-tree", "-r", "-z", "--long", commit]
    if path:
        args += ["--", path]
    entries = []
    for record in _git(root, *args).split("\0"):
        if not record:
            continue
        meta, name = record.split("\t", 1)
        _mode, kind, sha, size = meta.split()
        if kind == "blob":
            entries.append((name, sha, int(size)))
    return sorted(entries, key=lambda e: e[0].encode("utf-8"))


def read_blob(root, commit, path):
    return _git(root, "cat-file", "blob", "%s:%s" % (commit, path), binary=True)


def subtree_hash(root, commit, path):
    try:
        return _git(root, "rev-parse", "%s:%s" % (commit, path)).strip()
    except ManifestError:
        return None


def backend_modules(root, commit):
    pom = ET.fromstring(read_blob(root, commit, "pom.xml"))
    return [m.text.strip() for m in pom.iter() if m.tag.split("}")[-1] == "module" and m.text]


def workflow_files(root, commit):
    return [name for name, _sha, _size in ls_tree(root, commit, WORKFLOW_DIR)
            if name.endswith((".yml", ".yaml"))]


def ci_lookup_command(repo, sha):
    return ["gh", "run", "list", "--repo", repo, "--commit", sha, "--limit", "100",
            "--json", "databaseId,workflowName,event,status,conclusion,headSha,url"]


def lookup_ci(repo, sha, runner=subprocess.run):
    """可选:真的去查 CI。失败只记状态,不让清单生成失败(查不到 ≠ CI 绿)。"""
    try:
        proc = runner(ci_lookup_command(repo, sha), capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as error:
        return {"status": "LOOKUP_FAILED", "reason": type(error).__name__, "runs": []}
    if proc.returncode != 0:
        return {"status": "LOOKUP_FAILED", "reason": "gh exit %d" % proc.returncode, "runs": []}
    try:
        runs = json.loads(proc.stdout or "[]")
    except ValueError:
        return {"status": "LOOKUP_FAILED", "reason": "unparseable gh output", "runs": []}
    runs = [r for r in runs if r.get("headSha") == sha]
    return {"status": "LOOKED_UP", "runs": runs}


# ── 迁移静态分析 ─────────────────────────────────────────────────────
_CREATE_TABLE = re.compile(r"^\s*CREATE\s+(TEMPORARY\s+)?TABLE\s+(IF\s+NOT\s+EXISTS\s+)?", re.I)
_CREATE_OTHER = re.compile(r"^\s*CREATE\s+(UNIQUE\s+|FULLTEXT\s+|SPATIAL\s+)?(INDEX|VIEW|TRIGGER|PROCEDURE|FUNCTION|EVENT)\b", re.I)
_CREATE_OR_REPLACE = re.compile(r"^\s*CREATE\s+OR\s+REPLACE\b", re.I)
_ALTER = re.compile(r"^\s*ALTER\s+TABLE\b", re.I)
_INSERT = re.compile(r"^\s*(INSERT|REPLACE)\b", re.I)
_INSERT_TARGET = re.compile(r"^\s*(?:INSERT|REPLACE)\s+(?:IGNORE\s+)?(?:INTO\s+)?`?(\w+)`?", re.I)
_INSERT_GUARD =re.compile(r"\bINSERT\s+IGNORE\b|\bON\s+DUPLICATE\s+KEY\b|\bNOT\s+EXISTS\b|^\s*REPLACE\b", re.I)
_DROP = re.compile(r"^\s*DROP\s+(TEMPORARY\s+)?(TABLE|DATABASE|SCHEMA|VIEW|INDEX|TRIGGER|PROCEDURE|FUNCTION|EVENT)\b", re.I)
_ALTER_DESTRUCTIVE = re.compile(r"\bDROP\s+(COLUMN|INDEX|KEY|PRIMARY|FOREIGN|CONSTRAINT|PARTITION)\b|\bDROP\s+`?\w+`?\s*(,|$)|\bRENAME\b|\bMODIFY\b|\bCHANGE\b", re.I)
_RENAME_TABLE = re.compile(r"^\s*RENAME\s+TABLE\b", re.I)
_TRUNCATE = re.compile(r"^\s*TRUNCATE\b", re.I)
_DELETE = re.compile(r"^\s*DELETE\b", re.I)
_UPDATE = re.compile(r"^\s*UPDATE\b", re.I)
_DYNAMIC_DDL = re.compile(r"'[^']*\b(ALTER\s+TABLE|DROP\s+(TABLE|COLUMN|INDEX)|CREATE\s+(UNIQUE\s+)?INDEX|TRUNCATE)\b[^']*'", re.I)
_SCHEMA_GUARD = re.compile(r"information_schema\.", re.I)
_PREPARE = re.compile(r"^\s*PREPARE\b", re.I)
_WHERE = re.compile(r"\bWHERE\b", re.I)


def analyze_sql(path, text):
    """静态分析:幂等性判断 + 破坏性语句。只看文本,不执行;结论是「需人工复核」的线索。"""
    idempotency_issues = []
    destructive = []
    guarded_dynamic = False
    temporary_tables = set()
    has_schema_guard = bool(_SCHEMA_GUARD.search(text))
    for idx, (statement, _terminator) in enumerate(split_statements_with_delimiters(text), 1):
        sql = executable_statement(statement).strip()
        if not sql:
            continue
        first_line = " ".join(sql.split())[:120]
        create = _CREATE_TABLE.match(sql)
        if create and create.group(1):
            name = re.match(r"`?(\w+)`?", sql[create.end():])
            if name:
                temporary_tables.add(name.group(1).lower())
        if create:
            if not create.group(1) and not create.group(2):
                idempotency_issues.append("#%d CREATE TABLE 无 IF NOT EXISTS" % idx)
        elif _CREATE_OR_REPLACE.match(sql):
            pass
        elif _CREATE_OTHER.match(sql):
            idempotency_issues.append("#%d 直接 CREATE %s(无存在性判断)" % (idx, _CREATE_OTHER.match(sql).group(2).upper()))
        elif _ALTER.match(sql):
            idempotency_issues.append("#%d 直接 ALTER TABLE(未经 information_schema 动态守卫)" % idx)
            if _ALTER_DESTRUCTIVE.search(sql):
                destructive.append("#%d ALTER TABLE 含 DROP/RENAME/MODIFY/CHANGE: %s" % (idx, first_line))
        elif _INSERT.match(sql):
            target = _INSERT_TARGET.match(sql)
            if target and target.group(1).lower() in temporary_tables:
                pass  # 会话级临时表,每次执行重建,不影响幂等
            elif not _INSERT_GUARD.search(sql):
                idempotency_issues.append("#%d INSERT 无 NOT EXISTS/IGNORE/ON DUPLICATE KEY" % idx)
        drop = _DROP.match(sql)
        if drop and not drop.group(1):
            destructive.append("#%d DROP %s: %s" % (idx, drop.group(2).upper(), first_line))
        if _RENAME_TABLE.match(sql):
            destructive.append("#%d RENAME TABLE: %s" % (idx, first_line))
        if _TRUNCATE.match(sql):
            destructive.append("#%d TRUNCATE: %s" % (idx, first_line))
        if _DELETE.match(sql):
            label = "DELETE 带 WHERE(定点删除,需核对范围)" if _WHERE.search(sql) else "DELETE 无 WHERE"
            destructive.append("#%d %s: %s" % (idx, label, first_line))
        dynamic = _DYNAMIC_DDL.search(sql)
        if dynamic:
            if has_schema_guard:
                guarded_dynamic = True
            else:
                idempotency_issues.append("#%d 动态 DDL 无 information_schema 守卫" % idx)
            if re.search(r"\bDROP\b|\bTRUNCATE\b", dynamic.group(0), re.I):
                destructive.append("#%d 动态 SQL 含 DROP/TRUNCATE: %s" % (idx, first_line))
        if _PREPARE.match(sql) and not has_schema_guard:
            idempotency_issues.append("#%d PREPARE 动态语句,文件内无 information_schema 守卫" % idx)
    gate_problems = check_sql_text(path, text)  # 复用迁移门禁:未闭合/DELIMITER/无 WHERE 写
    for problem in gate_problems:
        if "没有 WHERE" in problem:
            destructive.append("migration_gates: %s" % problem.split(": ", 1)[-1].splitlines()[0])
    if idempotency_issues:
        verdict = "NEEDS_REVIEW"
    elif guarded_dynamic:
        verdict = "GUARDED_INFORMATION_SCHEMA"
    else:
        verdict = "IDEMPOTENT_STATIC"
    return {
        "idempotency": verdict,
        "idempotencyIssues": idempotency_issues,
        "informationSchemaGuard": has_schema_guard,
        "destructiveStatements": destructive,
        "gateProblems": [p.splitlines()[0] for p in gate_problems],
    }


def _dependencies_at(root, commit):
    try:
        data = json.loads(read_blob(root, commit, DEPENDENCY_FILE).decode("utf-8"))
    except ManifestError:
        return {}
    if data.get("schemaVersion") != 1 or not isinstance(data.get("dependencies"), dict):
        raise ManifestError("invalid %s at %s" % (DEPENDENCY_FILE, commit))
    return data["dependencies"]


def migrations_at(root, commit):
    """该 commit 上全部受管迁移(migration_*.sql + 显式纳管 seed),按 runner 同一规则排序(显式依赖 → 路径字节序)。"""
    items = []
    for path, blob, size in ls_tree(root, commit, MIGRATION_DIR):
        migration_id = _managed_migration_id(path)
        if not migration_id:
            continue
        body = read_blob(root, commit, path)
        items.append({
            "migrationId": migration_id,
            # 非 migration_*.sql 命名、但被 runner 显式纳管的历史 seed(见 migration_ledger.EXPLICIT_MANAGED_SQL)
            "explicitManaged": path in EXPLICIT_MANAGED_SQL,
            "path": path,
            "sha256": hashlib.sha256(body).hexdigest(),
            "gitBlob": blob,
            "bytes": size,
        })
    try:
        ordered = _ordered_migrations(items, _dependencies_at(root, commit))
    except LedgerError as error:
        raise ManifestError("migration ordering failed: %s %s" % (error, error.problems))
    return ordered


def mini_program_identity(root, commit):
    entries = ls_tree(root, commit, MINI_DIR)
    app_json = next((e for e in entries if e[0] == MINI_DIR + "/app.json"), None)
    return {
        "path": MINI_DIR,
        "treeHash": subtree_hash(root, commit, MINI_DIR),
        "fileCount": len(entries),
        "appJsonBlob": app_json[1] if app_json else None,
        "projectConfigBlob": next((e[1] for e in entries if e[0] == MINI_DIR + "/project.config.json"), None),
        "files": [{"path": p, "blob": s, "bytes": n} for p, s, n in entries],
        "exportRule": "从本 treeHash 干净检出后导出;禁止复用旧导出目录/旧 app.json",
        "mainPackageLimitBytes": 2 * 1024 * 1024,
        "mainPackageCompiledBytes": "TO_FILL_FROM_DEVTOOLS_UPLOAD_INFO",
        "appid": OWNER_INPUT,
        "apiTarget": OWNER_INPUT,
    }


def build_manifest(root, rev, repo=DEFAULT_REPO, ci_lookup=False, runner=subprocess.run):
    sha = resolve_commit(root, rev)
    migrations = migrations_at(root, sha)
    for item in migrations:
        item["applied"] = UNKNOWN
    ci = {
        "repo": repo,
        "lookupCommand": " ".join(ci_lookup_command(repo, sha)),
        "expectedWorkflows": workflow_files(root, sha),
        "jarWorkflow": WORKFLOW_DIR + "/backend-jar.yml",
        "jarArtefactName": "backend-jar-" + sha,
    }
    if ci_lookup:
        ci["lookup"] = lookup_ci(repo, sha, runner=runner)
    else:
        ci["lookup"] = {"status": "NOT_LOOKED_UP", "runs": []}
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedFrom": "ci/release_manifest.py (git objects only; no DB/host access)",
        "public": {"repo": repo, "commit": sha, "tree": _git(root, "rev-parse", sha + "^{tree}").strip()},
        "backend": {"modules": backend_modules(root, sha)},
        "ci": ci,
        "jar": {
            "path": "chengyinhub-admin/target/chengyinhub-admin.jar",
            "bytes": TO_FILL,
            "sha256": TO_FILL,
            "embeddedGitCommitId": TO_FILL,
            "embeddedGitCommitIdMustEqual": sha,
            "privateUpload": {"location": OWNER_INPUT, "deployed": False},
        },
        "miniProgram": mini_program_identity(root, sha),
        "migrations": {
            "directory": MIGRATION_DIR,
            "ordering": "explicit-dependencies-then-path-bytewise (" + DEPENDENCY_FILE + ")",
            "count": len(migrations),
            "appliedStateSource": "未知;只能由 owner 经 chengyin-db-migrate 只读预检回填,本脚本永不判定",
            "items": migrations,
        },
        "backup": {"databaseBackupId": OWNER_INPUT, "backupSha256": OWNER_INPUT, "restoreTested": UNKNOWN},
        "rollback": {
            "previousJarSha256": OWNER_INPUT,
            "previousGitCommitId": OWNER_INPUT,
            "note": "JAR 回滚不撤销 DDL;DDL 回滚需单独评审的前向修复迁移",
        },
    }


def migration_diff(root, base, head):
    """base..head 之间新增/修改/删除的 migration_*.sql,附静态分析与 head 上的执行序。"""
    base_sha, head_sha = resolve_commit(root, base), resolve_commit(root, head)
    base_items = {m["path"]: m for m in migrations_at(root, base_sha)}
    head_items = migrations_at(root, head_sha)
    head_by_path = {m["path"]: m for m in head_items}
    rows = []
    for item in head_items:
        before = base_items.get(item["path"])
        if before and before["sha256"] == item["sha256"]:
            continue
        text = read_blob(root, head_sha, item["path"]).decode("utf-8")
        row = {
            "change": "MODIFIED" if before else "ADDED",
            "migrationId": item["migrationId"],
            "path": item["path"],
            "sha256": item["sha256"],
            "baseSha256": before["sha256"] if before else None,
            "order": item["order"],
            "dependsOn": item["dependsOn"],
            "applied": UNKNOWN,
            "needsOwnerApproval": True,
        }
        row.update(analyze_sql(item["path"], text))
        rows.append(row)
    for path, before in sorted(base_items.items()):
        if path not in head_by_path:
            rows.append({"change": "DELETED", "migrationId": before["migrationId"], "path": path,
                         "sha256": None, "baseSha256": before["sha256"], "applied": UNKNOWN,
                         "needsOwnerApproval": True})
    return {"base": base_sha, "head": head_sha, "headMigrationCount": len(head_items), "changes": rows}


def _write(payload, output):
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if output:
        with open(output, "w", encoding="utf-8") as stream:
            stream.write(text)
    else:
        sys.stdout.write(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".")
    sub = parser.add_subparsers(dest="command", required=True)
    m = sub.add_parser("manifest")
    m.add_argument("--commit", required=True)
    m.add_argument("--repo", default=DEFAULT_REPO)
    m.add_argument("--lookup-ci", action="store_true", help="调用 gh 按 SHA 查 CI(默认只输出查询命令)")
    m.add_argument("--output")
    d = sub.add_parser("diff")
    d.add_argument("--base", required=True)
    d.add_argument("--head", required=True)
    d.add_argument("--output")
    args = parser.parse_args(argv)
    root = os.path.abspath(args.root)
    try:
        if args.command == "manifest":
            payload = build_manifest(root, args.commit, repo=args.repo, ci_lookup=args.lookup_ci)
        else:
            payload = migration_diff(root, args.base, args.head)
    except ManifestError as error:
        print("release_manifest: %s" % error, file=sys.stderr)
        return 2
    _write(payload, args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())

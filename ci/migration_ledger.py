#!/usr/bin/env python3
"""Deterministic migration manifest and read-only release planning.

This module never connects to a database and intentionally has no apply command.
It records paths/checksums/state metadata only; SQL bodies are never emitted.
"""

import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from migration_gates import check_sql_text  # noqa: E402
from sql_splitter import split_statements  # noqa: E402


MANAGED_DIR = "chengyinhub-admin/sql"
MIGRATION_PREFIX = "migration_"
# Only these explicitly approved new registration repairs are deferred; historical phases remain immutable.
POST_DEPLOY_MIGRATIONS = {"20261012_merchant_commerce_reconcile_job", "20261012_monitor_missing_jobs", "20261012_roam_presence_revoke_job"}
BOOTSTRAP_SQL = {
    "sql/quartz.sql",
    "sql/ry_20250522.sql",
    # 2026-08-18 补:城瘾 base schema 结构快照(零数据)。与上面两份【同目录、同角色】——
    # 从零重建 schema 用的可执行 DDL,不是 migration:不进台账、不由 runner 执行、
    # 不在生产上增量跑。增量结构变更仍走 migration_*.sql。
    # 分类不能用 REFERENCE_ONLY:那一档要过 REFERENCE_SQL_NOT_INERT 惰性校验,
    # 而本文件含 217 条可执行 CREATE TABLE。
    "sql/base_schema_20260818.sql",
    "sql/base_seed_20260818.sql",
}
AUXILIARY_PREFIXES = (
    "_deploy_",
    "_precheck_",
    "bak_",
    "diag_",
    "preflight_",
    "report_",
)
# 历史文件不可重命名(路径/checksum 已是审计事实),只能逐文件显式分类。这里刻意不用
# seed_* 或 test/resources/** 通配符：任何新增未知 SQL 仍必须先审阅并报红。
EXPLICIT_MANAGED_SQL = {
    "chengyinhub-admin/sql/seed_preset_demo.sql": "seed_preset_demo",
    "chengyinhub-admin/sql/seed_refund_manual_menu_20260714.sql":
        "seed_refund_manual_menu_20260714",
}
EXPLICIT_AUXILIARY_SQL = {
    # Architecture-day A/D: exact disposable test fixtures and non-executed
    # review proposals; canonical executable migrations remain fully checked.
    "chengyinhub-admin/src/test/resources/architecture-day-a-fixture.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/official-broadcast-delivery-it-schema.sql": "TEST_FIXTURE",
    "docs/delivery/architecture-day/A/schema-candidate.sql": "AUXILIARY_NOT_EXECUTED",
    "docs/delivery/architecture-day/A/schema-preflight.sql": "AUXILIARY_NOT_EXECUTED",
    # Historical source-only station proposal; canonical executable DDL is the
    # separately managed migration_20261025_coop_order_station_fulfillment.sql.
    "docs/delivery/architecture-day/G/supply-order-fulfillment-contract/PROPOSED_TABLE.sql":
        "AUXILIARY_NOT_EXECUTED",
    # Only the reviewed synthetic schema for the disposable, network-isolated startup gate.
    "ci/upgrade-startup/schema.sql": "TEST_FIXTURE",
    "chengyinhub-admin/sql/_四账映射与守恒规则_20260725.sql": "REFERENCE_ONLY",
    "chengyinhub-admin/sql/seed_official_content_template.sql": "REFERENCE_ONLY",
    "chengyinhub-admin/src/test/resources/it-schema/cms-topic-it-schema.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/wp10-content-copy-scratch.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/wp10-content-graph-scratch.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/fixtures/im-club-conversation-batch.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/coop-it-schema.sql": "TEST_FIXTURE",
    # #398 F1 移植:view_coupon_history 联表视图的测试建表资源(SELECT 照抄 base_schema_20260818),
    # 只在 H2 内存库里执行,不进发布台账、不在生产跑。
    "chengyinhub-admin/src/test/resources/reward/coupon-history-join-view.sql": "TEST_FIXTURE",
    # R11-A 新增(晚于 #732):两套探店日联盟 IT 收口到门禁唯一临时 schema 时用的建表资源。
    # 与上面两份同档 —— 它只被 ExploreItDatabase 在临时 schema 里执行,不进发布台账、不在生产跑。
    "chengyinhub-admin/src/test/resources/it-schema/explore-alliance-it-schema.sql": "TEST_FIXTURE",
    # 2026-08-18 并入 master #738 时补:ComplaintPickerOwnerType3RealDbIT 的建表资源,
    # 与上面几份同档 —— 只在 IT 的临时 schema 里执行,不进发布台账、不在生产跑。
    # 它是 master 带进来的新文件,分类没跟着登记 ⇒ ledger audit 当场报 UNMANAGED_SQL_LOCATION。
    "chengyinhub-admin/src/test/resources/it-schema/complaint-it-schema.sql": "TEST_FIXTURE",
    # R9-35:商家主表真库 IT 的建表资源(基线快照 base_schema_20260818 + 已合入迁移投影,
    # 含本批 disable_reason)。与上面几份同档:只在 IT 的临时 schema 里执行,不进发布台账。
    "chengyinhub-admin/src/test/resources/it-schema/merchant-it-schema.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/run41-prod-ddl.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/run41-prod-seed.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/game-module-mystery-market-seed.sql":
        "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/run41-prod-ddl.sql": "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/run41-prod-seed.sql": "TEST_FIXTURE",
    # R12-E2E:旅程全链路真库 IT 的存量表建表资源(cms_topic_node / cms_topic_chapter /
    # cms_member_template / player_node_progress / cms_registration / npc_profile)。
    # 与上面几份同档 —— 只在 IT 的临时 schema 里执行,不进发布台账、不在生产跑;
    # 这几张表没有可重建的 migration(仓库里只有增量 ALTER),才落成 fixture。
    "chengyinhub-admin/src/test/resources/it-schema/journey-full-flow-it-schema.sql":
        "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/merchant-chapter-offer-it-schema.sql":
        "TEST_FIXTURE",
    "chengyinhub-admin/src/test/resources/it-schema/generic-route-runtime-it-schema.sql":
        "TEST_FIXTURE",
    # GameplayReskinMySqlIT独占库：复用真实票/场次DDL补商业策略依赖，不属于生产迁移。
    "chengyinhub-admin/src/test/resources/it-schema/gameplay-reskin-commercial-it-schema.sql":
        "TEST_FIXTURE",
    # 发布迁移包的只读预检：与 chengyinhub-admin/sql/_precheck_* 同档 —— 全是 SELECT，runner 不执行、不进台账；
    # 由 ci/release_migration_rehearsal.py 逐句校验只含 SELECT 并在一次性 MySQL 上回读。
    "docs/delivery/release-closeout-20261022/release-migration-precheck.sql": "AUXILIARY_NOT_EXECUTED",
    "docs/delivery/release-closeout-20261028/release-migration-precheck.sql": "AUXILIARY_NOT_EXECUTED",
    "docs/delivery/release-closeout-20261029/release-migration-precheck.sql": "AUXILIARY_NOT_EXECUTED",
}
IGNORED_DIRS = {".git", "node_modules", "target"}
MIGRATION_ID = re.compile(r"^[a-z0-9][a-z0-9_]*$")
MIGRATION_PATH = re.compile(
    r"^chengyinhub-admin/sql/migration_([a-z0-9][a-z0-9_]*)\.sql$"
)
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")
HISTORY_STATUSES = {"APPLYING", "APPLIED", "UNKNOWN", "RECONCILED"}


class LedgerError(ValueError):
    """Fail-closed contract violation with safe, metadata-only details."""

    def __init__(self, message, problems=None):
        super().__init__(message)
        self.problems = problems or []


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _all_sql_files(root):
    files = []
    for dirpath, dirnames, filenames in os.walk(str(root)):
        dirnames[:] = sorted(d for d in dirnames if d not in IGNORED_DIRS)
        for filename in sorted(filenames):
            if filename.lower().endswith(".sql"):
                files.append(Path(dirpath) / filename)
    return sorted(files, key=lambda p: p.relative_to(root).as_posix().encode("utf-8"))


def _problem(code, path, message):
    return {"code": code, "path": path, "message": message}


def _auxiliary_classification(relative_path):
    if relative_path in EXPLICIT_AUXILIARY_SQL:
        return EXPLICIT_AUXILIARY_SQL[relative_path]
    path = Path(relative_path)
    if relative_path in BOOTSTRAP_SQL:
        return "BOOTSTRAP_ONLY"
    if path.parent.as_posix() == MANAGED_DIR and path.name.startswith(AUXILIARY_PREFIXES):
        return "AUXILIARY_NOT_EXECUTED"
    return None


def _managed_migration_id(relative_path):
    explicit = EXPLICIT_MANAGED_SQL.get(relative_path)
    if explicit:
        return explicit
    match = MIGRATION_PATH.fullmatch(relative_path)
    return match.group(1) if match else None


def audit_inventory(root):
    """Inventory every SQL file and return metadata-only problems/classification."""
    root = Path(root).resolve()
    managed_dir = root / MANAGED_DIR
    if not managed_dir.is_dir():
        raise LedgerError("missing managed migration directory", [
            _problem("MISSING_MIGRATION_DIR", MANAGED_DIR, "required migration directory is missing")
        ])

    migrations = []
    auxiliary = []
    problems = []
    all_files = _all_sql_files(root)
    migration_candidates = []

    for path in all_files:
        rel = path.relative_to(root).as_posix()
        name = path.name
        parent = path.parent.relative_to(root).as_posix()
        is_migration_name = name.casefold().startswith(MIGRATION_PREFIX)
        is_managed_first_level = parent == MANAGED_DIR

        if path.is_symlink():
            problems.append(_problem("SQL_SYMLINK_FORBIDDEN", rel, "SQL files must be regular files"))
            continue

        explicit_migration_id = EXPLICIT_MANAGED_SQL.get(rel)
        if explicit_migration_id:
            sql_problems = check_sql_text(rel, path.read_text(encoding="utf-8"))
            if sql_problems:
                problems.append(_problem(
                    "UNSUPPORTED_SQL_SHAPE",
                    rel,
                    "migration cannot be safely split by ci/sql_splitter.py",
                ))
            migrations.append({
                "migrationId": explicit_migration_id,
                "path": rel,
                "sha256": _sha256(path),
            })
            if rel == "chengyinhub-admin/sql/seed_preset_demo.sql":
                # 保留历史路径/checksum，但演示数据不能进入新的生产执行计划。
                migrations[-1]["executionScope"] = "TEST_FIXTURE"
            continue

        if is_migration_name:
            migration_candidates.append(path)
            if not is_managed_first_level:
                problems.append(_problem(
                    "INVALID_MIGRATION_LOCATION",
                    rel,
                    "migration files must be in chengyinhub-admin/sql first level",
                ))
                continue
            if not name.startswith(MIGRATION_PREFIX):
                problems.append(_problem(
                    "INVALID_MIGRATION_PREFIX",
                    rel,
                    "migration prefix is case-sensitive and must be lowercase",
                ))
                continue
            if path.suffix != ".sql":
                problems.append(_problem(
                    "INVALID_MIGRATION_EXTENSION",
                    rel,
                    "migration extension is case-sensitive and must be lowercase .sql",
                ))
                continue
            migration_id = path.stem[len(MIGRATION_PREFIX):]
            if not MIGRATION_ID.fullmatch(migration_id):
                problems.append(_problem(
                    "INVALID_MIGRATION_ID",
                    rel,
                    "migration id must contain lowercase letters, digits, and underscores only",
                ))
                continue
            sql_problems = check_sql_text(rel, path.read_text(encoding="utf-8"))
            if sql_problems:
                problems.append(_problem(
                    "UNSUPPORTED_SQL_SHAPE",
                    rel,
                    "migration cannot be safely split by ci/sql_splitter.py",
                ))
            migrations.append({
                "migrationId": migration_id,
                "path": rel,
                "sha256": _sha256(path),
                **({"executionPhase": "POST_DEPLOY"} if migration_id in POST_DEPLOY_MIGRATIONS else {}),
            })
            continue

        classification = _auxiliary_classification(rel)
        if classification:
            if classification == "REFERENCE_ONLY":
                text = path.read_text(encoding="utf-8")
                if split_statements(text) or check_sql_text(rel, text):
                    problems.append(_problem(
                        "REFERENCE_SQL_NOT_INERT",
                        rel,
                        "reference-only SQL must contain no executable statements",
                    ))
            auxiliary.append({
                "path": rel,
                "sha256": _sha256(path),
                "classification": classification,
            })
        elif is_managed_first_level:
            problems.append(_problem(
                "INVALID_EXECUTABLE_PREFIX",
                rel,
                "unclassified SQL in managed directory must not be omitted from releases",
            ))
        else:
            problems.append(_problem(
                "UNMANAGED_SQL_LOCATION",
                rel,
                "SQL outside the managed directory requires an explicit reviewed classification",
            ))

    by_case_name = defaultdict(list)
    by_id = defaultdict(list)
    for path in migration_candidates:
        rel = path.relative_to(root).as_posix()
        by_case_name[path.name.casefold()].append(rel)
        if path.stem.casefold().startswith(MIGRATION_PREFIX):
            by_id[path.stem[len(MIGRATION_PREFIX):].casefold()].append(rel)
    for paths in by_case_name.values():
        if len(paths) > 1:
            for rel in sorted(paths):
                problems.append(_problem(
                    "CASE_COLLISION", rel, "migration filename collides on case-insensitive filesystems"
                ))
    for paths in by_id.values():
        if len(paths) > 1:
            for rel in sorted(paths):
                problems.append(_problem(
                    "DUPLICATE_MIGRATION_ID", rel, "migration id must be globally unique"
                ))
    for rel, migration_id in EXPLICIT_MANAGED_SQL.items():
        if (root / rel).is_file():
            by_id[migration_id.casefold()].append(rel)
    for paths in by_id.values():
        if len(paths) > 1:
            existing = {
                (problem["code"], problem["path"])
                for problem in problems
            }
            for rel in sorted(paths):
                if ("DUPLICATE_MIGRATION_ID", rel) not in existing:
                    problems.append(_problem(
                        "DUPLICATE_MIGRATION_ID", rel, "migration id must be globally unique"
                    ))

    return {
        "schemaVersion": 1,
        "sqlFileCount": len(all_files),
        "managedMigrationCount": len(migrations),
        "migrations": sorted(migrations, key=lambda m: m["path"].encode("utf-8")),
        "auxiliarySql": sorted(auxiliary, key=lambda item: item["path"].encode("utf-8")),
        "problems": sorted(
            problems,
            key=lambda item: (item["path"].encode("utf-8"), item["code"]),
        ),
    }


def _load_dependencies(root, dependency_path=None):
    path = Path(dependency_path) if dependency_path else Path(root) / "ci/migration-dependencies.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1 or not isinstance(data.get("dependencies"), dict):
        raise LedgerError("invalid dependency metadata")
    return data["dependencies"]


def _ordered_migrations(migrations, dependencies):
    by_id = {m["migrationId"]: dict(m) for m in migrations}
    problems = []
    normalized = {}
    for migration_id, raw_dependencies in dependencies.items():
        if migration_id not in by_id:
            problems.append(_problem(
                "UNKNOWN_DEPENDENCY_OWNER", migration_id, "dependency owner is not in the manifest"
            ))
            continue
        if not isinstance(raw_dependencies, list) or any(not isinstance(x, str) for x in raw_dependencies):
            problems.append(_problem(
                "INVALID_DEPENDENCY_LIST", by_id[migration_id]["path"], "dependencies must be a string list"
            ))
            continue
        deps = sorted(set(raw_dependencies), key=lambda value: value.encode("utf-8"))
        for dependency in deps:
            if dependency not in by_id:
                problems.append(_problem(
                    "UNKNOWN_DEPENDENCY",
                    by_id[migration_id]["path"],
                    "declared dependency is not in the manifest",
                ))
            if dependency == migration_id:
                problems.append(_problem(
                    "SELF_DEPENDENCY", by_id[migration_id]["path"], "migration cannot depend on itself"
                ))
        normalized[migration_id] = deps
    if problems:
        raise LedgerError("invalid migration dependencies", problems)

    for migration_id in by_id:
        normalized.setdefault(migration_id, [])
    children = defaultdict(list)
    indegree = {migration_id: len(deps) for migration_id, deps in normalized.items()}
    for migration_id, deps in normalized.items():
        for dependency in deps:
            children[dependency].append(migration_id)

    ready = sorted(
        (migration_id for migration_id, count in indegree.items() if count == 0),
        key=lambda migration_id: by_id[migration_id]["path"].encode("utf-8"),
    )
    ordered = []
    while ready:
        migration_id = ready.pop(0)
        ordered.append(migration_id)
        for child in sorted(children[migration_id], key=lambda item: by_id[item]["path"].encode("utf-8")):
            indegree[child] -= 1
            if indegree[child] == 0:
                ready.append(child)
                ready.sort(key=lambda item: by_id[item]["path"].encode("utf-8"))
    if len(ordered) != len(by_id):
        raise LedgerError("migration dependency cycle", [
            _problem("DEPENDENCY_CYCLE", MANAGED_DIR, "dependency graph must be acyclic")
        ])

    result = []
    for index, migration_id in enumerate(ordered, 1):
        item = by_id[migration_id]
        item["order"] = index
        item["dependsOn"] = normalized[migration_id]
        result.append(item)
    return result


def build_manifest(root, target_commit, dependencies=None, dependency_path=None):
    root = Path(root).resolve()
    if not FULL_SHA.fullmatch(target_commit or ""):
        raise LedgerError("target commit must be a full lowercase 40-character SHA")
    inventory = audit_inventory(root)
    if inventory["problems"]:
        raise LedgerError("SQL inventory is unsafe", inventory["problems"])
    if dependencies is None:
        dependencies = _load_dependencies(root, dependency_path)
    ordered = _ordered_migrations(inventory["migrations"], dependencies)
    return {
        "schemaVersion": 1,
        "targetCommit": target_commit,
        "ordering": {
            "strategy": "explicit-dependencies-then-path-bytewise",
            "dependencySource": "ci/migration-dependencies.json",
        },
        "inventory": {
            "sqlFileCount": inventory["sqlFileCount"],
            "managedMigrationCount": len(ordered),
            "auxiliarySql": inventory["auxiliarySql"],
        },
        "migrations": ordered,
    }


def verify_manifest_files(root, manifest):
    root = Path(root).resolve()
    problems = []
    for migration in manifest.get("migrations", []):
        rel = migration.get("path", "")
        if _managed_migration_id(rel) != migration.get("migrationId"):
            problems.append(_problem(
                "MANIFEST_PATH_INVALID", rel, "manifest path and migration id must be canonical"
            ))
            continue
        path = root / rel
        if not path.is_file() or path.is_symlink():
            problems.append(_problem("MANIFEST_FILE_MISSING", rel, "manifest file is missing"))
        elif _sha256(path) != migration.get("sha256"):
            problems.append(_problem("MANIFEST_CONTENT_DRIFT", rel, "file checksum differs from manifest"))
    return problems


def verify_target_commit(root, manifest):
    """Prove the target commit itself contains the exact migration bytes."""
    root = Path(root).resolve()
    target = manifest.get("targetCommit", "")
    problems = []
    try:
        subprocess.run(
            ["git", "cat-file", "-e", "%s^{commit}" % target],
            cwd=str(root),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return [_problem("TARGET_COMMIT_NOT_FOUND", target, "target commit is not available in this repository")]

    manifest_paths = {item["path"] for item in manifest.get("migrations", [])}
    try:
        tree_output = subprocess.check_output(
            ["git", "-c", "core.quotePath=false", "ls-tree", "-r", "--name-only", target,
             "--", MANAGED_DIR],
            cwd=str(root),
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        tree_output = ""
    target_paths = {
        rel for rel in tree_output.splitlines()
        if _managed_migration_id(rel) is not None
    }
    for rel in sorted(manifest_paths.symmetric_difference(target_paths)):
        problems.append(_problem(
            "TARGET_COMMIT_INVENTORY_MISMATCH",
            rel,
            "target commit and manifest contain different migration path sets",
        ))

    try:
        all_tree_output = subprocess.check_output(
            ["git", "-c", "core.quotePath=false", "ls-tree", "-r", "--name-only", target],
            cwd=str(root),
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        all_tree_output = ""
    target_sql_paths = {
        rel for rel in all_tree_output.splitlines()
        if rel.lower().endswith(".sql") and not IGNORED_DIRS.intersection(Path(rel).parts)
    }
    auxiliary_items = manifest.get("inventory", {}).get("auxiliarySql", [])
    manifest_auxiliary = {item.get("path"): item for item in auxiliary_items}
    declared_sql_paths = manifest_paths.union(manifest_auxiliary)
    for rel in sorted(target_sql_paths.symmetric_difference(declared_sql_paths)):
        problems.append(_problem(
            "TARGET_COMMIT_SQL_INVENTORY_MISMATCH",
            rel,
            "target commit SQL inventory differs from managed plus explicitly classified SQL",
        ))
    for rel, item in sorted(manifest_auxiliary.items()):
        classification = _auxiliary_classification(rel or "")
        if not classification or item.get("classification") != classification:
            problems.append(_problem(
                "TARGET_COMMIT_AUXILIARY_CLASSIFICATION_INVALID",
                rel or "",
                "auxiliary SQL classification is not allowed by repository policy",
            ))
            continue
        try:
            content = subprocess.check_output(
                ["git", "show", "%s:%s" % (target, rel)],
                cwd=str(root),
                stderr=subprocess.DEVNULL,
            )
        except (OSError, subprocess.CalledProcessError):
            problems.append(_problem(
                "TARGET_COMMIT_AUXILIARY_FILE_MISSING",
                rel,
                "declared auxiliary SQL is absent from the target commit",
            ))
            continue
        if hashlib.sha256(content).hexdigest() != item.get("sha256"):
            problems.append(_problem(
                "TARGET_COMMIT_AUXILIARY_CONTENT_MISMATCH",
                rel,
                "target commit auxiliary SQL bytes differ from the manifest",
            ))

    try:
        dependency_blob = subprocess.check_output(
            ["git", "show", "%s:ci/migration-dependencies.json" % target],
            cwd=str(root),
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        target_dependencies = {}
    else:
        try:
            dependency_payload = json.loads(dependency_blob)
            if (dependency_payload.get("schemaVersion") != 1
                    or not isinstance(dependency_payload.get("dependencies"), dict)):
                raise ValueError("invalid dependency metadata")
            target_dependencies = dependency_payload["dependencies"]
        except (AttributeError, ValueError, json.JSONDecodeError):
            problems.append(_problem(
                "TARGET_COMMIT_DEPENDENCY_INVALID",
                "ci/migration-dependencies.json",
                "target commit dependency metadata is invalid",
            ))
            target_dependencies = None

    if target_dependencies is not None:
        try:
            expected_order = _ordered_migrations(manifest.get("migrations", []), target_dependencies)
        except LedgerError:
            problems.append(_problem(
                "TARGET_COMMIT_DEPENDENCY_INVALID",
                "ci/migration-dependencies.json",
                "target commit dependency graph is invalid",
            ))
        else:
            expected = [
                (item["migrationId"], item["order"], item["dependsOn"])
                for item in expected_order
            ]
            actual = [
                (item.get("migrationId"), item.get("order"), item.get("dependsOn"))
                for item in manifest.get("migrations", [])
            ]
            if actual != expected:
                problems.append(_problem(
                    "TARGET_COMMIT_DEPENDENCY_MISMATCH",
                    "ci/migration-dependencies.json",
                    "manifest order or dependencies differ from target commit metadata",
                ))

    for migration in manifest.get("migrations", []):
        rel = migration["path"]
        try:
            content = subprocess.check_output(
                ["git", "show", "%s:%s" % (target, rel)],
                cwd=str(root),
                stderr=subprocess.DEVNULL,
            )
        except (OSError, subprocess.CalledProcessError):
            problems.append(_problem(
                "TARGET_COMMIT_FILE_MISSING", rel, "migration is absent from the target commit"
            ))
            continue
        if hashlib.sha256(content).hexdigest() != migration["sha256"]:
            problems.append(_problem(
                "TARGET_COMMIT_CONTENT_MISMATCH", rel, "target commit bytes differ from the manifest"
            ))
    return problems


def migration_ids_at_commit(root, target_commit):
    """Return managed migration IDs present at a reconciled bootstrap commit."""
    if not FULL_SHA.fullmatch(target_commit or ""):
        raise LedgerError("bootstrap target commit must be a full lowercase SHA")
    try:
        tree_output = subprocess.check_output(
            ["git", "-c", "core.quotePath=false", "ls-tree", "-r", "--name-only",
             target_commit, "--", MANAGED_DIR],
            cwd=str(Path(root).resolve()),
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        raise LedgerError("bootstrap target commit is not available")
    ids = set()
    for rel in tree_output.splitlines():
        migration_id = _managed_migration_id(rel)
        if migration_id is not None:
            if not MIGRATION_ID.fullmatch(migration_id):
                raise LedgerError("bootstrap commit contains an invalid migration id")
            ids.add(migration_id)
    return ids


def _validate_history_rows(rows):
    if not isinstance(rows, list):
        raise LedgerError("history rows must be a list")
    seen = set()
    required = {"migrationId", "path", "checksum", "targetCommit", "appliedAt", "status"}
    for row in rows:
        if not isinstance(row, dict) or set(row) != required:
            raise LedgerError("history row must contain exactly the required metadata fields")
        migration_id = row["migrationId"]
        if migration_id in seen:
            raise LedgerError("duplicate history migration id")
        seen.add(migration_id)
        if not MIGRATION_ID.fullmatch(migration_id or ""):
            raise LedgerError("invalid history migration id")
        if _managed_migration_id(row["path"] or "") != migration_id:
            raise LedgerError("history path and migration id must be canonical")
        if not CHECKSUM.fullmatch(row["checksum"] or ""):
            raise LedgerError("invalid history checksum")
        if not FULL_SHA.fullmatch(row["targetCommit"] or ""):
            raise LedgerError("invalid history target commit")
        if row["status"] not in HISTORY_STATUSES:
            raise LedgerError("invalid history status")
        if row["status"] in ("APPLIED", "RECONCILED") and not row["appliedAt"]:
            raise LedgerError("applied history rows require appliedAt")


def validate_history_snapshot_source(snapshot):
    """Reject self-asserted provenance; this validates workflow metadata, not DB effects."""
    if not isinstance(snapshot, dict):
        raise LedgerError("history snapshot must be an object")
    if snapshot.get("schemaVersion") != 1:
        raise LedgerError("history snapshot schemaVersion must be 1")
    source = snapshot.get("source")
    bootstrap = snapshot.get("bootstrap")
    if not isinstance(bootstrap, dict):
        raise LedgerError("history snapshot requires bootstrap metadata")
    if bootstrap.get("status") == "FRESH_EMPTY":
        if source != "LOCAL_FIXTURE_ONLY":
            raise LedgerError("FRESH_EMPTY is restricted to explicit local fixtures")
    elif source != "CHENGYIN_DB_MIGRATE_READ_ONLY_EXPORT":
        raise LedgerError("reconciled history requires chengyin-db-migrate read-only export provenance")
    return source


def plan_release(manifest, history_rows, bootstrap=None, baseline_ids=None):
    """Compare immutable files to a read-only history snapshot; never apply SQL."""
    _validate_history_rows(history_rows)
    migrations = manifest.get("migrations")
    if not isinstance(migrations, list):
        raise LedgerError("manifest migrations must be a list")
    by_history = {row["migrationId"]: row for row in history_rows}
    by_manifest = {item["migrationId"]: item for item in migrations}
    result = []

    bootstrap_status = "RECONCILIATION_REQUIRED"
    if bootstrap is not None:
        if not isinstance(bootstrap, dict):
            raise LedgerError("bootstrap metadata must be an object")
        bootstrap_status = bootstrap.get("status", "RECONCILIATION_REQUIRED")
    if bootstrap_status not in {"FRESH_EMPTY", "RECONCILED", "RECONCILIATION_REQUIRED"}:
        raise LedgerError("invalid bootstrap status")
    if bootstrap_status == "FRESH_EMPTY" and history_rows:
        raise LedgerError("FRESH_EMPTY bootstrap cannot contain history rows")
    if bootstrap_status == "RECONCILED" and baseline_ids is None:
        raise LedgerError("RECONCILED bootstrap requires baseline migration ids")
    baseline_ids = set(baseline_ids or ())

    for migration in migrations:
        migration_id = migration["migrationId"]
        row = by_history.get(migration_id)
        state = "PENDING"
        reason = "no history row"
        if migration["path"] == "chengyinhub-admin/sql/seed_preset_demo.sql" and not row:
            state = "EXCLUDED_TEST_FIXTURE"
            reason = "demo seed is retained for historical inventory only; never install in production"
        elif bootstrap_status == "RECONCILIATION_REQUIRED":
            state = "BLOCKED_BOOTSTRAP"
            reason = "existing database has no observed reconciliation baseline"
        elif bootstrap_status == "RECONCILED" and migration_id in baseline_ids and not row:
            state = "BLOCKED_BOOTSTRAP"
            reason = "baseline migration lacks an observed reconciliation history row"
        elif not row and migration_id in POST_DEPLOY_MIGRATIONS:
            state = "DEFERRED_POST_DEPLOY"
            reason = "register only after candidate jar SHA, task bean and health are independently verified"
        elif row:
            if row["path"] != migration["path"] or row["checksum"] != migration["sha256"]:
                state = "BLOCKED_DRIFT"
                reason = "applied path or checksum differs from the immutable file"
            elif row["status"] in ("APPLYING", "UNKNOWN"):
                state = "BLOCKED_UNKNOWN"
                reason = "an interrupted attempt requires read-back and manual reconciliation"
            else:
                state = "APPLIED"
                reason = "same path and checksum; idempotent skip"
        result.append({
            "migrationId": migration_id,
            "path": migration["path"],
            "order": migration["order"],
            "dependsOn": migration.get("dependsOn", []),
            "state": state,
            "reason": reason,
        })

    result_by_id = {item["migrationId"]: item for item in result}
    for item in result:
        if item["state"] != "APPLIED":
            continue
        unmet = [dep for dep in item["dependsOn"] if result_by_id[dep]["state"] != "APPLIED"]
        if unmet:
            item["state"] = "BLOCKED_DEPENDENCY_ORDER"
            item["reason"] = "history says applied before required dependencies"

    for migration_id, row in sorted(by_history.items()):
        if migration_id not in by_manifest:
            result.append({
                "migrationId": migration_id,
                "path": row["path"],
                "order": None,
                "dependsOn": [],
                "state": "BLOCKED_MISSING",
                "reason": "history row has no corresponding immutable file",
            })
    for migration_id in sorted(baseline_ids.difference(by_manifest).difference(by_history)):
        result.append({
            "migrationId": migration_id,
            "path": "%s/%s%s.sql" % (MANAGED_DIR, MIGRATION_PREFIX, migration_id),
            "order": None,
            "dependsOn": [],
            "state": "BLOCKED_MISSING",
            "reason": "bootstrap baseline migration and history row are both missing",
        })

    counts = defaultdict(int)
    for item in result:
        counts[item["state"]] += 1
    blocked = sum(count for state, count in counts.items() if state.startswith("BLOCKED_"))
    return {
        "schemaVersion": 1,
        "mode": "READ_ONLY_PLAN",
        "bootstrapStatus": bootstrap_status,
        "targetCommit": manifest.get("targetCommit"),
        "canProceed": blocked == 0,
        "summary": {
            "pending": counts["PENDING"],
            "applied": counts["APPLIED"],
            "blocked": blocked,
            "executeCount": counts["PENDING"],
            "deferredPostDeploy": counts["DEFERRED_POST_DEPLOY"],
        },
        "migrations": result,
    }


def _safe_error(error):
    payload = {"error": str(error)}
    if isinstance(error, LedgerError) and error.problems:
        payload["problems"] = error.problems
    return payload


def _write_json(payload, output=None):
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if output:
        path = Path(output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description="read-only migration manifest and ledger planner")
    subparsers = parser.add_subparsers(dest="command", required=True)

    audit = subparsers.add_parser("audit", help="inventory every SQL file; never reads a database")
    audit.add_argument("--root", default=".")
    audit.add_argument("--output")

    manifest = subparsers.add_parser("manifest", help="generate a deterministic immutable manifest")
    manifest.add_argument("--root", default=".")
    manifest.add_argument("--target-commit", required=True)
    manifest.add_argument("--dependencies")
    manifest.add_argument("--output")

    plan = subparsers.add_parser("plan", help="read-only plan from manifest and history snapshot")
    plan.add_argument("--root", default=".")
    plan.add_argument("--manifest", required=True)
    plan.add_argument("--history", required=True)
    plan.add_argument("--output")

    args = parser.parse_args(argv)
    try:
        if args.command == "audit":
            payload = audit_inventory(args.root)
            _write_json(payload, args.output)
            return 2 if payload["problems"] else 0
        if args.command == "manifest":
            payload = build_manifest(
                args.root,
                args.target_commit,
                dependency_path=args.dependencies,
            )
            target_problems = verify_target_commit(args.root, payload)
            if target_problems:
                raise LedgerError("target commit failed verification", target_problems)
            _write_json(payload, args.output)
            return 0
        if args.command == "plan":
            manifest_payload = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
            history_payload = json.loads(Path(args.history).read_text(encoding="utf-8"))
            history_source = validate_history_snapshot_source(history_payload)
            file_problems = verify_manifest_files(args.root, manifest_payload)
            target_problems = verify_target_commit(args.root, manifest_payload)
            verification_problems = file_problems + target_problems
            if verification_problems:
                raise LedgerError("manifest failed file or target verification", verification_problems)
            rows = history_payload.get("rows") if isinstance(history_payload, dict) else None
            bootstrap = history_payload.get("bootstrap") if isinstance(history_payload, dict) else None
            baseline_ids = None
            if isinstance(bootstrap, dict) and bootstrap.get("status") == "RECONCILED":
                baseline_ids = migration_ids_at_commit(args.root, bootstrap.get("targetCommit"))
            payload = plan_release(
                manifest_payload,
                rows,
                bootstrap=bootstrap,
                baseline_ids=baseline_ids,
            )
            payload["historySource"] = history_source
            payload["databaseEffectReadback"] = "REQUIRED_BEFORE_PRODUCTION_APPLY"
            payload["productionApplyAuthorized"] = False
            _write_json(payload, args.output)
            return 0 if payload["canProceed"] else 2
    except (LedgerError, OSError, ValueError, json.JSONDecodeError) as error:
        sys.stderr.write(json.dumps(_safe_error(error), ensure_ascii=False, indent=2) + "\n")
        return 2
    return 2


if __name__ == "__main__":
    sys.exit(main())

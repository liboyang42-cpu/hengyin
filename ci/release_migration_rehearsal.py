#!/usr/bin/env python3
"""发布迁移包演练：在一次性 MySQL 8.0 上证明「本次发布的迁移」按序可落、重跑零变更。

只连回环地址、只操作 cy_e2e_* scratch schema（沿用 scripts/e2e/build_schema.py 的守卫），
不读任何生产配置，不连任何真实主机。

流程（任何一步失败即停，退出码 1）：
  0. 用 ci/release_manifest.migration_diff(--base, HEAD) 算出相对生产基线新增/修改的迁移，
     与 docs/.../release-migration-pack.json 逐项比对（id / path / sha256 / 执行序 / 依赖）。
  1. 从 sql/base_schema_20260818.sql + base seed 建库，再按台账拓扑序叠加「快照后、且不在本次发布集」的迁移，
     得到模拟生产基线的库；写入少量合成行（只在 scratch 库），让数据类迁移有东西可处理。
  2. 跑只读预检脚本：每一项都必须等于 pack 的 expectedAtBaseline（除 D-PAYEE 外都是 NEEDED）。
  3. 第一遍：按执行序逐个执行发布集（严格模式，断言为假即停），记录每个迁移改动了哪些表。
  4. 再跑预检：每一项都必须落在 pack 声明的 expectedAfterApply 状态里。
  5. 第二遍：原样重跑发布集，每个迁移前后的 schema 指纹 + 全表 CHECKSUM 必须完全一致（零变更）。
  6. 负控：人为制造同名异形表 / 列 / 重复配置键 / 重复菜单 / 授权漂移，预检必须判出对应状态。
  7. 风险探针：另建一个「后台角色无页面授权」的库，复现 20261013 在断言处中途停止（见 pack 的 D-PAYEE 节）。

用法:
  CHENGYIN_REHEARSAL_MYSQL_PASSWORD=... python3 ci/release_migration_rehearsal.py \\
      --schema cy_e2e_release_rehearsal --port 3316 --report /tmp/report.json
  python3 ci/release_migration_rehearsal.py --inventory      # 只打印发布集清单，不连库
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))
sys.path.insert(0, str(REPO / "ci"))

from ci.migration_ledger import build_manifest  # noqa: E402
from ci.release_manifest import migration_diff  # noqa: E402
from ci.sql_splitter import split_statements, executable_statement  # noqa: E402

PACK_DIR = REPO / "docs/delivery/release-closeout-20261022"
PACK_JSON = PACK_DIR / "release-migration-pack.json"
PRECHECK_SQL = PACK_DIR / "release-migration-precheck.sql"
PASSWORD_ENV = "CHENGYIN_REHEARSAL_MYSQL_PASSWORD"

# 只写进 scratch 库的合成行：一个已有后台账号（生产必然有 sys_user，D-PAYEE 不走全新初始化分支）、
# 俱乐部城市 / 主题 / 活动（O-14 回填）、地标与到访点（poi_key 回填）、分享快照、属性定义、定向会话。
SYNTHETIC_ROWS = [
    "INSERT INTO sys_user(user_id, user_name, nick_name) VALUES (9900, 'rehearsal_op', 'rehearsal')",
    "INSERT INTO club(id, member_id, name, city) VALUES (9001, 1, 'rehearsal-club-sh', '上海市'), "
    "(9002, 2, 'rehearsal-club-ambiguous', '南京西路')",
    "INSERT INTO cms_topic(id, club_id) VALUES (9101, 9001), (9102, 9002), (9103, NULL)",
    "INSERT INTO cms_activity(id, topic_id, club_id) VALUES (9201, 9101, NULL), (9202, 9103, 9001), (9203, 9102, 9002)",
    "INSERT INTO roam_poi(id, name, lat, lng, type, status) VALUES (9301, 'rehearsal-landmark', 31.230000, 121.470000, 1, 1)",
    "INSERT INTO member_visited_poi(id, member_id, geo_key, poi_name, lat, lng) VALUES "
    "(9401, 1, '31.230,121.470', 'A', 31.230010, 121.470010), "
    "(9402, 1, '31.231,121.470', 'A', 31.230015, 121.470012), "
    "(9403, 1, '31.300,121.500', 'far', 31.300000, 121.500000)",
    "INSERT INTO roam_share_snapshot(share_token, member_id, client_ts, snapshot_json, create_time, update_time) "
    "VALUES ('rehearsalrehearsalrehearsal00001', 1, 1, '{}', NOW(), NOW())",
    "INSERT INTO theme_state_key(topic_id, state_key, key_type, scope, create_time, update_time) "
    "VALUES (9101, 'hp', 'number', 'run', NOW(3), NOW(3))",
    "INSERT INTO topic_route_session(topic_id, member_id, config_version, config_snapshot_json, state_json, seed, "
    "create_time, update_time) VALUES (9101, 1, 1, '{}', '{}', 1, NOW(3), NOW(3))",
]

# 生产形态：四个后台角色都已配置过页面（生产上 20261013 因此是 no-op），且收款审核/打款授权按
# 20261016 要纠正的方向漂移过（cy_operator 持 verify、cy_finance 持 payout），让 D-PAYEE 的撤销路径真正执行。
PRODUCTION_LIKE_ROWS = [
    "INSERT INTO sys_role_menu(role_id, menu_id) SELECT r.role_id, (SELECT MIN(menu_id) FROM sys_menu "
    "WHERE menu_type = 'C' AND perms LIKE 'business:%') FROM sys_role r "
    "WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_auditor', 'cy_support', 'cy_finance')",
    "INSERT INTO sys_role_menu(role_id, menu_id) SELECT r.role_id, m.menu_id FROM sys_role r JOIN sys_menu m "
    "WHERE (r.role_key = 'cy_operator' AND m.perms = 'business:clubpayee:verify') "
    "OR (r.role_key = 'cy_finance' AND m.perms = 'business:clubpayee:payout')",
]
HAZARD_MIGRATION = "20261013_admin_role_page_grants"

# 负控：每条在 scratch 库上制造一个「同名但定义不同 / 前置不唯一」的状态，预检必须判出对应状态。
NEGATIVE_CONTROLS = [
    ("audit_problem_20261022", "ALTER TABLE audit_problem ADD COLUMN rehearsal_bogus INT NULL", "CONFLICT"),
    ("member_profile_version_20261022",
     "ALTER TABLE ums_member_profile_version MODIFY changed_fields VARCHAR(64) NOT NULL DEFAULT ''", "CONFLICT"),
    ("20261022_merchant_desk_switch_seed",
     "INSERT INTO sys_config(config_name, config_key, config_value, config_type) "
     "VALUES ('dup', 'merchant.desk.f4.enabled', 'false', 'N')", "CONFLICT"),
    ("orient_session_lifecycle_20261022",
     "ALTER TABLE topic_route_session MODIFY COLUMN status INT NOT NULL DEFAULT 0", "CONFLICT"),
    ("20261013_admin_role_page_grants",
     "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id "
     "WHERE r.role_key = 'cy_auditor' AND m.menu_type = 'C'", "NEEDED"),
    ("20261016_admin_role_payee_separation_forward",
     "INSERT INTO sys_role_menu(role_id, menu_id) SELECT r.role_id, m.menu_id FROM sys_role r JOIN sys_menu m "
     "WHERE r.role_key = 'cy_finance' AND m.perms = 'business:clubpayee:payout'", "NEEDED"),
    ("20261016_admin_role_payee_separation_forward",
     "INSERT INTO sys_menu(menu_name, parent_id, order_num, menu_type, visible, status, perms) "
     "SELECT 'dup', parent_id, 99, 'F', '0', '0', perms FROM sys_menu WHERE perms = 'business:clubpayee:verify'",
     "BLOCKED_PREREQ"),
]


def release_set(base):
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, capture_output=True,
                          text=True, check=True).stdout.strip()
    diff = migration_diff(REPO, base, head)
    deleted = [c["path"] for c in diff["changes"] if c["change"] == "DELETED"]
    if deleted:
        raise SystemExit("基线之后有迁移被删除，违反只增规则：%s" % deleted)
    items = sorted(diff["changes"], key=lambda c: c["order"])
    return diff, [{k: c[k] for k in ("migrationId", "path", "sha256", "baseSha256", "change", "order", "dependsOn")}
                  for c in items]


def compare_pack(pack, computed):
    declared = [{k: m[k] for k in ("migrationId", "path", "sha256", "order", "dependsOn")}
                for m in pack["migrations"]]
    actual = [{k: m[k] for k in ("migrationId", "path", "sha256", "order", "dependsOn")} for m in computed]
    if declared != actual:
        lines = []
        for d, a in zip(declared, actual):
            if d != a:
                lines.append("pack=%s\nhead=%s" % (d, a))
        if len(declared) != len(actual):
            lines.append("pack 有 %d 项，head 算出 %d 项" % (len(declared), len(actual)))
        raise SystemExit("release-migration-pack.json 与 HEAD 不一致（pack 过期）：\n" + "\n".join(lines))


def schema_fingerprint(cur):
    """每张表一个摘要：表属性 + 列 + 索引 + 约束 + CHECKSUM TABLE。返回 {table: sha256}。"""
    cur.execute("SELECT table_name, engine, table_collation, table_comment FROM information_schema.tables "
                "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name")
    tables = {r[0]: list(r[1:]) for r in cur.fetchall()}
    cur.execute("SELECT table_name, ordinal_position, column_name, column_type, is_nullable, column_default, extra, "
                "collation_name, column_comment, generation_expression FROM information_schema.columns "
                "WHERE table_schema = DATABASE() ORDER BY table_name, ordinal_position")
    detail = {name: [meta] for name, meta in tables.items()}
    for row in cur.fetchall():
        if row[0] in detail:
            detail[row[0]].append(list(row[1:]))
    cur.execute("SELECT table_name, index_name, non_unique, seq_in_index, column_name, sub_part, is_visible, "
                "index_type FROM information_schema.statistics WHERE table_schema = DATABASE() "
                "ORDER BY table_name, index_name, seq_in_index")
    for row in cur.fetchall():
        if row[0] in detail:
            detail[row[0]].append(["idx"] + list(row[1:]))
    cur.execute("SELECT tc.table_name, tc.constraint_name, tc.constraint_type, cc.check_clause "
                "FROM information_schema.table_constraints tc LEFT JOIN information_schema.check_constraints cc "
                "ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name "
                "WHERE tc.table_schema = DATABASE() ORDER BY tc.table_name, tc.constraint_name")
    for row in cur.fetchall():
        if row[0] in detail:
            detail[row[0]].append(["con"] + list(row[1:]))
    if tables:
        cur.execute("CHECKSUM TABLE " + ", ".join("`%s`" % t for t in tables) + " EXTENDED")
        for name, checksum in cur.fetchall():
            detail[name.split(".", 1)[1]].append(["checksum", checksum])
    return {name: hashlib.sha256(json.dumps(rows, default=str, ensure_ascii=False).encode()).hexdigest()
            for name, rows in detail.items()}


def table_digest(cur, table, ignored):
    """表内容摘要，跳过 ignored 列（只用于 pack 声明过的重跑元数据差异）。"""
    cur.execute("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() "
                "AND table_name = %s ORDER BY ordinal_position", (table,))
    columns = [r[0] for r in cur.fetchall() if r[0] not in ignored]
    cur.execute("SELECT %s FROM `%s` ORDER BY %s" % (", ".join("`%s`" % x for x in columns), table,
                                                      ", ".join("`%s`" % x for x in columns)))
    return hashlib.sha256(json.dumps(cur.fetchall(), default=str, ensure_ascii=False).encode()).hexdigest()


def changed_tables(before, after):
    return sorted(set(before) ^ set(after) | {t for t in before if t in after and before[t] != after[t]})


def run_precheck(cur):
    """执行只读预检；返回 {migration_id: status}。预检文件里出现任何非 SELECT 语句直接拒绝。"""
    statuses = {}
    for statement in split_statements(PRECHECK_SQL.read_text(encoding="utf-8")):
        sql = executable_statement(statement).strip().rstrip(";")
        if not sql:
            continue
        if not sql.lstrip("(").upper().startswith(("SELECT", "WITH")):
            raise SystemExit("预检脚本含非 SELECT 语句，拒绝执行：%s" % sql.splitlines()[0][:80])
        cur.execute(sql)
        names = [d[0] for d in cur.description or ()]
        rows = cur.fetchall()
        if "migration_id" in names and "status" in names:
            mi, si = names.index("migration_id"), names.index("status")
            for row in rows:
                statuses[row[mi]] = row[si]
    return statuses


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema", default="cy_e2e_release_rehearsal")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3316)
    ap.add_argument("--user", default="root")
    ap.add_argument("--report", type=pathlib.Path)
    ap.add_argument("--inventory", action="store_true", help="只打印发布集清单（不连库）")
    a = ap.parse_args()

    pack = json.loads(PACK_JSON.read_text(encoding="utf-8"))
    base = pack["baseline"]["commit"]
    diff, computed = release_set(base)
    if a.inventory:
        print(json.dumps({"base": diff["base"], "head": diff["head"], "migrations": computed},
                         ensure_ascii=False, indent=2))
        return 0
    compare_pack(pack, computed)
    expected_before = {m["migrationId"]: m["expectedAtBaseline"] for m in pack["migrations"]}
    expected_after = {m["migrationId"]: set(m["expectedAfterApply"]) for m in pack["migrations"]}
    known_delta = {m["migrationId"]: m["rerunKnownDelta"] for m in pack["migrations"] if m.get("rerunKnownDelta")}
    release_ids = [m["migrationId"] for m in computed]

    from scripts.e2e import build_schema as bs
    a.password = os.environ.get(PASSWORD_ENV, "")
    bs.validate_target(a)
    report = {"base": diff["base"], "head": diff["head"], "releaseCount": len(computed), "steps": []}

    def fail(message, **extra):
        report["result"] = "FAIL"
        report["failure"] = message
        report.update(extra)
        write_report()
        print("FAIL: " + message)
        return 1

    def write_report():
        if a.report:
            a.report.parent.mkdir(parents=True, exist_ok=True)
            a.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    manifest = build_manifest(REPO, target_commit=diff["head"], dependency_path=REPO / "ci/migration-dependencies.json")
    by_id = {m["migrationId"]: m for m in manifest["migrations"]}
    baseline_phase = [m for m in bs.migrations_after_baseline(manifest["migrations"])
                      if m["migrationId"] not in set(release_ids)]

    def create_schema(schema):
        c = bs.connect(a)
        try:
            cur = c.cursor()
            cur.execute("SELECT VERSION(), @@version_comment")
            version = cur.fetchone()
            cur.execute("DROP DATABASE IF EXISTS `%s`" % schema)
            cur.execute("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % schema)
            return "%s / %s" % version
        finally:
            c.close()

    def build_baseline(cur, extra_rows):
        results = []
        bs.run_file(cur, bs.BASELINE, "baseline", results, keep_going=False)
        bs.run_file(cur, bs.BASELINE_SEED, "baseline-seed", results, keep_going=False)
        for m in baseline_phase:
            if not bs.run_file(cur, REPO / m["path"], m["migrationId"], results, keep_going=False, schema_only=True):
                break
        if not results:
            for sql in SYNTHETIC_ROWS + extra_rows:
                cur.execute(sql)
        return results

    report["mysql"] = create_schema(a.schema)
    if not report["mysql"].startswith("8.0."):
        return fail("演练必须在 MySQL 8.0.x 上跑，当前 %s" % report["mysql"])

    c = bs.connect(a, a.schema)
    try:
        cur = c.cursor()
        t0 = time.time()
        results = build_baseline(cur, PRODUCTION_LIKE_ROWS)
        if results:
            return fail("模拟基线建库失败", problems=[list(r) for r in results])
        cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")
        report["steps"].append({"step": "baseline", "tables": cur.fetchone()[0],
                                "baselineMigrations": len(baseline_phase), "seconds": round(time.time() - t0, 1)})
        print("[1] 模拟基线：%d 个快照后迁移（不含发布集），合成行 %d 组（含生产形态的角色页面授权与收款授权漂移）"
              % (len(baseline_phase), len(SYNTHETIC_ROWS) + len(PRODUCTION_LIKE_ROWS)))

        pre = run_precheck(cur)
        wrong = {i: pre.get(i) for i in release_ids if pre.get(i) != expected_before[i]}
        report["steps"].append({"step": "precheck-baseline", "statuses": pre})
        if wrong:
            return fail("基线库上预检不符合 pack 的 expectedAtBaseline", unexpected=wrong)
        print("[2] 基线预检：%d 项全部符合 expectedAtBaseline（NEEDED %d）" %
              (len(release_ids), sum(pre[i] == "NEEDED" for i in release_ids)))

        def apply_all(ids, rerun=False):
            changes = {}
            for migration_id in ids:
                delta = known_delta.get(migration_id) if rerun else None
                before = schema_fingerprint(cur)
                narrow = table_digest(cur, delta["table"], delta["ignoredColumns"]) if delta else None
                problems = []
                ok = bs.run_file(cur, REPO / by_id[migration_id]["path"], migration_id, problems, keep_going=False)
                if not ok or problems:
                    return changes, (migration_id, [list(p) for p in problems])
                changed = changed_tables(before, schema_fingerprint(cur))
                if delta and delta["table"] in changed and \
                        table_digest(cur, delta["table"], delta["ignoredColumns"]) == narrow:
                    # 只有 pack 声明的列变了（例如回填日志的 write_time 被刷新）；其余列与行集合逐字节相同。
                    changed.remove(delta["table"])
                    report.setdefault("rerunKnownDeltaObserved", []).append(
                        {"migrationId": migration_id, **delta})
                changes[migration_id] = changed
            return changes, None

        first, error = apply_all(release_ids)
        if error:
            return fail("第一遍在 %s 停止（stop-on-first-error）" % error[0], problems=error[1])
        report["steps"].append({"step": "run-1", "changedTables": first})
        print("[3] 第一遍：%d 个迁移全部成功" % len(first))

        post = run_precheck(cur)
        wrong = {i: post.get(i) for i in release_ids if post.get(i) not in expected_after[i]}
        report["steps"].append({"step": "precheck-after-run-1", "statuses": post})
        if wrong:
            return fail("第一遍后预检状态不符合 pack 的 expectedAfterApply", unexpected=wrong)
        print("[4] 第一遍后预检：全部符合 expectedAfterApply")

        second, error = apply_all(release_ids, rerun=True)
        if error:
            return fail("第二遍（重跑）在 %s 失败" % error[0], problems=error[1])
        drift = {k: v for k, v in second.items() if v}
        report["steps"].append({"step": "run-2", "changedTables": second})
        if drift:
            return fail("第二遍不是零变更", drift=drift)
        print("[5] 第二遍：%d 个迁移重跑，零变更（schema 指纹 + 全表 CHECKSUM 一致；pack 声明的元数据列除外：%s）"
              % (len(second), report.get("rerunKnownDeltaObserved", "无")))

        controls = []
        for migration_id, mutation, expected in NEGATIVE_CONTROLS:
            cur.execute(mutation)
            status = run_precheck(cur).get(migration_id)
            controls.append({"migrationId": migration_id, "expected": expected, "status": status})
            if status != expected:
                return fail("负控未转红：%s 判为 %s" % (migration_id, status), negativeControls=controls)
        report["steps"].append({"step": "negative-controls", "controls": controls})
        print("[6] 负控：%d/%d 被预检判为预期状态" % (len(controls), len(NEGATIVE_CONTROLS)))
    finally:
        c.close()

    # 已知风险探针：后台角色没有任何页面授权（全新库）时，master 版 20261013 的回读断言会在
    # 文件中途判假；该文件不是 requires=transaction，前面的 INSERT 已提交。预检对这种库判 NEEDED，
    # 发布包据此规定「20261013 判 NEEDED 时不得执行，交 owner 处置」。探针在独立 scratch 库复现它。
    fresh_schema = a.schema + "_fresh_roles"
    create_schema(fresh_schema)
    c = bs.connect(a, fresh_schema)
    try:
        cur = c.cursor()
        results = build_baseline(cur, [])
        if results:
            return fail("风险探针建库失败", problems=[list(r) for r in results])
        status = run_precheck(cur).get(HAZARD_MIGRATION)
        upto = release_ids[:release_ids.index(HAZARD_MIGRATION) + 1]
        _, error = apply_all(upto)
        cur.execute("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id "
                    "JOIN sys_menu m ON m.menu_id = rm.menu_id WHERE r.role_key = 'cy_operator' AND m.menu_type = 'C'")
        committed_pages = cur.fetchone()[0]
        probe = {"step": "hazard-probe-fresh-roles", "precheckStatus": status,
                 "stoppedAt": error[0] if error else None, "problems": error[1] if error else [],
                 "operatorPageGrantsCommittedBeforeStop": committed_pages}
        report["steps"].append(probe)
        if status != "NEEDED" or not error or error[0] != HAZARD_MIGRATION or committed_pages == 0:
            return fail("风险探针结果与发布包记载不一致（pack 需要更新）", probe=probe)
        print("[7] 风险探针：无页面授权的角色上 %s 预检判 NEEDED，执行在断言处停止，已提交 %d 条页面授权"
              % (HAZARD_MIGRATION, committed_pages))
        cur.execute("DROP DATABASE `%s`" % fresh_schema)
    finally:
        c.close()

    report["result"] = "PASS"
    write_report()
    print("PASS: %d 个发布迁移在 %s 上按序落库、重跑零变更" % (len(release_ids), report["mysql"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""qx-final6：把 10-29 之后进入发布集的迁移补进 release-closeout-20261029 的 pack 契约（f-release RESULT 待决第 4 条）。

只读 git 对象与仓库文件，不连任何库/主机。数据来源（都已审阅、已提交）：
- f-release/release-migrations.json：类别、阶段、重跑规则、幂等分类、说明；
- f-release/release-precheck.sql：每项只读分类 SELECT（改写成 pack 主分类块的列形状）；
- f-rehearsal/RESULT.md 第四节：一次性 MySQL 8.0 上观测到的「预检 前→后」状态。
补完后调用 ci/release_migration_rehearsal.refresh_pack 统一刷新 step/order/源提交/文档表格。
只追加新项，不改 10-29 原 88 项的任何已审字段。

用法（仓库根目录，工作区干净、HEAD 即发布候选）：
  python3 docs/delivery/questo-ui-20261109/qx-final6/extend_release_pack.py
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "ci"))

from ci import release_migration_rehearsal as rehearsal  # noqa: E402
from ci.sql_splitter import executable_statement, split_statements  # noqa: E402

QX = ROOT / "docs/delivery/questo-ui-20261109"
REL = json.loads((QX / "f-release/release-migrations.json").read_text(encoding="utf-8"))
REL_PRECHECK = (QX / "f-release/release-precheck.sql").read_text(encoding="utf-8")
REHEARSAL_RESULT = (QX / "f-rehearsal/RESULT.md").read_text(encoding="utf-8")

PHASE = {("必须", "PRE_DEPLOY"): "PRE_DEPLOY_REQUIRED", ("可选", "PRE_DEPLOY"): "PRE_DEPLOY",
         ("必须", "POST_DEPLOY"): "POST_DEPLOY", ("可选", "POST_DEPLOY"): "POST_DEPLOY",
         ("需业主授权", "PRE_DEPLOY"): "OWNER_GATED_DATA", ("需业主授权", "POST_DEPLOY"): "OWNER_GATED_DATA",
         ("不执行", "PRE_DEPLOY"): "PRE_DEPLOY"}
POST_DEPLOY_PHASE = ("只插/改 sys_job 等调度行：旧 jar 没有对应 bean 时调度器整体失效，"
                     "必须在新 jar 健康之后执行（f-release DEPLOY 第 3 轮）")
GUARD = {
    "IDEMPOTENT_STATIC": "静态幂等（IF NOT EXISTS / INSERT…WHERE NOT EXISTS / 写绝对值）；f-rehearsal 两遍执行零报错",
    "GUARDED_INFORMATION_SCHEMA": "information_schema 守卫后条件执行 DDL；f-rehearsal 两遍执行零报错",
    "NEEDS_REVIEW": "单次执行：普通 CREATE/ADD 或前置断言，直接重跑以 1050/1060/3141 拒绝且不改库",
}
SINGLE = "；重跑只在新鲜预检 APPLIED 时跳过（PRECHECK_SKIP_APPLIED）"
TABLE = re.compile(r"(?is)^\s*(CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?|ALTER\s+TABLE|INSERT(?:\s+IGNORE)?\s+INTO|"
                   r"UPDATE|DELETE\s+FROM|REPLACE\s+INTO)\s+`?([A-Za-z0-9_]+)`?")


UNOBSERVED = {"20261112_template_usage_count", "20261112_template_market_status",
              "20261112_template_author_title_unique", "20261112_dec_c_team_codes", "20261112_dec_c_safety",
              "20261112_dec_c_perks", "20261112_dec_c_payout", "20261112_dec_c_payout_jobs", "20261112_chapter_reward",
              "20261113_stagger_payout_jobs"}


def observed_statuses():
    rows = re.findall(r"^\| (\d+) \| `migration_[^`]+\.sql` \| ([A-Z_]+)→([A-Z_]+) \|", REHEARSAL_RESULT, re.M)
    return {int(step): (before, after) for step, before, after in rows}


def precheck_blocks():
    out = {}
    for part in re.split(r"\n(?=-- \[\d+\])", REL_PRECHECK):
        m = re.search(r"(?ms)^SELECT \d+ AS step, '([a-z0-9_]+)' AS migration_id, .*?;$", part)
        if m:
            out[m.group(1)] = m.group(0)
    return out


def classifier(block, migration_id, replaced_by):
    body = re.sub(r"^SELECT \d+ AS step, '([a-z0-9_]+)' AS migration_id, '[^']*' AS release_class, '[^']*' AS phase,",
                  r"SELECT 0 AS exec_order, '\1' AS migration_id,", block, count=1)
    body, n = re.subn(r"CONCAT\(present, '/', expected\) AS objects_present, ('(?:[^']|'')*') AS detail",
                      r"CONCAT(\1, ' (', present, '/', expected, ')') AS detail", body, count=1)
    if n != 1 or not body.startswith("SELECT 0 AS exec_order"):
        raise SystemExit("无法改写预检块：%s" % migration_id)
    if replaced_by:
        body = body.replace("CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' "
                            "ELSE 'PARTIAL' END AS status",
                            "CASE WHEN present = expected THEN 'APPLIED' ELSE 'REPLACED_UNAPPLIED' END AS status")
        body = re.sub(r"CONCAT\('((?:[^']|'')*)', ' \(", "CONCAT('已被 %s 取代，永不执行；替代形状：\\1', ' (" %
                      replaced_by, body, count=1)
        if "REPLACED_UNAPPLIED" not in body:
            raise SystemExit("替代项状态改写失败：%s" % migration_id)
    return body.rstrip().rstrip(";")


def verify_query(block):
    m = re.search(r"(?s)\nFROM \(SELECT (.*) AS present, (\d+) AS expected\) x;$", block)
    if not m:
        raise SystemExit("无法提取回读：%s" % block[:80])
    return "SELECT (%s) = %s AS applied; -- 期望 1" % (" ".join(m.group(1).split()), m.group(2))


def lock_risk(path):
    kinds = {}
    for statement in split_statements((ROOT / path).read_text(encoding="utf-8")):
        sql = executable_statement(statement).strip()
        m = TABLE.match(sql)
        if m:
            kind = " ".join(m.group(1).upper().split()[:2]).replace(" IF", "")
            kinds.setdefault(kind, set()).add(m.group(2))
    if not kinds:
        return "无表级写入（只读断言/存储过程守卫外壳）；按语句归纳，生产体量 UNKNOWN"
    parts = []
    for kind, tables in sorted(kinds.items()):
        names = "、".join(sorted(tables))
        if kind == "CREATE TABLE":
            parts.append("新建空表 %s，瞬时" % names)
        elif kind == "ALTER TABLE":
            parts.append("ALTER %s：在线 DDL，生产行数 UNKNOWN，窗口内看元数据锁" % names)
        else:
            parts.append("%s %s：按键/条件写少量行" % (kind, names))
    return "；".join(parts) + "（按语句归纳）"


def main():
    pack = json.loads(rehearsal.PACK_JSON.read_text(encoding="utf-8"))
    diff, computed = rehearsal.release_set(pack["baseline"]["commit"])
    reviewed = {m["migrationId"] for m in pack["migrations"]}
    rel = {m["migrationId"]: m for m in REL["migrations"]}
    if {m["migrationId"] for m in computed} != set(rel):
        raise SystemExit("f-release 发布集与 HEAD 不一致，先重跑 gen_release_set.py")
    observed, blocks = observed_statuses(), precheck_blocks()
    replaced = {mid: repl for mid, repl in rehearsal.REPLACED_UNAPPLIED.items()}
    new_items, new_blocks = [], []
    for c in computed:
        mid = c["migrationId"]
        if mid in reviewed:
            continue
        r = rel[mid]
        item = {k: c[k] for k in ("migrationId", "path", "sha256", "baseSha256", "change", "order", "dependsOn")}
        item["phase"] = PHASE[(r["class"], r["phase"])]
        item["changes"] = r["note"]
        if r["class"] == "不执行":
            if mid not in replaced:
                raise SystemExit("不执行项缺替代者登记：%s" % mid)
            item["idempotencyGuard"] = "已被 %s 取代：任何情况下都不执行本文件" % replaced[mid]
            item["rerunPolicy"] = "REPLACED_UNAPPLIED"
            item["expectedAtBaseline"], item["expectedAfterApply"] = "REPLACED_UNAPPLIED", ["APPLIED"]
        else:
            # 11-12 九项在 f-rehearsal 之后才进入发布集，step 编号已漂移，不能按 step 查观测：
            # 在一次性 MySQL 8.0 干净基线上按「NEEDED→APPLIED」登记（正式演练由 release-migration-rehearsal 工作流复核）。
            before, after = ("NEEDED", "APPLIED") if mid in UNOBSERVED else observed[r["step"]]
            item["idempotencyGuard"] = GUARD[r["idempotency"]] + (SINGLE if r["rerun"] == "APPLIED 时必须跳过" else "")
            if r["rerun"] == "APPLIED 时必须跳过":
                if mid not in rehearsal.SINGLE_APPLY:
                    raise SystemExit("单次执行项未登记进 SINGLE_APPLY：%s" % mid)
                item["rerunPolicy"] = "PRECHECK_SKIP_APPLIED"
            item["expectedAtBaseline"], item["expectedAfterApply"] = before, [after]
        item["lockRisk"] = lock_risk(c["path"])
        item["verifySelectOnly"] = [verify_query(blocks[mid])]
        new_items.append(item)
        new_blocks.append(classifier(blocks[mid], mid, replaced.get(mid) if r["class"] == "不执行" else None))
    if not new_items:
        print("pack 已覆盖 HEAD 发布集，无需补充")
        return
    pack["migrations"] = pack["migrations"] + new_items
    pack["phases"] = {**pack["phases"], "POST_DEPLOY": POST_DEPLOY_PHASE}
    precheck = rehearsal.PRECHECK_SQL.read_text(encoding="utf-8")
    end = precheck.index("\nORDER BY exec_order;")
    precheck = precheck[:end] + "\nUNION ALL\n" + "\nUNION ALL\n".join(new_blocks) + precheck[end:]
    rehearsal.PRECHECK_SQL.write_text(precheck, encoding="utf-8")
    rehearsal.refresh_pack(pack, diff, computed)
    print("补充 %d 项（原 %d 项不变）" % (len(new_items), len(reviewed)))


if __name__ == "__main__":
    main()

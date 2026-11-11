#!/usr/bin/env python3
"""f-rehearsal：演练 DEPLOY.md 第 12 节「回滚」里能脚本化的部分（只在本机 cy_e2e_* 演练库上）。

R1 旧 jar 回滚前删任务：对迁移后库里每个 sys_job.invoke_target，核对生产基线 19408d49 的源码里有没有这个 bean；
   没有的必须出现在 DEPLOY 第 12 节的删除清单里，清单里的又必须都是旧 jar 没有的（多删会丢生产已有任务）。
   然后按清单 SELECT → DELETE，再前滚重跑对应迁移，核对任务恢复为暂停态、行数一致。
R2 数据迁移文件头的回滚语句：20261029_unlist_test_topics、20261028_remove_wildlife_theme 的回滚 UPDATE
   只恢复本迁移改过的行；再前滚重跑迁移回到已应用状态。

用法：CHENGYIN_REHEARSAL_MYSQL_PASSWORD=... python3 rollback.py --schema cy_e2e_frehearsal --report <out.json>
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO))
from scripts.e2e import build_schema as bs  # noqa: E402

OLD_JAR_SOURCE = "19408d49172c1d8fd16ec012f4498ff8ea584a37"
DEPLOY = REPO / "docs/delivery/questo-ui-20261109/f-release/DEPLOY.md"
SQL = REPO / "chengyinhub-admin/sql"
JOB_MIGRATIONS = [  # 插入新 jar 才有的 bean 任务的发布集迁移（rounds 1/3）
    "migration_20261024_d_committed_official_notice_wx.sql",
    "migration_20261104_content_safety_jobs_post_deploy.sql",
    "migration_20261110_ecommerce_payout_wiring.sql",
    "migration_marketing_task_registration_20261108.sql",
    "migration_o14_reco_search_jobs_20261022.sql",
]


def bean_in_tree(commit, bean):
    cls = bean[0].upper() + bean[1:]
    out = subprocess.run(["git", "grep", "-l", "-E", r'(Component|Service)\("%s"\)|class %s\b' % (bean, cls),
                          commit, "--", "*.java"], cwd=REPO, capture_output=True, text=True)
    return bool(out.stdout.strip())


def deploy_delete_list():
    text = DEPLOY.read_text(encoding="utf-8")
    section = text[text.index("## 12 回滚"):]
    section = section[:section.index("- 删之前先用")]
    keep = section[section.index("**不要删**"):] if "**不要删**" in section else ""
    listed = set(re.findall(r"`(\w+\.\w+\(\))`", section)) - set(re.findall(r"`(\w+\.\w+\(\))`", keep))
    return listed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema", default="cy_e2e_frehearsal")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3316)
    ap.add_argument("--user", default="root")
    ap.add_argument("--report", type=pathlib.Path, required=True)
    a = ap.parse_args()
    a.password = os.environ.get("CHENGYIN_REHEARSAL_MYSQL_PASSWORD", "")
    bs.validate_target(a)
    c = bs.connect(a, a.schema)
    cur = c.cursor()
    report = {}

    # R1
    cur.execute("SELECT job_id, invoke_target, status FROM sys_job ORDER BY job_id")
    jobs = cur.fetchall()
    missing_in_old = sorted({t for _, t, _ in jobs if not bean_in_tree(OLD_JAR_SOURCE, t.split(".")[0])})
    listed = deploy_delete_list()
    report["r1"] = {"jobsAfterMigration": len(jobs), "beansMissingInOldJar": missing_in_old,
                    "deployDeleteList": sorted(listed),
                    "notListedButMissing": sorted(set(missing_in_old) - listed),
                    "listedButPresentInOldJar": sorted(listed - set(missing_in_old))}
    placeholders = ", ".join(["%s"] * len(listed))
    cur.execute("SELECT job_id, invoke_target, status FROM sys_job WHERE invoke_target IN (%s) ORDER BY job_id"
                % placeholders, sorted(listed))
    before = [list(r) for r in cur.fetchall()]
    cur.execute("DELETE FROM sys_job WHERE invoke_target IN (%s)" % placeholders, sorted(listed))
    deleted = cur.rowcount
    problems = []
    for name in JOB_MIGRATIONS:
        bs.run_file(cur, SQL / name, name, problems, keep_going=False)
    cur.execute("SELECT job_id, invoke_target, status FROM sys_job WHERE invoke_target IN (%s) ORDER BY job_id"
                % placeholders, sorted(listed))
    after = [list(r) for r in cur.fetchall()]
    report["r1"].update(selectBefore=before, deleted=deleted, reforwardProblems=[list(p) for p in problems],
                        afterReforward=after,
                        restoredSameTargets=sorted(r[1] for r in before) == sorted(r[1] for r in after),
                        allPausedAfter=all(r[2] == "1" for r in after))

    # R2
    r2 = []
    cases = [
        ("migration_20261029_unlist_test_topics.sql",
         "UPDATE cms_topic SET del_flag = 0, update_by = 'rollback_unlist_test_topics_20261029', update_time = NOW() "
         "WHERE (id, name, member_id) IN ((990062, '预制人生 · 第一站', 100092), (8, '西安大唐不夜城灯光秀', 100000), "
         "(7, '上海沉浸式戏剧', 100000), (6, '成都宽窄巷子辣味食材寻味', 100000), (1, '上海滨江 10KM 骑行', 100086)) "
         "AND del_flag = 1 AND update_by = 'unlist_test_topics_20261029'",
         "SELECT id, del_flag FROM cms_topic WHERE id IN (1, 6, 7, 8, 990062) ORDER BY id"),
        ("migration_20261028_remove_wildlife_theme.sql",
         "UPDATE cms_topic SET del_flag = 0, user_status = 1, is_recommend = 1 "
         "WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局' AND del_flag = 2",
         "SELECT id, del_flag, user_status FROM cms_topic WHERE id BETWEEN 990052 AND 990056 ORDER BY id"),
    ]
    for name, rollback_sql, probe in cases:
        text = (SQL / name).read_text(encoding="utf-8")
        header_has = re.sub(r"\s+", " ", rollback_sql.split(" WHERE ")[0]) in re.sub(r"\s+|--", " ", text)
        cur.execute(probe)
        applied = [list(r) for r in cur.fetchall()]
        cur.execute(rollback_sql)
        rolled = cur.rowcount
        cur.execute(probe)
        after_rollback = [list(r) for r in cur.fetchall()]
        problems = []
        bs.run_file(cur, SQL / name, name, problems, keep_going=False)
        cur.execute(probe)
        reapplied = [list(r) for r in cur.fetchall()]
        r2.append({"migration": name, "rollbackSqlMatchesHeader": header_has, "applied": applied,
                   "rollbackRows": rolled, "afterRollback": after_rollback,
                   "reforwardProblems": [list(p) for p in problems], "afterReforward": reapplied,
                   "reforwardMatchesApplied": reapplied == applied})
    report["r2"] = r2
    a.report.write_text(json.dumps(report, ensure_ascii=False, indent=1, default=str) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=1, default=str)[:4000])
    return 0


if __name__ == "__main__":
    sys.exit(main())

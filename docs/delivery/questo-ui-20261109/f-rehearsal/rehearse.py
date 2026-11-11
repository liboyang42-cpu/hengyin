#!/usr/bin/env python3
"""f-rehearsal：在一次性本机 MySQL 8.0 上按 f-release/DEPLOY.md 的轮次演练整套上线迁移。

只连回环地址、只操作 cy_e2e_* scratch schema（沿用 scripts/e2e/build_schema.py 的守卫），不读生产配置。

流程：
  1. 模拟生产库 = sql/base_schema_20260818.sql + base seed + 台账拓扑序里「快照后、且不在本次 155 项发布集」的迁移
     （即生产参考基线 19408d49 已有的迁移；10-22/10-28/10-29 三个包都按“未执行”处理），再写合成业务行。
  2. 跑 f-release/release-precheck.sql（逐句只读）与 10-29 预检全文件，记录每项状态。
  3. 按 release-migrations.json 的 rounds 1→5、轮内按 step 逐个执行；「不执行」项只记账；
     step 134（20261013_admin_role_page_grants）按 DEPLOY 硬规则：预检 NEEDED 时不执行。
  4. 第二遍（按 DEPLOY 规则）：幂等项实际重跑；「APPLIED 时必须跳过」项先跑预检，APPLIED 才跳过。全库指纹必须不变。
  5. 第二遍（原样）：在克隆库上把「APPLIED 时必须跳过」项也原样重跑一次，记录报错与是否改动状态（仅供参考）。
每个文件记录：结果、受影响行数（DML rowcount 之和）、耗时、错误。

用法：
  CHENGYIN_REHEARSAL_MYSQL_PASSWORD=... python3 docs/delivery/questo-ui-20261109/f-rehearsal/rehearse.py \
      --schema cy_e2e_frehearsal --port 3316 --report <out.json>
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import time

REPO = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO))
sys.path.insert(0, str(REPO / "ci"))

from ci.migration_ledger import build_manifest  # noqa: E402
from ci.sql_splitter import split_statements, executable_statement  # noqa: E402
from ci import release_migration_rehearsal as r1029  # noqa: E402
from scripts.e2e import build_schema as bs  # noqa: E402

HERE = pathlib.Path(__file__).resolve().parent
REL = HERE.parent / "f-release"
REL_JSON = REL / "release-migrations.json"
REL_PRECHECK = REL / "release-precheck.sql"
PRECHECK_1029 = REPO / "docs/delivery/release-closeout-20261029/release-migration-precheck.sql"
ROUNDS = ("1_PRE_REQUIRED", "2_PRE_OPTIONAL", "3_POST_DEPLOY", "4_OWNER_DATA_PRE", "5_OWNER_DATA_POST")
HAZARD = "20261013_admin_role_page_grants"
DML = re.compile(r"^\s*(INSERT|UPDATE|DELETE|REPLACE)\b", re.I)

# 新数据迁移（10-29 之后）用的合成行：只写 scratch 库，让第 4/5 轮的数据迁移真正命中行。
# 由 --extra-seed 文件追加（每行一条 SQL），便于按首轮结果补齐。
EXTRA_SEED = HERE / "seed_rows.sql"


class CountingCursor:
    """代理游标：累计 DML 语句的 rowcount，其余行为与原游标一致。"""

    def __init__(self, cur):
        self._cur = cur
        self.rows = 0

    def execute(self, sql, args=None):
        result = self._cur.execute(sql, args)
        if DML.match(sql) and self._cur.rowcount and self._cur.rowcount > 0:
            self.rows += self._cur.rowcount
        return result

    def __getattr__(self, name):
        return getattr(self._cur, name)


def run_selects(cur, path):
    statuses, errors = {}, []
    for statement in split_statements(pathlib.Path(path).read_text(encoding="utf-8")):
        sql = executable_statement(statement).strip().rstrip(";")
        if not sql:
            continue
        if not sql.lstrip("(").upper().startswith(("SELECT", "WITH")):
            raise SystemExit("预检脚本含非 SELECT 语句：%s" % sql.splitlines()[0][:80])
        try:
            cur.execute(sql)
        except Exception as error:  # 记录，继续
            errors.append("%s: %s | %s" % (type(error).__name__, str(error)[:160], sql.splitlines()[0][:80]))
            continue
        names = [d[0] for d in cur.description or ()]
        rows = cur.fetchall()
        if "migration_id" in names and "status" in names:
            mi, si = names.index("migration_id"), names.index("status")
            for row in rows:
                statuses[row[mi]] = row[si]
    return statuses, errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema", default="cy_e2e_frehearsal")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3316)
    ap.add_argument("--user", default="root")
    ap.add_argument("--report", type=pathlib.Path, required=True)
    ap.add_argument("--stop-after", choices=("baseline", "run1", "run2"), default=None)
    a = ap.parse_args()
    a.password = os.environ.get(r1029.PASSWORD_ENV, "")
    bs.validate_target(a)

    rel = json.loads(REL_JSON.read_text(encoding="utf-8"))
    items = {m["migrationId"]: m for m in rel["migrations"]}
    for m in items.values():
        actual = __import__("hashlib").sha256((REPO / m["path"]).read_bytes()).hexdigest()
        if actual != m["sha256"]:
            raise SystemExit("发布清单哈希与工作树不一致：%s" % m["path"])
    order = []
    for name in ROUNDS:
        ids = sorted(rel["rounds"][name], key=lambda i: items[i]["step"])
        if ids != rel["rounds"][name]:
            print("注意：%s 的清单顺序不是按 step 排列，按 step 重排" % name)
        order += [(name, i) for i in ids]
    in_rounds = {i for _, i in order}
    never = sorted((m for m in items.values() if m["migrationId"] not in in_rounds), key=lambda m: m["step"])
    if any(m["class"] != "不执行" for m in never):
        raise SystemExit("有不在任何轮次里、又不是「不执行」的迁移：%s" % [m["migrationId"] for m in never])

    manifest = build_manifest(REPO, target_commit=subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, capture_output=True, text=True, check=True).stdout.strip(), dependency_path=REPO / "ci/migration-dependencies.json")
    by_id = {m["migrationId"]: m for m in manifest["migrations"]}
    # 依赖核对：按演练执行序，每项的集内依赖都必须已执行（或是「不执行」项的替代关系）。
    seen = set()
    dep_problems = []
    for name, i in order:
        miss = [d for d in by_id[i]["dependsOn"] if d in items and d not in seen and d in in_rounds]
        if miss:
            dep_problems.append({"migrationId": i, "round": name, "missing": miss})
        seen.add(i)
    baseline_phase = [m for m in bs.migrations_after_baseline(manifest["migrations"]) if m["migrationId"] not in items]

    report = {"mysql": None, "releaseCount": len(items), "baselineMigrations": len(baseline_phase),
              "dependencyProblems": dep_problems, "runs": {}, "neverExecuted": [m["migrationId"] for m in never]}

    def write():
        a.report.parent.mkdir(parents=True, exist_ok=True)
        a.report.write_text(json.dumps(report, ensure_ascii=False, indent=1, default=str) + "\n", encoding="utf-8")

    c = bs.connect(a)
    cur = c.cursor()
    cur.execute("SELECT VERSION(), @@sql_mode, @@character_set_server, @@collation_server, @@time_zone")
    report["mysql"] = list(cur.fetchone())
    cur.execute("DROP DATABASE IF EXISTS `%s`" % a.schema)
    cur.execute("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % a.schema)
    c.close()

    c = bs.connect(a, a.schema)
    cur = c.cursor()
    t0 = time.time()
    problems = []
    bs.run_file(cur, bs.BASELINE, "baseline", problems, keep_going=False)
    bs.run_file(cur, bs.BASELINE_SEED, "baseline-seed", problems, keep_going=False)
    for m in baseline_phase:
        if problems:
            break
        bs.run_file(cur, REPO / m["path"], m["migrationId"], problems, keep_going=False, schema_only=True)
    if problems:
        report["baselineFailure"] = [list(p) for p in problems]
        write()
        print("FAIL baseline", problems)
        return 1
    seed_errors = []
    # seed_rows.sql 用「-- @@before-round <轮次>」分段：段前在基线后写入，段后在该轮开始前写入。
    staged = {}
    if EXTRA_SEED.exists():
        parts = re.split(r"(?m)^-- @@before-round (\S+)\n", EXTRA_SEED.read_text(encoding="utf-8"))
        staged[None] = parts[0]
        for name, body in zip(parts[1::2], parts[2::2]):
            staged[name] = body

    def seed(sqls):
        for sql in sqls:
            if not sql:
                continue
            try:
                cur.execute(sql)
            except Exception as error:
                seed_errors.append("%s | %s" % (str(error)[:160], sql[:100]))

    def seed_text(text):
        seed([executable_statement(s).strip().rstrip(";") for s in split_statements(text or "")])

    seed(r1029.SYNTHETIC_ROWS + r1029.PRODUCTION_LIKE_ROWS)
    seed_text(staged.get(None))
    cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")
    report["baseline"] = {"tables": cur.fetchone()[0], "seconds": round(time.time() - t0, 1),
                          "seedErrors": seed_errors}
    print("[1] baseline: %d tables, %d post-snapshot migrations, seed errors %d"
          % (report["baseline"]["tables"], len(baseline_phase), len(seed_errors)))

    pre, pre_err = run_selects(cur, REL_PRECHECK)
    pre1029, pre1029_err = run_selects(cur, PRECHECK_1029)
    report["precheckBaseline"] = {"statuses": pre, "errors": pre_err, "pack1029Errors": pre1029_err}
    print("[2] precheck: %s, errors %d / 10-29 errors %d" %
          (json.dumps(__import__("collections").Counter(pre.values()), ensure_ascii=False),
           len(pre_err), len(pre1029_err)))
    write()
    if a.stop_after == "baseline":
        return 0

    def apply(pass_name, policy_rerun=False):
        rows = []
        seeded = set()
        for round_name, i in order:
            if pass_name == "run1" and round_name in staged and round_name not in seeded:
                seeded.add(round_name)
                before_errors = len(seed_errors)
                seed_text(staged[round_name])
                report.setdefault("stagedSeeds", []).append(
                    {"beforeRound": round_name, "errors": seed_errors[before_errors:]})
            m = items[i]
            entry = {"round": round_name, "step": m["step"], "migrationId": i, "class": m["class"],
                     "rerun": m["rerun"]}
            before = r1029.schema_fingerprint(cur)
            status = None
            if i == HAZARD or (policy_rerun and m["rerun"] == "APPLIED 时必须跳过"):
                status = run_selects(cur, REL_PRECHECK)[0].get(i)
                entry["precheck"] = status
            if i == HAZARD and status == "NEEDED":
                entry.update(result="STOP_HAZARD_NEEDED", rows=0)
                rows.append(entry)
                continue
            if policy_rerun and m["rerun"] == "APPLIED 时必须跳过":
                if status == "APPLIED":
                    entry.update(result="SKIP_APPLIED", rows=0,
                                 unchanged=before == r1029.schema_fingerprint(cur))
                else:
                    entry.update(result="REFUSED_NOT_APPLIED", rows=0)
                rows.append(entry)
                continue
            counting = CountingCursor(cur)
            problems = []
            t = time.time()
            ok = bs.run_file(counting, REPO / m["path"], i, problems, keep_going=False)
            after = r1029.schema_fingerprint(cur)
            entry.update(result="OK" if ok and not problems else "ERROR", rows=counting.rows,
                         seconds=round(time.time() - t, 2), changedTables=r1029.changed_tables(before, after),
                         errors=[list(p) for p in problems])
            rows.append(entry)
            if entry["result"] == "ERROR":
                print("  ERROR %s %s: %s" % (pass_name, i, problems))
        return rows

    first = apply("run1")
    report["runs"]["run1"] = first
    post, post_err = run_selects(cur, REL_PRECHECK)
    report["precheckAfterRun1"] = {"statuses": post, "errors": post_err}
    print("[3] run1: %d ok / %d error; post precheck %s" % (
        sum(r["result"] == "OK" for r in first), sum(r["result"] == "ERROR" for r in first),
        json.dumps(__import__("collections").Counter(post.values()), ensure_ascii=False)))
    write()
    if a.stop_after == "run1":
        return 0

    second = apply("run2", policy_rerun=True)
    report["runs"]["run2"] = second
    print("[4] run2(policy): executed %d, skipped-applied %d, error %d, changed %d" % (
        sum(r["result"] in ("OK", "ERROR") for r in second), sum(r["result"] == "SKIP_APPLIED" for r in second),
        sum(r["result"] in ("ERROR", "REFUSED_NOT_APPLIED") for r in second),
        sum(bool(r.get("changedTables")) for r in second)))
    write()
    if a.stop_after == "run2":
        return 0

    # 原样重跑：克隆库，逐个原样重跑「APPLIED 时必须跳过」项。
    clone = a.schema + "_raw2"
    dump = subprocess.run(["mysqldump", "-h", a.host, "-P", str(a.port), "-u", a.user, "--routines", "--triggers",
                           "--single-transaction", "--skip-lock-tables", a.schema],
                          env={**os.environ, "MYSQL_PWD": a.password}, capture_output=True, check=True)
    cur.execute("DROP DATABASE IF EXISTS `%s`" % clone)
    cur.execute("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % clone)
    subprocess.run(["mysql", "-h", a.host, "-P", str(a.port), "-u", a.user, clone], input=dump.stdout,
                   env={**os.environ, "MYSQL_PWD": a.password}, check=True)
    c.close()
    a2 = argparse.Namespace(**{**vars(a), "schema": clone})
    c = bs.connect(a2, clone)
    cur = c.cursor()
    raw = []
    for round_name, i in order:
        m = items[i]
        if m["rerun"] != "APPLIED 时必须跳过":
            continue
        before = r1029.schema_fingerprint(cur)
        counting = CountingCursor(cur)
        problems = []
        ok = bs.run_file(counting, REPO / m["path"], i, problems, keep_going=False)
        after = r1029.schema_fingerprint(cur)
        raw.append({"step": m["step"], "migrationId": i, "result": "OK" if ok and not problems else "ERROR",
                    "rows": counting.rows, "changedTables": r1029.changed_tables(before, after),
                    "errors": [list(p) for p in problems]})
    report["runs"]["run2raw"] = raw
    print("[5] run2(raw single-apply on clone): ok %d, error %d, changed %d" % (
        sum(r["result"] == "OK" for r in raw), sum(r["result"] == "ERROR" for r in raw),
        sum(bool(r["changedTables"]) for r in raw)))
    cur.execute("DROP DATABASE `%s`" % clone)
    c.close()
    write()
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""只读对账:每个 migration 到底在生产库落没落。

判据不是我猜的,是**迁移自己写的** `-- runner: assert=scalar-true` 回读断言。

★ 为什么不能把断言单独拎出来跑(2026-08-27 实测踩到)
--------------------------------------------------------------------
这些断言大量引用同一 session 里前面 `SET @x := (SELECT ... information_schema ...)`
攒下来的变量。把断言单独丢进一个新连接执行,`@x` 是 NULL,
`NULL IN (...)` → NULL → 判成假 ⇒ **一个刚刚成功落库、63 条语句全过的迁移,
被报成「未落库」**。那一轮 28 条「未落库」里混着这类假阳性,差点拿去排期。

所以本脚本按**文件顺序**、在**同一连接**里重放:
  · `SET @... := (SELECT ...)`      —— 只读,重建断言需要的会话变量
  · `SET SESSION ...`               —— 只影响本会话
  · `-- runner: assert=scalar-true` 的 SELECT —— 真正的判据
其余一律跳过(CREATE/ALTER/DROP/INSERT/UPDATE/DELETE/PREPARE/EXECUTE/DEALLOCATE)。
**一条都不写库。**

语义上这样是对的:断言描述的是「落库后应有的样子」,拿它去问当前生产 ——
落过就真,没落就假。至于 DDL 前后都要成立的形状断言,迁移本身就写成了两态都接受
(例如 `@col_def IN ('', 'tinyint:NO:0')`),重放不会误判。

★ 已知盲区,别当全覆盖
--------------------------------------------------------------------
· 只有带 scalar-true 断言的迁移判得了;没写断言的**判不了**,不是「没问题」。
  末尾会把这个数字明明白白打出来 —— 沉默不该被读成绿。
· `rowcount-positive` 类断言只读复现不了(它判的是上一句改了几行),同样不下结论。
· 断言为假 ≠ 一定没跑:数据订正类迁移可能跑过了、事后数据又变了。
  所以输出分「schema 缺口」和「仅断言为假」两栏,前者才是确定的缺。

用法:
  export CHENGYIN_DB_PASSWORD=...
  python3 ci/audit_migrations_applied.py                # 全量,有缺口 exit 1
  python3 ci/audit_migrations_applied.py <关键字>       # 只看文件名含该关键字的
  python3 ci/audit_migrations_applied.py --check-baseline
      对 ci/migration-applied-baseline.txt 做**双向**棘轮判定,给 CI 用:
        · 出现基线之外的落差   ⇒ 红(有人合了带迁移的 PR 却没落库)
        · 基线里的某条已落库   ⇒ 也红,要求删掉那行
      第二条同样重要:只挡新增、不要求删旧,基线就会永远停在最初的长度 ——
      棘轮看着还在,实际已经不拧了。
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import run_migration as rm  # noqa: E402
from sql_splitter import RUNNER_ASSERTION, executable_statement  # noqa: E402

REPO = os.path.dirname(HERE)
SQL_DIR = os.path.join(REPO, "chengyinhub-admin", "sql")
DEFAULT_CONFIG = os.path.join(
    REPO, "chengyinhub-admin", "src", "main", "resources", "application-druid.yml")

# 只读重放白名单:除了断言本身,只放行「攒会话变量」和「设会话参数」两种形状。
_SET_VAR = re.compile(r"^SET\s+@[A-Za-z_]", re.IGNORECASE)
_SET_SESSION = re.compile(r"^SET\s+SESSION\b", re.IGNORECASE)
# 从断言里抽出它自己声明的 schema 期望,用来把「确定缺」和「仅断言为假」分开。
_DECL_TABLE = re.compile(r"table_name\s*=\s*'([a-z0-9_]+)'", re.IGNORECASE)


def scalar_assertions_and_setup(text, split):
    """按文件顺序返回 (要重放的只读语句, 是否含 scalar-true 断言, 是否只有 rowcount 类)。"""
    replay, has_scalar, has_rowcount = [], False, False
    for stmt in split(text):
        marks = list(RUNNER_ASSERTION.finditer(stmt))
        body = executable_statement(stmt).rstrip().rstrip(";").strip()
        if marks:
            mode = marks[0].group("mode").lower()
            if mode == "scalar-true":
                has_scalar = True
                replay.append(("assert", body))
                continue
            has_rowcount = True
            continue
        if _SET_VAR.match(body) or _SET_SESSION.match(body):
            replay.append(("setup", body))
    return replay, has_scalar, has_rowcount


BASELINE = os.path.join(HERE, "migration-applied-baseline.txt")
SUPERSESSIONS = os.path.join(HERE, "migration-assertion-supersessions.json")


def read_supersessions():
    """读取显式旧断言替代关系；配置损坏时拒绝继续，不能静默放行。"""
    with open(SUPERSESSIONS, encoding="utf-8") as handle:
        payload = json.load(handle)
    if payload.get("schemaVersion") != 1 or not isinstance(payload.get("supersessions"), dict):
        raise ValueError(f"无效的迁移断言替代配置:{SUPERSESSIONS}")
    return payload["supersessions"]


def apply_assertion_supersessions(assertion_false, landed, mapping, known_names):
    """只在补偿迁移断言已通过时，替代被后续 schema 演进淘汰的旧断言。"""
    for old_name, replacement_name in mapping.items():
        if old_name not in known_names:
            raise ValueError(f"断言替代配置引用的旧迁移不存在:{old_name}")
        if replacement_name not in known_names:
            raise ValueError(f"断言替代配置引用的补偿迁移不存在:{replacement_name}")
        if old_name == replacement_name:
            raise ValueError(f"迁移不能替代自身断言:{old_name}")

    landed_names = set(landed)
    remaining, superseded = [], []
    for item in assertion_false:
        old_name = item[0]
        replacement_name = mapping.get(old_name)
        if replacement_name and replacement_name in landed_names:
            superseded.append((old_name, replacement_name))
        else:
            remaining.append(item)
    return remaining, superseded


def read_baseline():
    """基线文件缺失/读不动一律当致命错,绝不当成空基线放行(空基线=门禁静默失效)。"""
    if not os.path.isfile(BASELINE):
        raise SystemExit(f"基线文件不存在:{BASELINE} —— 拒绝按空基线放行")
    names = set()
    for line in open(BASELINE, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            names.add(line)
    return names


def main():
    args = [a for a in sys.argv[1:]]
    baseline_mode = "--check-baseline" in args
    if baseline_mode:
        args.remove("--check-baseline")
    needle = args[0] if args else ""
    if baseline_mode and needle:
        raise SystemExit("--check-baseline 是全量判定,不接受关键字过滤")
    split, splitter_path = rm.load_split_statements()
    print(f"分句器: {splitter_path}")
    conn, db = rm.connect(DEFAULT_CONFIG)

    all_files = sorted(glob.glob(os.path.join(SQL_DIR, "migration_*.sql")))
    known_names = {os.path.basename(path) for path in all_files}
    files = all_files
    if needle:
        files = [f for f in files if needle in os.path.basename(f)]

    landed, schema_gap, assertion_false, unjudgeable = [], [], [], []

    for path in files:
        name = os.path.basename(path)
        text = open(path, encoding="utf-8").read()
        replay, has_scalar, has_rowcount = scalar_assertions_and_setup(text, split)
        if not has_scalar:
            unjudgeable.append((name, "只有 rowcount 类断言" if has_rowcount else "没写断言"))
            continue

        declared_tables = {t.lower() for t in _DECL_TABLE.findall(text)}
        # 每个文件一条干净连接:会话变量不许跨文件串味。
        cur = conn.cursor()
        cur.execute("SET @__audit := 1")  # 触发一次,确保连接活着
        ok, detail = True, ""
        for kind, sql in replay:
            try:
                cur.execute(sql)
                if kind == "assert":
                    row = cur.fetchone()
                    if not (row and row[0]):
                        ok, detail = False, "断言为假"
                        break
                else:
                    cur.fetchall() if cur.description else None
            except Exception as exc:  # noqa: BLE001
                ok, detail = False, str(exc).split("\n")[0][:100]
                break
        cur.close()

        if ok:
            landed.append(name)
            continue
        missing = sorted(t for t in declared_tables if not table_exists(conn, db, t))
        if missing:
            schema_gap.append((name, detail, missing))
        else:
            assertion_false.append((name, detail))

    supersessions = read_supersessions()
    assertion_false, superseded = apply_assertion_supersessions(
        assertion_false, landed, supersessions, known_names)

    print()
    print(f"扫描 {len(files)} 个迁移")
    print(f"  ✅ 断言通过 = 已落库            : {len(landed)}")
    print(f"  ❌ schema 缺口 = 确定没落       : {len(schema_gap)}")
    print(f"  ⚠️  仅断言为假 = 需人眼看        : {len(assertion_false)}")
    print(f"  ↪️  旧断言已由补偿迁移替代       : {len(superseded)}")
    print(f"  ➖ 判不了(没断言/rowcount 类)   : {len(unjudgeable)}"
          f"   ← 这不是「没问题」,是盲区")
    if schema_gap:
        print("\n❌ schema 缺口(它声明的表在生产不存在):")
        for name, why, missing in schema_gap:
            print(f"   {name}\n       {why};缺表 {', '.join(missing)}")
    if assertion_false:
        print("\n⚠️ 断言为假但声明的表都在 —— 可能只落了一半,也可能是数据订正类跑过后数据又变了:")
        for name, why in assertion_false:
            print(f"   {name}\n       {why}")
    if superseded:
        print("\n↪️ 旧断言已被生产断言通过的补偿迁移替代:")
        for old_name, replacement_name in superseded:
            print(f"   {old_name}\n       -> {replacement_name}")

    if not baseline_mode:
        return 1 if (schema_gap or assertion_false) else 0

    gaps = {n for n, *_ in schema_gap} | {n for n, _ in assertion_false}
    base = read_baseline()
    if needle:  # 上面已拒,这里只是防御
        raise SystemExit("--check-baseline 不接受关键字过滤")
    new_gaps = sorted(gaps - base)
    fixed = sorted(base & set(landed))
    print()
    print(f"棘轮基线:{BASELINE}")
    print(f"  基线条目 {len(base)};本次落差 {len(gaps)}")
    if new_gaps:
        print("\n❌ 基线之外的新落差 —— 有人合了带迁移的 PR 却没落库:")
        for n in new_gaps:
            print(f"   {n}")
    if fixed:
        print("\n❌ 基线里这些已经落库了 —— 请把它们从基线删掉,棘轮要收紧:")
        for n in fixed:
            print(f"   {n}")
    if not new_gaps and not fixed:
        print("  ✅ 无新增落差,基线也没有过期条目")
    return 1 if (new_gaps or fixed) else 0


_TABLE_CACHE = {}


def table_exists(conn, db, table):
    if not _TABLE_CACHE:
        cur = conn.cursor()
        cur.execute(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = %s", (db,))
        _TABLE_CACHE.update({r[0].lower(): True for r in cur.fetchall()})
        cur.close()
    return table.lower() in _TABLE_CACHE


if __name__ == "__main__":
    sys.exit(main())

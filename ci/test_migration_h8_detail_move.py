#!/usr/bin/env python3
"""H8 待结明细搬钱的【配对唯一性】契约：真跑迁移自己的语句，不是读它的文本。

用法:python3 ci/test_migration_h8_detail_move.py   (纯 stdlib,CI 直跑)

【为什么要这条契约】
2026-08-16 第五轮双轴复审同时点名(Spec P2-2 / Standards P3-8):
`migration_20260815_tandianri_h8_rate_convergence.sql` 的配对 guard 是【单向】的 ——
它只数「同一报名的待结 merchant 明细必须恰好 1 条」,却不数 creator 明细。
于是同一张报名留下两条 `receiver_type='creator' AND status=0` 明细时(幂等键含 receiverId,
发布者在两条结算路之间易主即产生):
  ① 候选临时表产出两行 move,共享同一个 merchant_detail_id 与同一份 merchant 前值快照;
  ② MySQL 的多表 JOIN UPDATE 对同一目标行【只更新一次】⇒ merchant 只收下其中一份;
  ③ 两条 creator 明细却【都】被清零 ⇒ 差额凭空消失;
  ④ 回读 4/4 逐 move 行比对,两条 creator 金额【相等】时两边都成立 ⇒ 恒真通过、静默。
而同一张报名、同一份 gross、同一条费率算出来的两条金额【通常就是相等的】,
也就是说静默那一支是主路而不是边角。

【本契约的观测方式:替身库真执行,不是 grep】
把迁移里【明细搬钱那一段】的语句原样喂给 sqlite 跑一遍(方言替换见 _MYSQLISMS 与
`_rewrite_join_update`,每一处都在这里显式登记),因此断言的是【语句真实改写了哪些行】。
guard 表的主键冲突在替身库上表现为 IntegrityError —— 与 MySQL 上撞主键停机同构。

【为什么 sqlite 能复现 MySQL 多表 UPDATE 的那个语义】
MySQL 的 `UPDATE t JOIN m ON ...` 对同一目标行只更新一次;SQLite 的 `UPDATE t SET ... FROM m`
在 FROM 侧匹配多行时同样只取其中任意一行。两者在「两条 move 行指向同一个 merchant 行」
这一点上语义一致 —— 而这正是本缺陷的机理。用例
`test_负控_摘掉creator配对闸即复现静默丢钱` 把这条语义本身也断言了下来。

【已知边界(故意不做,不得从本契约推导 PASS)】
  - 替身库是 sqlite,不是 MySQL 8.0.45:DECIMAL(12,2) 在这里是 REAL(用例断言到 1e-9)。
  - 只 replay 引用 settlement_detail / 搬钱临时表的语句;费率收敛段由
    ci/test_migration_h8_rate_scope.py 覆盖,积分探针与 FORCE INDEX 仍不在任何观测面内。
  - 真 MySQL 上一句都没执行过。
"""
import importlib.util
import os
import re
import unittest

# 替身库的 SQLite 从哪来 —— 完整缘由见下方 _SQLITE_REQUIRED 那段。
# pysqlite3 是 stdlib sqlite3 的同源 fork，API 与语义一致，只是自带一份新的 SQLite，
# 不吃系统 libsqlite3。装不上就退回内置的，由版本断言当场判红，不是静默降级。
try:
    import pysqlite3 as sqlite3
except ImportError:
    import sqlite3

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATION = os.path.join(
    REPO, "chengyinhub-admin", "sql",
    "migration_20260815_tandianri_h8_rate_convergence.sql")


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_SPLITTER = _load("chengyin_sql_splitter", os.path.join(REPO, "ci", "sql_splitter.py"))

# 替身库方言替换：只此三处，多一处都要在这里显式登记。
_MYSQLISMS = (
    (re.compile(r"(?i)\s+ENGINE\s*=\s*InnoDB"), ""),
    (re.compile(r"(?i)\bTEMPORARY\s+TABLE\b"), "TABLE"),
    (re.compile(r"(?i)\bNOW\(\)"), "CURRENT_TIMESTAMP"),
)

_GUARD_BOOTSTRAP = re.compile(
    r"(?is)^\s*(?:CREATE(?:\s+TEMPORARY)?\s+TABLE|DROP(?:\s+TEMPORARY)?\s+TABLE"
    r"|INSERT\s+INTO)\s+tandianri_h8_rate_guard\b"
    r"(?!.*\bSELECT\s+1\s+WHERE\b)")

# MySQL 多表 JOIN UPDATE → SQLite 的 UPDATE ... FROM。第四处方言替换，同样显式登记。
_JOIN_UPDATE = re.compile(
    r"(?is)^\s*UPDATE\s+(?P<target>\w+)\s+AS\s+(?P<talias>\w+)\s+"
    r"JOIN\s+(?P<joined>\w+)\s+AS\s+(?P<jalias>\w+)\s+"
    r"ON\s+(?P<on>.+?)\s+SET\s+(?P<assign>.+?)\s+WHERE\s+(?P<where>.+?)\s*$")

# 各阶段的定位判据：按语句【内容】认，不按下标认(下标会随文件编辑漂移)。
STAGES = {
    "pairing_guard": lambda s: "receiver_type = 'merchant'" in s and "COUNT(*)" in s,
    "move_candidates": lambda s: "CREATE TEMPORARY TABLE tandianri_h8_pending_creator_move" in s,
    "merchant_absorb": lambda s: _JOIN_UPDATE.match(s) is not None,
    "creator_zero": lambda s: "SET gross_amount = 0" in s,
    "move_readback": lambda s: "move.merchant_gross_before + move.creator_gross_amount" in s,
}

# 只数 creator 侧的那半边闸；负控要能把它单独摘掉。
_CREATOR_PAIRING_CLAUSE = re.compile(
    r"(?is)\s*OR\s*\(\s*SELECT\s+COUNT\(\*\)\s+FROM\s+settlement_detail\s+sibling_creator\b.*?\)\s*<>\s*1")

SCHEMA = (
    """
    CREATE TABLE settlement_detail (
        id INTEGER PRIMARY KEY,
        source_type TEXT NOT NULL,
        source_id INTEGER NOT NULL,
        receiver_type TEXT NOT NULL,
        status INTEGER NOT NULL,
        gross_amount REAL NOT NULL,
        commission_amount REAL NOT NULL,
        settlement_amount REAL NOT NULL,
        update_time TEXT
    )
    """,
    """
    CREATE TABLE cms_registration (
        id INTEGER PRIMARY KEY,
        owner_type INTEGER NOT NULL,
        owner_id INTEGER NOT NULL
    )
    """,
    "CREATE TABLE cms_topic (id INTEGER PRIMARY KEY, is_settlement INTEGER NOT NULL)",
    "CREATE TABLE cms_activity (id INTEGER PRIMARY KEY, is_settlement INTEGER NOT NULL)",
)

REGISTRATIONS = ((1001, 2, 2001), (1002, 2, 2001))
ACTIVITIES = ((2001, 0),)
TOPICS = ()

DETAIL_COLUMNS = ("id", "source_type", "source_id", "receiver_type", "status",
                  "gross_amount", "commission_amount", "settlement_amount", "update_time")

# 干净基线：一张报名 = 一条 creator + 一条 merchant。
CLEAN_DETAILS = (
    (1, "registration", 1001, "creator", 0, 100.0, 0.0, 100.0, None),
    (2, "registration", 1001, "merchant", 0, 700.0, 0.0, 700.0, None),
)

# ★ 缺陷 fixture：同一张报名两条【同额】待结 creator 明细 + 一条 merchant。
#   两条金额相等正是回读 4/4 恒真的那个子情形 —— 也是最可能出现的那个。
DUPLICATE_DETAILS = CLEAN_DETAILS + (
    (3, "registration", 1002, "creator", 0, 100.0, 0.0, 100.0, None),
    (4, "registration", 1002, "creator", 0, 100.0, 0.0, 100.0, None),
    (5, "registration", 1002, "merchant", 0, 700.0, 0.0, 700.0, None),
)


def detail_move_statements(sql_text):
    """切出明细搬钱那一段：引用 settlement_detail / 搬钱临时表的语句 + guard 表自身的建播删。

    分句器已经剥掉注释，所以注释里出现的表名不会把无关语句带进来。
    """
    kept = []
    for statement in _SPLITTER.split_statements(sql_text):
        statement = _SPLITTER.executable_statement(statement)
        lowered = statement.lower()
        touches_details = ("settlement_detail" in lowered
                           or "tandianri_h8_pending_creator_move" in lowered)
        if touches_details or _GUARD_BOOTSTRAP.match(statement):
            kept.append(statement)
    return kept


def _rewrite_join_update(statement):
    """MySQL 多表 JOIN UPDATE 改写成 SQLite 的 UPDATE ... FROM，语义等价地保留

    「同一目标行只被加一次」这条 —— 缺陷机理就在这条上。
    """
    match = _JOIN_UPDATE.match(statement)
    if match is None:
        return statement
    alias = match.group("talias")
    prefix = re.compile(r"(?i)^\s*%s\." % re.escape(alias))
    assignments = []
    for piece in match.group("assign").split(","):
        column, _, value = piece.partition("=")
        assignments.append("%s =%s" % (prefix.sub("", column).strip(), value))
    return ("UPDATE %s AS %s SET %s FROM %s AS %s WHERE (%s) AND (%s)"
            % (match.group("target"), alias, ", ".join(assignments),
               match.group("joined"), match.group("jalias"),
               match.group("on"), match.group("where")))


def to_sqlite(statement):
    statement = _rewrite_join_update(statement)
    for pattern, replacement in _MYSQLISMS:
        statement = pattern.sub(replacement, statement)
    return statement


class GuardTripped(Exception):
    """迁移撞 guard 停机（MySQL 上是主键冲突）。自带撞红【当时】的 settlement_detail 快照。"""

    def __init__(self, statement, details, cause):
        super(GuardTripped, self).__init__(str(cause))
        self.statement = statement
        self.details = details


# ★ 本文件的替身库靠 SQLite 的 `UPDATE t SET ... FROM m` 复现 MySQL 多表 JOIN UPDATE
#   的语义(见文件头「为什么 sqlite 能复现」一段)。该语法是 SQLite 3.33.0 引入的。
#   2026-08-28 云效实测:容器里的 SQLite 太旧,报的是
#       sqlite3.OperationalError: near "FROM": syntax error
#   —— 一句看不出根因的语法错,4 个用例一起 ERROR。
#   ⇒ 在这里显式断言版本,把隐晦语法错变成能一眼看懂的 fail-closed。
#   ⚠️ 故意【不 skip】:跳过 = 没验,而本文件的负控正是「摘掉闸就静默丢钱」那一族,
#      环境不达标必须让它红,由人去修环境,不能悄悄放行。
#
#   2026-08-28 查清了根因,不再是推断:alinux3 官方源(os / updates / plus /
#   powertools 四个仓全查过)里 sqlite 最高就是 3.26.0-*.al8 —— 上游 3.26.0 只在打
#   RHEL8 的补丁位。所以:
#     · 「dnf 装个新 sqlite」这条路是死的,源里根本没有;
#     · 「装 python3.11」也不解决,Python 内置 sqlite3 链的是系统 libsqlite3。
#   ⇒ 改用 pysqlite3(见文件顶部 import):pysqlite3-binary 的 wheel 静态内嵌
#     SQLite 3.51.1,与系统库无关,语法语义与本文件的论证一字不动。
#   构建环境需要装:python3.11 -m pip install "pysqlite3-binary==0.5.4.post2"
#   (钉版本 —— 同小程序门禁那条教训:不钉版本 = 上游一发版 CI 就可能红)
_SQLITE_REQUIRED = "3.33.0"   # `UPDATE ... FROM` 是 SQLite 3.33.0 引入的
if tuple(int(x) for x in sqlite3.sqlite_version.split(".")) < (3, 33, 0):
    raise RuntimeError(
        "本契约的替身库需要 SQLite >= " + _SQLITE_REQUIRED
        + ",当前 " + sqlite3.sqlite_version
        + "(模块 " + sqlite3.__name__ + ")。这不是代码问题,是运行环境问题。"
        + "修法:python3 -m pip install \"pysqlite3-binary==0.5.4.post2\" "
        + "—— 它自带 SQLite 3.51.1,不依赖系统 libsqlite3。"
        + "⚠️ 不要跳过本文件:它的负控守的是「摘掉 creator 配对闸即静默丢钱」,"
        + "跳过 = 没验。")


def replay(statements, details):
    """在替身库上逐句跑，返回终态 settlement_detail；撞 guard → GuardTripped。

    逐句执行(而不是一次 executescript)是为了在撞红的那一刻回读表状态 ——
    「闸在写之前」这句话只有这样才有证据，否则只能靠读代码相信。
    """
    connection = sqlite3.connect(":memory:")
    try:
        for ddl in SCHEMA:
            connection.execute(ddl)
        connection.executemany(
            "INSERT INTO settlement_detail VALUES (?,?,?,?,?,?,?,?,?)", details)
        connection.executemany("INSERT INTO cms_registration VALUES (?,?,?)", REGISTRATIONS)
        connection.executemany("INSERT INTO cms_activity VALUES (?,?)", ACTIVITIES)
        if TOPICS:
            connection.executemany("INSERT INTO cms_topic VALUES (?,?)", TOPICS)
        for statement in statements:
            try:
                connection.execute(to_sqlite(statement))
            except sqlite3.IntegrityError as error:
                raise GuardTripped(statement, read_details(connection), error)
        return read_details(connection)
    finally:
        connection.close()


def read_details(connection):
    cursor = connection.execute(
        "SELECT %s FROM settlement_detail ORDER BY id"
        % ", ".join(c for c in DETAIL_COLUMNS if c != "update_time"))
    columns = [c for c in DETAIL_COLUMNS if c != "update_time"]
    return {row[0]: dict(zip(columns, row)) for row in cursor.fetchall()}


def drop_creator_pairing_guard(statements):
    """摘掉【只数 creator 侧】的那半边配对闸——负控用。

    必须恰好命中一条语句并真的发生替换，否则负控本身是假绿。
    """
    hits = [index for index, text in enumerate(statements)
            if STAGES["pairing_guard"](text)]
    if len(hits) != 1:
        raise AssertionError("配对闸命中 %d 条语句，负控定位失效" % len(hits))
    index = hits[0]
    mutated, count = _CREATOR_PAIRING_CLAUSE.subn("", statements[index], count=1)
    if count != 1:
        raise AssertionError("未摘到 creator 配对闸，负控本身是假绿")
    return statements[:index] + [mutated] + statements[index + 1:]


class H8DetailMoveContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(MIGRATION, encoding="utf-8") as handle:
            cls.sql = handle.read()
        cls.statements = detail_move_statements(cls.sql)

    def assertAmount(self, detail, field, expected):
        self.assertAlmostEqual(expected, detail[field], delta=1e-9,
                               msg="%s 期望 %s，实得 %s" % (field, expected, detail[field]))

    # ---- 观测面本身要先证明取对了，否则下面全是空跑恒真 ----

    def test_replay_scope_really_contains_the_detail_move_statements(self):
        for stage in ("pairing_guard", "move_candidates", "merchant_absorb",
                      "creator_zero", "move_readback"):
            with self.subTest(stage=stage):
                hits = [s for s in self.statements if STAGES[stage](s)]
                self.assertEqual(1, len(hits), "阶段 %s 必须恰好命中一条语句" % stage)
        joined = " ".join(" ".join(s.split()) for s in self.statements)
        self.assertIn("INSERT INTO tandianri_h8_rate_guard (id) VALUES (1)", joined)
        # 依赖本替身库没有的表 / MySQL 专属语法的语句必须被排除掉
        self.assertNotIn("FORCE INDEX", joined)
        self.assertNotIn("settlement_rule", joined)
        self.assertNotIn("cms_points_setting", joined)

    def test_多表join更新在替身库上仍是同一目标行只加一次(self):
        """缺陷机理依赖这条语义。它若不成立，本文件其余负控全部失去意义。"""
        rewritten = to_sqlite(
            next(s for s in self.statements if STAGES["merchant_absorb"](s)))
        self.assertIn("FROM tandianri_h8_pending_creator_move", rewritten)
        self.assertNotIn("JOIN tandianri_h8_pending_creator_move", rewritten)
        connection = sqlite3.connect(":memory:")
        try:
            connection.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v REAL)")
            connection.execute("CREATE TABLE m (target INTEGER, add_v REAL)")
            connection.execute("INSERT INTO t VALUES (1, 10.0)")
            connection.executemany("INSERT INTO m VALUES (?,?)", ((1, 5.0), (1, 5.0)))
            connection.execute(
                "UPDATE t AS d SET v = d.v + m.add_v FROM m WHERE m.target = d.id")
            self.assertAlmostEqual(
                15.0, connection.execute("SELECT v FROM t").fetchone()[0], delta=1e-9)
        finally:
            connection.close()

    # ---- 正向：一对一配对时金额守恒地搬过去 ----

    def test_单条creator明细正常并入merchant(self):
        details = replay(self.statements, CLEAN_DETAILS)
        self.assertAmount(details[1], "settlement_amount", 0.0)
        self.assertAmount(details[1], "gross_amount", 0.0)
        self.assertAmount(details[1], "commission_amount", 0.0)
        self.assertAmount(details[2], "settlement_amount", 800.0)
        self.assertAmount(details[2], "gross_amount", 800.0)

    def test_guard_mechanism_can_actually_go_red(self):
        """缺 merchant 明细(既有的单向闸)必须在写前红——证明替身库上 guard 真会抛。"""
        without_merchant = tuple(row for row in CLEAN_DETAILS if row[0] != 2)
        with self.assertRaises(GuardTripped):
            replay(self.statements, without_merchant)

    # ---- 主控：同一报名多条 pending creator 必须在【改任何账之前】红 ----

    def test_同一报名两条待结creator明细必须在写前红(self):
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, DUPLICATE_DETAILS)
        self.assertTrue(STAGES["pairing_guard"](caught.exception.statement),
                        "撞红的不是配对闸，说明已经先搬过账了")
        # 【写前】的证据不是读代码，是撞红那一刻回读出来的表状态：逐行仍是原值。
        at_failure = caught.exception.details
        for row in DUPLICATE_DETAILS:
            original = dict(zip(DETAIL_COLUMNS, row))
            original.pop("update_time")
            self.assertEqual(original, at_failure[original["id"]])

    def test_负控_摘掉creator配对闸即复现静默丢钱(self):
        """摘掉新闸 = 修复前那份迁移：它会【全绿】，而 100 元凭空消失、回读 4/4 恒真通过。"""
        mutated = drop_creator_pairing_guard(self.statements)
        details = replay(mutated, DUPLICATE_DETAILS)
        # 两条 creator 都被清零
        self.assertAmount(details[3], "settlement_amount", 0.0)
        self.assertAmount(details[4], "settlement_amount", 0.0)
        # 但 merchant 只收下其中一份 —— 差额静默消失，且没有任何 guard 拦住
        self.assertAmount(details[5], "settlement_amount", 800.0)
        self.assertAmount(details[5], "gross_amount", 800.0)
        # 干净那张报名照常正确，说明丢的就是重复那张
        self.assertAmount(details[2], "settlement_amount", 800.0)

    def test_已结算父级的明细一律不动(self):
        """锁定第 9 条：只搬 is_settlement=0 的父级；已结算的是退款回冲基数，不可变。"""
        settled = ((2001, 1),)
        connection = sqlite3.connect(":memory:")
        try:
            for ddl in SCHEMA:
                connection.execute(ddl)
            connection.executemany(
                "INSERT INTO settlement_detail VALUES (?,?,?,?,?,?,?,?,?)", CLEAN_DETAILS)
            connection.executemany("INSERT INTO cms_registration VALUES (?,?,?)", REGISTRATIONS)
            connection.executemany("INSERT INTO cms_activity VALUES (?,?)", settled)
            for statement in self.statements:
                connection.execute(to_sqlite(statement))
            details = read_details(connection)
        finally:
            connection.close()
        self.assertAmount(details[1], "settlement_amount", 100.0)
        self.assertAmount(details[2], "settlement_amount", 700.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""H8 发布者存量前置闸的【对称性】契约：真跑迁移自己的语句，不是读它的文本。

用法:python3 ci/test_migration_h8_publisher_guard.py   (纯 stdlib,CI 直跑)

【为什么要这条契约】
2026-08-16 第六轮双轴复审同时点名(Spec P2-1 / Standards P2-1 同一个根因):
`awardPublisherSettlement` 是本 PR 新增的【第二条会硬抛的路径】,它对发布者的口径是
「memberId > 0 ∧ ums_member 行存在」(PointsAwardServiceImpl:110-112 与 :126-128;
UmsMemberMapper.lockMemberById:90-92 是 `select id from ums_member where id = ? for update`,
**不带 status / del_flag 过滤** —— 判据只有"行在不在")。而存量前置闸只做了半道:

  硬抛点                                           需要的存量闸          修复前
  活动腿 payActivityCommission ← activity.member_id  非空 + 会员行存在     ✅ guard 6
  活动腿 awardPublisherSettlement ← 源 topic.member_id 非空 + 会员行存在    ⚠️ 只判了 IS NULL
  主题腿 awardPublisherSettlement ← topic.member_id   非空 + 会员行存在     ❌ 完全没有

后果与迁移自己在注释里点名要避免的那种活动**同一个病**,只是换了一条腿:
费率一收敛,这些主题/活动每轮结算走到发积分那一跳硬抛 → 独立事务整个回滚(含 CAS 与
`settleCoopTopic` 已写的商家应付台账)→ **每轮回滚 + 每轮告警、应付/现金永不出账**,
而且迁移全绿、CI 全绿,部署后才在结算轮次里现形。

【本契约的观测方式:替身库真执行,不是 grep】
把迁移里【发布者前置闸那一段】的语句原样喂给 sqlite 跑一遍(方言替换见 _MYSQLISMS),
因此断言的是【语句真实选中了哪些行】。guard 表的主键冲突在替身库上表现为 IntegrityError
—— 与 MySQL 上撞主键停机同构。两条摘闸负控各自复现修复前那份迁移的静默放行。

【目标集为什么要和运行时逐字对齐】
闸放宽一分就是假绿,收窄一分就是假红 —— 假红比缺闸更坏:它会把一条【运行时根本走不到
发积分那一跳】的主题变成"迁移永远跑不了",而那种主题正是裁决第 7 条有意留下的
(无生效 coop 单 ⇒ 先撞 NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN,压根到不了积分)。
故本契约同时锁两侧:该红的红(test_负控_*),该绿的绿(test_运行时够不到的*)。

【已知边界(故意不做,不得从本契约推导 PASS)】
  - 替身库是 sqlite,不是 MySQL 8.0.45;真 MySQL 上一句都没执行过。
  - 只 replay 引用发布者域表的语句;费率收敛段 / 明细搬钱段 / 积分探针与 FORCE INDEX
    分别由 ci/test_migration_h8_rate_scope.py、ci/test_migration_h8_detail_move.py 覆盖
    或仍不在任何观测面内。
  - 生产存量完全未量化(禁止连库):本契约不回答"生产上到底有几条这样的主题"。
"""
import importlib.util
import os
import re
import sqlite3
import unittest

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

# 替身库方言替换：只此两处，多一处都要在这里显式登记。
_MYSQLISMS = (
    (re.compile(r"(?i)\s+ENGINE\s*=\s*InnoDB"), ""),
    (re.compile(r"(?i)\bTEMPORARY\s+TABLE\b"), "TABLE"),
)

_GUARD_BOOTSTRAP = re.compile(
    r"(?is)^\s*(?:CREATE(?:\s+TEMPORARY)?\s+TABLE|DROP(?:\s+TEMPORARY)?\s+TABLE"
    r"|INSERT\s+INTO)\s+tandianri_h8_rate_guard\b"
    r"(?!.*\bSELECT\s+1\s+WHERE\b)")

# 发布者域的表；命中其一即纳入 replay。
_PUBLISHER_TABLES = ("cms_topic", "cms_activity", "cms_registration",
                     "ums_member", "coop_order")
# 别的域；命中其一即排除（先判排除，故 ums_member_points_detail 不会被 ums_member 带进来）。
_OTHER_DOMAINS = ("settlement_detail", "settlement_rule", "cms_points_setting",
                  "ums_member_points_detail", "tandianri_h8_pending_creator_move",
                  "tandianri_h8_rate_before")

_TEMP_TABLES = ("tandianri_h8_rate_guard", "tandianri_h8_pending_creator_move",
                "tandianri_h8_rate_before")

def _flat(statement):
    """把语句压成单行再找子串，免得判据被换行/缩进的写法拆开。"""
    return " ".join(statement.split())


# 各闸的定位判据：按语句【内容】认，不按下标认(下标会随文件编辑漂移)。三者互斥。
# ⚠️ 不能按「引没引用 cms_topic」分活动腿两道闸:guard 6 也要判来源主题的可达性
#    (product_type 为 2 / 为空时运行时先硬抛，到不了现金那一跳)。真正区分它们的是
#    【会员行挂在谁身上】——guard 5 挂 topic.member_id，guard 6 挂 activity.member_id。
STAGES = {
    # guard 5：活动腿的【来源主题发布者】——会员行挂在 topic.member_id 上，且引用 cms_activity
    "activity_source_topic_publisher":
        lambda s: "member.id = topic.member_id" in _flat(s) and "cms_activity" in s,
    # guard 6：活动腿的【现金收款人】——会员行挂在 activity.member_id 上
    "activity_cash_payee":
        lambda s: "member.id = activity.member_id" in _flat(s),
    # 新增：主题腿的【发布者】——唯一引用 coop_order 的语句
    "topic_publisher":
        lambda s: "coop_order" in s,
}

# 「会员行必须存在」那半道判据；负控要能把它单独摘掉，退化成修复前的"只判 IS NULL"。
_MEMBER_EXISTS_CLAUSE = re.compile(r"(?is)\s+OR\s+member\.id\s+IS\s+NULL")

SCHEMA = (
    """
    CREATE TABLE cms_topic (
        id INTEGER PRIMARY KEY,
        member_id INTEGER,
        is_settlement INTEGER NOT NULL,
        del_flag INTEGER NOT NULL,
        status INTEGER NOT NULL,
        product_type INTEGER
    )
    """,
    # del_flag / status 是运行时候选查询 CmsActivityMapper.xml:348-364 真正过滤的两列。
    # 替身库先前没有它们 ⇒ 活动腿的「已软删/已下架活动不许被误拦」在结构上不可观测。
    """
    CREATE TABLE cms_activity (
        id INTEGER PRIMARY KEY,
        topic_id INTEGER,
        member_id INTEGER,
        is_settlement INTEGER NOT NULL,
        del_flag INTEGER NOT NULL,
        status INTEGER NOT NULL
    )
    """,
    """
    CREATE TABLE cms_registration (
        id INTEGER PRIMARY KEY,
        owner_type INTEGER NOT NULL,
        owner_id INTEGER NOT NULL,
        payment_status INTEGER NOT NULL,
        registration_status INTEGER NOT NULL,
        payable_amount REAL NOT NULL
    )
    """,
    "CREATE TABLE ums_member (id INTEGER PRIMARY KEY)",
    """
    CREATE TABLE coop_order (
        id INTEGER PRIMARY KEY,
        topic_id INTEGER NOT NULL,
        invite_id INTEGER,
        status INTEGER NOT NULL,
        del_flag INTEGER NOT NULL
    )
    """,
    "CREATE TABLE coop_invite (id INTEGER PRIMARY KEY, invite_type INTEGER NOT NULL)",
)

TOPIC_COLUMNS = ("id", "member_id", "is_settlement", "del_flag", "status", "product_type")
ACTIVITY_COLUMNS = ("id", "topic_id", "member_id", "is_settlement", "del_flag", "status")

MEMBERS = ((900,), (901,), (902,))
# 900/901/902 都是活着的会员行；999 故意【不建行】——指向它 = 会员行不存在。
GHOST_MEMBER = 999

# 主题 300：主题腿的正常样本(有生效 coop 单 + 正销售 + 发布者 902 存在)
# 主题 100：活动腿的来源主题(发布者 900 存在)，活动 200 的现金收款人是 901
BASELINE_TOPICS = (
    (100, 900, 0, 0, 1, 1),
    (300, 902, 0, 0, 1, 1),
)
BASELINE_ACTIVITIES = ((200, 100, 901, 0, 0, 1),)
# 活动 210:没有来源主题(topic_id 为空)。运行时它照样走到现金那一跳,所以 guard 6 仍须管它。
NO_TOPIC_ACTIVITY = (210, None, 901, 0, 0, 1)
# 999 号主题不建行 —— 指向它 = 来源主题行已被硬删。
GHOST_TOPIC = 999
# owner_type=1 挂主题、owner_type=2 挂活动；均为「已付款 ∧ 未取消未退单 ∧ 金额 > 0」
BASELINE_REGISTRATIONS = (
    (5001, 1, 300, 2, 2, 199.0),
    (5002, 2, 200, 2, 2, 299.0),
)
BASELINE_COOP_ORDERS = ((7001, 300, None, 0, 0),)
BASELINE_COOP_INVITES = ()


def publisher_guard_statements(sql_text):
    """切出发布者前置闸那一段 + guard 表自身的建播删。

    分句器已经剥掉注释，所以注释里出现的表名不会把无关语句带进来。
    """
    kept = []
    for statement in _SPLITTER.split_statements(sql_text):
        statement = _SPLITTER.executable_statement(statement)
        lowered = statement.lower()
        if any(table in lowered for table in _OTHER_DOMAINS):
            continue
        if any(table in lowered for table in _PUBLISHER_TABLES) \
                or _GUARD_BOOTSTRAP.match(statement):
            kept.append(statement)
    return kept


def all_statements(sql_text):
    return [_SPLITTER.executable_statement(s)
            for s in _SPLITTER.split_statements(sql_text)]


def is_persistent_write(statement):
    """持久写 = 改真表的语句。写临时 guard/候选表不算，建临时表也不算。"""
    head = " ".join(statement.split()).upper()
    if head.startswith(("UPDATE ", "DELETE ", "REPLACE ")):
        return True
    if head.startswith("INSERT INTO "):
        target = head[len("INSERT INTO "):].split()[0].split("(")[0]
        return target.lower() not in _TEMP_TABLES
    return False


def to_sqlite(statement):
    for pattern, replacement in _MYSQLISMS:
        statement = pattern.sub(replacement, statement)
    return statement


class GuardTripped(Exception):
    """迁移撞 guard 停机（MySQL 上是主键冲突）。自带撞红的那条语句。"""

    def __init__(self, statement, cause):
        super(GuardTripped, self).__init__(str(cause))
        self.statement = statement


def replay(statements, topics=BASELINE_TOPICS, activities=BASELINE_ACTIVITIES,
           registrations=BASELINE_REGISTRATIONS, members=MEMBERS,
           coop_orders=BASELINE_COOP_ORDERS, coop_invites=BASELINE_COOP_INVITES):
    """在替身库上逐句跑；撞 guard → GuardTripped。"""
    connection = sqlite3.connect(":memory:")
    try:
        for ddl in SCHEMA:
            connection.execute(ddl)
        connection.executemany("INSERT INTO cms_topic VALUES (?,?,?,?,?,?)", topics)
        connection.executemany("INSERT INTO cms_activity VALUES (?,?,?,?,?,?)", activities)
        connection.executemany(
            "INSERT INTO cms_registration VALUES (?,?,?,?,?,?)", registrations)
        connection.executemany("INSERT INTO ums_member VALUES (?)", members)
        connection.executemany("INSERT INTO coop_order VALUES (?,?,?,?,?)", coop_orders)
        connection.executemany("INSERT INTO coop_invite VALUES (?,?)", coop_invites)
        for statement in statements:
            try:
                connection.execute(to_sqlite(statement))
            except sqlite3.IntegrityError as error:
                raise GuardTripped(statement, error)
    finally:
        connection.close()


def patched(rows, columns, row_id, **overrides):
    """改一行 fixture，其余原样。"""
    result = []
    for row in rows:
        record = dict(zip(columns, row))
        if record["id"] == row_id:
            record.update(overrides)
        result.append(tuple(record[column] for column in columns))
    return tuple(result)


def locate(statements, stage):
    hits = [index for index, text in enumerate(statements) if STAGES[stage](text)]
    if len(hits) != 1:
        raise AssertionError("阶段 %s 命中 %d 条语句，负控定位失效" % (stage, len(hits)))
    return hits[0]


def drop_guard(statements, stage):
    """整条摘掉某道闸——负控用。必须恰好命中一条，否则负控本身是假绿。"""
    index = locate(statements, stage)
    return statements[:index] + statements[index + 1:]


def drop_member_existence(statements, stage):
    """只摘掉「会员行必须存在」那半道，退化成修复前的"只判 member_id IS NULL"。"""
    index = locate(statements, stage)
    mutated, count = _MEMBER_EXISTS_CLAUSE.subn("", statements[index], count=1)
    if count != 1:
        raise AssertionError("阶段 %s 未摘到会员存在性判据，负控本身是假绿" % stage)
    return statements[:index] + [mutated] + statements[index + 1:]


def mutate_stage(statements, stage, pattern, replacement):
    """对某道闸做【真实变异】——摘谓词 / 放宽 / 收窄，各一条负控。

    必须恰好替换一处，否则变异没生效，负控本身是假绿。
    """
    index = locate(statements, stage)
    original = statements[index]
    hits = len(re.findall(pattern, original))
    if hits != 1:
        raise AssertionError(
            "阶段 %s 的变异点 %r 命中 %d 处，负控本身是假绿" % (stage, pattern, hits))
    mutated = re.sub(pattern, replacement, original, count=1)
    if mutated == original:
        raise AssertionError("阶段 %s 变异后与原文相同，负控本身是假绿" % stage)
    return statements[:index] + [mutated] + statements[index + 1:]


class H8PublisherGuardContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(MIGRATION, encoding="utf-8") as handle:
            cls.sql = handle.read()
        cls.statements = publisher_guard_statements(cls.sql)
        cls.every = all_statements(cls.sql)

    # ---- 观测面本身要先证明取对了，否则下面全是空跑恒真 ----

    def test_replay_scope_really_contains_the_three_publisher_guards(self):
        for stage in STAGES:
            with self.subTest(stage=stage):
                hits = [s for s in self.statements if STAGES[stage](s)]
                self.assertEqual(1, len(hits), "闸 %s 必须恰好命中一条语句" % stage)
        joined = " ".join(" ".join(s.split()) for s in self.statements)
        self.assertIn("INSERT INTO tandianri_h8_rate_guard (id) VALUES (1)", joined)
        # 依赖本替身库没有的表 / MySQL 专属语法的语句必须被排除掉
        self.assertNotIn("FORCE INDEX", joined)
        self.assertNotIn("settlement_rule", joined)
        self.assertNotIn("settlement_detail", joined)

    def test_三道发布者闸都排在任何持久写之前(self):
        """整份迁移的第一条持久写必须晚于三道闸——否则"停下"时账已经改了一半。"""
        writes = [i for i, s in enumerate(self.every) if is_persistent_write(s)]
        self.assertTrue(writes, "整份迁移里一条持久写都没认出来，断言恒真")
        first_write = writes[0]
        # STAGES 的判据只在发布者切片里互斥；整份文件里配对闸/候选表也引用这些表，
        # 故先在切片里定位，再按语句原文回到整份文件里取下标。
        for stage in STAGES:
            with self.subTest(stage=stage):
                guard = self.statements[locate(self.statements, stage)]
                self.assertIn(guard, self.every)
                self.assertLess(self.every.index(guard), first_write)

    def test_基线不误拦(self):
        """发布者齐全的正常存量必须绿——假红比缺闸更坏。"""
        replay(self.statements)

    # ---- 主控：主题腿(修复前完全没有这道闸) ----

    def test_负控_主题腿发布者为空必须红(self):
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=None)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, topics=topics)
        self.assertTrue(STAGES["topic_publisher"](caught.exception.statement))

    def test_负控_主题腿发布者会员行不存在必须红(self):
        """lockMemberById 只问"行在不在"，所以指向已被硬删的会员同样会硬抛。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=GHOST_MEMBER)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, topics=topics)
        self.assertTrue(STAGES["topic_publisher"](caught.exception.statement))

    def test_负控_主题腿发布者id非正必须红(self):
        """PointsAwardServiceImpl:110-112 对 memberId <= 0 直接抛。"""
        members = MEMBERS + ((0,),)
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=0)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, topics=topics, members=members)
        self.assertTrue(STAGES["topic_publisher"](caught.exception.statement))

    def test_负控_摘掉主题腿闸即复现缺陷本身(self):
        """摘掉新闸 = 修复前那份迁移：它会【全绿】，而这条主题部署后每轮回滚。"""
        mutated = drop_guard(self.statements, "topic_publisher")
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=None)
        replay(mutated, topics=topics)

    # ---- 主控：活动腿的来源主题发布者(修复前只判 IS NULL) ----

    def test_负控_活动腿来源主题发布者会员行不存在必须红(self):
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=GHOST_MEMBER)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, topics=topics)
        self.assertTrue(STAGES["activity_source_topic_publisher"](
            caught.exception.statement))

    def test_负控_摘掉活动腿存在性判据即复现缺陷本身(self):
        """退化成"只判 IS NULL" = 修复前那份迁移：非空但会员已删的主题被静默放行。"""
        mutated = drop_member_existence(
            self.statements, "activity_source_topic_publisher")
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=GHOST_MEMBER)
        replay(mutated, topics=topics)

    def test_活动腿来源主题发布者为空仍必须红(self):
        """既有判据不得被这次修改弄丢。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None)
        with self.assertRaises(GuardTripped):
            replay(self.statements, topics=topics)

    def test_活动腿现金收款人缺失仍必须红(self):
        """guard 6 是既有闸，本次修改不得削弱它。"""
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                             member_id=GHOST_MEMBER)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, activities=activities)
        self.assertTrue(STAGES["activity_cash_payee"](caught.exception.statement))

    # ---- 反向：运行时够不到的主题不许被误拦（假红比缺闸更坏）----

    def assertGreenWithMissingTopicPublisher(self, **overrides):
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300,
                         member_id=None, **overrides)
        replay(self.statements, topics=topics)

    def test_运行时够不到的_已结算主题不误拦(self):
        self.assertGreenWithMissingTopicPublisher(is_settlement=1)

    def test_运行时够不到的_已删除主题不误拦(self):
        self.assertGreenWithMissingTopicPublisher(del_flag=1)

    def test_运行时够不到的_已下架主题不误拦(self):
        self.assertGreenWithMissingTopicPublisher(status=0)

    def test_运行时够不到的_FREE_EXPLORE主题不误拦(self):
        """product_type=2 在候选查询 CmsTopicMapper:789-798 就被排掉。"""
        self.assertGreenWithMissingTopicPublisher(product_type=2)

    def test_运行时够不到的_product_type为空主题不误拦(self):
        """有销售时先撞 NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN，到不了发积分那一跳。"""
        self.assertGreenWithMissingTopicPublisher(product_type=None)

    def test_运行时够不到的_无正销售主题不误拦(self):
        """未付款 / 已取消的报名不计入 selectTotalSalesByTopicId 的销售口径。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=None)
        registrations = (
            (5001, 1, 300, 1, 2, 199.0),   # 未付款
            (5003, 1, 300, 2, 3, 199.0),   # 已取消 / 已退单
            (5004, 1, 300, 2, 2, 0.0),     # 零元
            (5002, 2, 200, 2, 2, 299.0),
        )
        replay(self.statements, topics=topics, registrations=registrations)

    def test_运行时够不到的_无生效coop单主题不误拦(self):
        """裁决第 7 条有意留下的那类：先撞 NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN。

        把这类也拦红 = 迁移永远跑不了，而它们本来就到不了发积分那一跳。
        """
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=None)
        replay(self.statements, topics=topics, coop_orders=())

    def test_运行时够不到的_coop单已取消或已删除不误拦(self):
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=None)
        replay(self.statements, topics=topics,
               coop_orders=((7001, 300, None, 1, 0), (7002, 300, None, 0, 1)))

    def test_运行时够不到的_仅历史邀约coop单不误拦(self):
        """selectEffectiveOrdersByTopicId 用 NOT EXISTS 排掉 invite_type=2 的历史单。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=None)
        replay(self.statements, topics=topics,
               coop_orders=((7003, 300, 8001, 0, 0),),
               coop_invites=((8001, 2),))

    def test_生效coop单的邀约不是历史类型时仍必须红(self):
        """上一条的对照组：invite_type 不是 2 ⇒ 单子生效 ⇒ 走得到发积分 ⇒ 必须红。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 300, member_id=None)
        with self.assertRaises(GuardTripped):
            replay(self.statements, topics=topics,
                   coop_orders=((7003, 300, 8001, 0, 0),),
                   coop_invites=((8001, 1),))

    # ---- 反向：活动腿运行时够不到的存量不许被误拦 ----
    #
    # 运行时可达集的真源(逐跳读出，不是照抄任何复审报告):
    #   ① 候选 CmsActivityMapper.xml:348-364 selectActivitiesForSettlement：
    #      activity.del_flag = 0 ∧ activity.status = 1 ∧ activity.is_settlement = 0
    #      ∧ end_date 已过 ∧ (topic_id 为空 或 来源主题 product_type <> 2)
    #   ② CmsActivityServiceImpl.settleActivity:191 来源主题 product_type = 2 ⇒ 先硬抛
    #   ③ 同文件 :197 claim 再要求 del_flag = 0
    #   ④ 同文件 :208-211 有销售且(来源主题行不存在 或 product_type 为空)⇒ 先硬抛
    #      ⇒ 现金(:219)与积分(:240)两跳都到不了
    # ⚠️ end_date 故意【不抄】：它只决定"哪一轮轮到它"，不决定可达性，与主题腿同口径。
    # ⚠️ 来源主题的 del_flag / status 同样【不抄】：settleActivity:190 取来源主题用的是
    #    CmsTopicMapper.xml:500-503 selectCmsTopicById(`where id = #{id}`，不带 del_flag/
    #    status 过滤)，候选查询的 NOT EXISTS 也只判 product_type = 2。⇒ 来源主题被软删/
    #    下架时活动腿【照样走得到】那两跳，拦它是对的。对应正向断言见下面两条"仍必须红"。

    def test_运行时够不到的_已软删活动不误拦_来源主题发布者(self):
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None)
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200, del_flag=1)
        replay(self.statements, topics=topics, activities=activities)

    def test_运行时够不到的_已下架活动不误拦_来源主题发布者(self):
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None)
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200, status=0)
        replay(self.statements, topics=topics, activities=activities)

    def test_运行时够不到的_FREE_EXPLORE来源主题不误拦(self):
        """product_type=2 在候选查询就被 NOT EXISTS 排掉，settleActivity:191 还要再抛一次。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100,
                         member_id=None, product_type=2)
        replay(self.statements, topics=topics)

    def test_运行时够不到的_product_type为空来源主题不误拦(self):
        """有销售时先撞 NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN，到不了发积分那一跳。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100,
                         member_id=None, product_type=None)
        replay(self.statements, topics=topics)

    def test_运行时够不到的_已软删活动不误拦_现金收款人(self):
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                             member_id=GHOST_MEMBER, del_flag=1)
        replay(self.statements, activities=activities)

    def test_运行时够不到的_已下架活动不误拦_现金收款人(self):
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                             member_id=GHOST_MEMBER, status=0)
        replay(self.statements, activities=activities)

    def test_运行时够不到的_FREE_EXPLORE来源主题不误拦_现金收款人(self):
        """现金(:219)排在 FREE_EXPLORE 硬抛(:191)之后，这类活动的现金也永远出不了账。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, product_type=2)
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                             member_id=GHOST_MEMBER)
        replay(self.statements, topics=topics, activities=activities)

    def test_运行时够不到的_product_type为空来源主题不误拦_现金收款人(self):
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, product_type=None)
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                             member_id=GHOST_MEMBER)
        replay(self.statements, activities=activities, topics=topics)

    def test_运行时够不到的_来源主题行已被硬删不误拦_现金收款人(self):
        """topic_id 指向已被硬删的主题：settleActivity:208-211 的 topic == null 分支先抛。"""
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                             member_id=GHOST_MEMBER, topic_id=GHOST_TOPIC)
        replay(self.statements, activities=activities)

    # ---- 正向：放宽不得过头（下面每一条今天都真走得到那一跳）----

    def test_来源主题已软删但发布者缺失仍必须红(self):
        """selectCmsTopicById 不带 del_flag 过滤 ⇒ 软删的来源主题照样发积分 ⇒ 必须拦。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None, del_flag=1)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, topics=topics)
        self.assertTrue(STAGES["activity_source_topic_publisher"](
            caught.exception.statement))

    def test_来源主题已下架但发布者缺失仍必须红(self):
        """候选查询过滤的是 activity.status，不是来源主题的 status。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None, status=0)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, topics=topics)
        self.assertTrue(STAGES["activity_source_topic_publisher"](
            caught.exception.statement))

    # ---- 变异负控：新收窄的每个谓词都必须是 load-bearing 的 ----
    #
    # 只加谓词不做变异 = 下一轮把它删掉照样全绿。三类变异各自回答一个问题：
    #   摘谓词 / 放宽 → 假红回来了吗?     收窄 → 会不会顺手放过一条【真走得到】的存量?

    def assertFalseRedReturns(self, stage, pattern, **fixtures):
        """摘掉某个可达性谓词后，本来该绿的存量必须重新变红（证明它真在挡假红）。"""
        replay(self.statements, **fixtures)          # 未变异：绿
        mutated = mutate_stage(self.statements, stage, pattern, "1 = 1")
        with self.assertRaises(GuardTripped) as caught:
            replay(mutated, **fixtures)
        self.assertTrue(STAGES[stage](caught.exception.statement))

    def test_负控_摘掉活动腿del_flag谓词_来源主题闸假红回来(self):
        self.assertFalseRedReturns(
            "activity_source_topic_publisher", r"activity\.del_flag = 0",
            topics=patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None),
            activities=patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200, del_flag=1))

    def test_负控_摘掉活动腿status谓词_来源主题闸假红回来(self):
        self.assertFalseRedReturns(
            "activity_source_topic_publisher", r"activity\.status = 1",
            topics=patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None),
            activities=patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200, status=0))

    def test_负控_摘掉来源主题product_type谓词_假红回来(self):
        self.assertFalseRedReturns(
            "activity_source_topic_publisher",
            r"topic\.product_type IS NOT NULL\s+AND topic\.product_type <> 2",
            topics=patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100,
                           member_id=None, product_type=2))

    def test_负控_摘掉现金腿del_flag谓词_假红回来(self):
        self.assertFalseRedReturns(
            "activity_cash_payee", r"activity\.del_flag = 0",
            activities=patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                               member_id=GHOST_MEMBER, del_flag=1))

    def test_负控_摘掉现金腿status谓词_假红回来(self):
        self.assertFalseRedReturns(
            "activity_cash_payee", r"activity\.status = 1",
            activities=patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                               member_id=GHOST_MEMBER, status=0))

    def test_负控_摘掉现金腿来源主题可达性分支_假红回来(self):
        """整段摘掉 = 修复前那份迁移：FREE_EXPLORE 来源主题的活动又被当场拦红。"""
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, product_type=2)
        activities = patched(BASELINE_ACTIVITIES, ACTIVITY_COLUMNS, 200,
                             member_id=GHOST_MEMBER)
        replay(self.statements, topics=topics, activities=activities)
        mutated = mutate_stage(
            self.statements, "activity_cash_payee",
            r"(?s)AND \(activity\.topic_id IS NULL.*?source_topic\.product_type <> 2\)\)\s+",
            "")
        with self.assertRaises(GuardTripped) as caught:
            replay(mutated, topics=topics, activities=activities)
        self.assertTrue(STAGES["activity_cash_payee"](caught.exception.statement))

    def test_负控_把来源主题收窄成必须未删即静默放过真存量(self):
        """收窄方向的负控：多加一条 `topic.del_flag = 0` 就会静默放过一条真走得到的存量。

        selectCmsTopicById 不带 del_flag 过滤 ⇒ 这条主题运行时照样走到 :240 硬抛。
        它证明「两条腿对称照抄」是错的，也把这条判据钉死，防下一轮按报告的建议加回来。
        """
        topics = patched(BASELINE_TOPICS, TOPIC_COLUMNS, 100, member_id=None, del_flag=1)
        with self.assertRaises(GuardTripped):
            replay(self.statements, topics=topics)
        mutated = mutate_stage(
            self.statements, "activity_source_topic_publisher",
            r"AND \(topic\.member_id IS NULL",
            "AND topic.del_flag = 0\n      AND (topic.member_id IS NULL")
        replay(mutated, topics=topics)               # 假绿：闸不再拦它

    def test_负控_把现金腿收窄成必须有来源主题即静默放过真存量(self):
        """收窄方向的负控：摘掉 `topic_id IS NULL OR` 就会放过无来源主题的活动。"""
        activities = (patched((NO_TOPIC_ACTIVITY,), ACTIVITY_COLUMNS, 210,
                              member_id=GHOST_MEMBER))
        registrations = BASELINE_REGISTRATIONS + ((5005, 2, 210, 2, 2, 299.0),)
        with self.assertRaises(GuardTripped):
            replay(self.statements, activities=activities, registrations=registrations)
        mutated = mutate_stage(
            self.statements, "activity_cash_payee",
            r"activity\.topic_id IS NULL\s+OR ", "")
        replay(mutated, activities=activities, registrations=registrations)

    def test_无来源主题的活动现金收款人缺失仍必须红(self):
        """topic_id 为空的活动跳过全部来源主题判据，直达现金那一跳 ⇒ guard 6 仍须管它。"""
        activities = (NO_TOPIC_ACTIVITY,)
        registrations = BASELINE_REGISTRATIONS + ((5005, 2, 210, 2, 2, 299.0),)
        activities = patched(activities, ACTIVITY_COLUMNS, 210, member_id=GHOST_MEMBER)
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, activities=activities, registrations=registrations)
        self.assertTrue(STAGES["activity_cash_payee"](caught.exception.statement))


if __name__ == "__main__":
    unittest.main(verbosity=2)

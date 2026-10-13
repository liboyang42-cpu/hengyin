#!/usr/bin/env python3
"""H8 费率收敛的【作用域】契约：真跑迁移自己的语句，不是读它的文本。

用法:python3 ci/test_migration_h8_rate_scope.py   (纯 stdlib,CI 直跑)

【为什么要这条契约】
2026-08-16 第四轮双轴复审同时点名:迁移只清 `merchant_id = 0` 的三条全局规则,
而运行时读的是 `getActiveRule(merchantId, bizType)` —— 先查 per-merchant 覆盖规则,
查不到才回落全局(SettlementRuleServiceImpl.getActiveRule:38-46 +
SettlementRuleMapper.xml:54-62,过滤恒为 status = 1 AND del_flag = 0)。
主题核销腿传的是真实商家 id(SettlementEntryServiceImpl:149),于是生产里任何一行
`merchant_id = 77, biz_type = 'topic', creator_rate = 0.20` 都:
  ① 迁移够不到 → 部署后仍是非零 creator_rate;
  ② 被写侧闸 SettlementEntryServiceImpl:227-234 硬抛 → 该商家名下主题票整批核不了销。
而迁移全绿、CI 全绿、`--dry-run` 全绿 —— 唯一的发现时机是现场停摆。

【本契约的观测方式:替身库真执行,不是 grep】
把迁移里【费率收敛那一段】的语句原样喂给 sqlite 跑一遍(只做两处 MySQL 方言替换,
见 _MYSQLISMS),因此断言的是【语句真实选中/改写了哪些行】,而不是「文本里有没有某个子串」。
guard 表的主键冲突在替身库上表现为 IntegrityError —— 与 MySQL 上撞主键停机同构。

【已知边界(故意不做,不得从本契约推导 PASS)】
  - 替身库是 sqlite,不是 MySQL 8.0.45:DECIMAL(10,4) 在这里是 REAL。
    迁移只做 `+` 与比较,所选 fixture 的浮点结果与十进制一致(用例逐条断言到 1e-9),
    但这【不证明】生产上的十进制行为,更不证明真库 PASS。
  - 只replay 引用 settlement_rule / 快照表的语句;明细搬钱、积分探针、FORCE INDEX
    那几条依赖本替身库没有的表与 MySQL 专属语法,不在本契约观测面内。
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

_TARGET_SCOPE = re.compile(r"biz_type IN \('activity', 'topic', 'topic_club'\)")
_OLD_SCOPE = "merchant_id = 0 AND biz_type IN ('activity', 'topic', 'topic_club')"

# 各阶段的定位判据：按语句【内容】认，不按下标认(下标会随文件编辑漂移)。
STAGES = {
    "prewrite_guard": lambda s: "platform_commission_rate IS NULL" in s,
    "snapshot": lambda s: "CREATE TEMPORARY TABLE tandianri_h8_rate_before" in s,
    "update": lambda s: s.lstrip().upper().startswith("UPDATE SETTLEMENT_RULE"),
    "count_readback": lambda s: "COUNT(*) FROM tandianri_h8_rate_before" in s,
    "endstate_readback": lambda s: "creator_rate IS NULL OR creator_rate <> 0" in s,
}

SCHEMA = """
CREATE TABLE settlement_rule (
    id INTEGER PRIMARY KEY,
    merchant_id INTEGER NOT NULL,
    biz_type TEXT NOT NULL,
    platform_commission_rate REAL,
    merchant_rate REAL,
    creator_rate REAL,
    inviter_rate REAL,
    status INTEGER NOT NULL,
    del_flag INTEGER NOT NULL
)
"""

# id / merchant_id / biz_type / platform / merchant / creator / inviter / status / del_flag
BASELINE_ROWS = (
    (1, 0, "activity", 0.10, 0.80, 0.10, 0.00, 1, 0),
    (2, 0, "topic", 0.03, 0.70, 0.20, 0.02, 1, 0),
    (3, 0, "topic_club", 0.08, 0.70, 0.15, 0.02, 1, 0),
    # ★ 任务点名的 per-merchant 覆盖规则：旧迁移够不到，部署后必被写侧闸拒。
    (4, 77, "topic", 0.03, 0.75, 0.20, 0.02, 1, 0),
    # 保护半径不得扩大：coupon 不在 H8 裁决范围，必须一字不动。
    (5, 0, "coupon", 0.10, 0.90, 0.00, 0.00, 1, 0),
    # 运行时够不到：停用 / 已删除的历史行不在动账范围，必须一字不动。
    (6, 88, "topic", 0.03, 0.70, 0.25, 0.02, 0, 0),
    (7, 99, "activity", 0.10, 0.60, 0.30, 0.00, 1, 1),
)

RULE_COLUMNS = ("id", "merchant_id", "biz_type", "platform_commission_rate",
                "merchant_rate", "creator_rate", "inviter_rate", "status", "del_flag")


def rate_scope_statements(sql_text):
    """切出费率收敛那一段：引用 settlement_rule / 快照表的语句 + guard 表自身的建播删。

    分句器已经剥掉注释，所以注释里出现的表名不会把无关语句带进来。
    """
    kept = []
    for statement in _SPLITTER.split_statements(sql_text):
        statement = _SPLITTER.executable_statement(statement)
        lowered = statement.lower()
        touches_rules = ("settlement_rule" in lowered
                         or "tandianri_h8_rate_before" in lowered)
        if touches_rules or _GUARD_BOOTSTRAP.match(statement):
            kept.append(statement)
    return kept


def to_sqlite(statement):
    for pattern, replacement in _MYSQLISMS:
        statement = pattern.sub(replacement, statement)
    return statement


class GuardTripped(Exception):
    """迁移撞 guard 停机（MySQL 上是主键冲突）。自带撞红【当时】的 settlement_rule 快照。"""

    def __init__(self, statement, rules, cause):
        super(GuardTripped, self).__init__(str(cause))
        self.statement = statement
        self.rules = rules


def replay(statements, rows):
    """在替身库上逐句跑，返回终态 settlement_rule；撞 guard → GuardTripped。

    逐句执行(而不是一次 executescript)是为了在撞红的那一刻回读表状态 ——
    「闸在写之前」这句话只有这样才有证据，否则只能靠读代码相信。
    """
    connection = sqlite3.connect(":memory:")
    try:
        connection.execute(SCHEMA)
        connection.executemany(
            "INSERT INTO settlement_rule VALUES (?,?,?,?,?,?,?,?,?)", rows)
        for statement in statements:
            try:
                connection.execute(to_sqlite(statement))
            except sqlite3.IntegrityError as error:
                raise GuardTripped(statement, read_rules(connection), error)
        return read_rules(connection)
    finally:
        connection.close()


def read_rules(connection):
    cursor = connection.execute(
        "SELECT %s FROM settlement_rule ORDER BY id" % ", ".join(RULE_COLUMNS))
    return {row[0]: dict(zip(RULE_COLUMNS, row)) for row in cursor.fetchall()}


def narrow_to_old_scope(statements, stages):
    """把指定阶段的目标集写回旧作用域(只清 merchant_id = 0)——负控用。

    命名的阶段必须恰好命中一条语句并真的发生替换，否则负控本身是假绿。
    """
    mutated = list(statements)
    for stage in stages:
        matcher = STAGES[stage]
        hits = [index for index, text in enumerate(mutated) if matcher(text)]
        if len(hits) != 1:
            raise AssertionError("阶段 %s 命中 %d 条语句，负控定位失效" % (stage, len(hits)))
        index = hits[0]
        replaced, count = _TARGET_SCOPE.subn(_OLD_SCOPE, mutated[index], count=1)
        if count != 1:
            raise AssertionError("阶段 %s 未发生作用域替换，负控本身是假绿" % stage)
        mutated[index] = replaced
    return mutated


def with_rate(rows, rule_id, **overrides):
    """改一行 fixture 的费率，其余原样。"""
    patched = []
    for row in rows:
        record = dict(zip(RULE_COLUMNS, row))
        if record["id"] == rule_id:
            record.update(overrides)
        patched.append(tuple(record[column] for column in RULE_COLUMNS))
    return tuple(patched)


class H8RateScopeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(MIGRATION, encoding="utf-8") as handle:
            cls.sql = handle.read()
        cls.statements = rate_scope_statements(cls.sql)

    def assertRate(self, rule, field, expected):
        self.assertAlmostEqual(expected, rule[field], delta=1e-9,
                               msg="%s 期望 %s，实得 %s" % (field, expected, rule[field]))

    def assertUnchanged(self, rules, rule_id):
        original = dict(zip(RULE_COLUMNS,
                            next(r for r in BASELINE_ROWS if r[0] == rule_id)))
        for field in ("platform_commission_rate", "merchant_rate",
                      "creator_rate", "inviter_rate"):
            self.assertRate(rules[rule_id], field, original[field])

    # ---- 观测面本身要先证明取对了，否则下面全是空跑恒真 ----

    def test_replay_scope_really_contains_the_convergence_statements(self):
        joined = " ".join(" ".join(s.split()) for s in self.statements)
        self.assertIn(
            "UPDATE settlement_rule SET merchant_rate = merchant_rate + creator_rate",
            joined)
        self.assertIn("CREATE TEMPORARY TABLE tandianri_h8_rate_before", joined)
        self.assertIn("INSERT INTO tandianri_h8_rate_guard (id) VALUES (1)", joined)
        # 依赖本替身库没有的表 / MySQL 专属语法的语句必须被排除掉
        self.assertNotIn("FORCE INDEX", joined)
        self.assertNotIn("settlement_detail", joined)
        self.assertNotIn("cms_registration", joined)

    def test_guard_mechanism_can_actually_go_red(self):
        """三条全局 live 规则的存在且唯一是业务基线：少一条必须在写前红。"""
        without_global_topic = tuple(r for r in BASELINE_ROWS if r[0] != 2)
        with self.assertRaises(GuardTripped):
            replay(self.statements, without_global_topic)

    # ---- 正向：全体运行时可达目标都被纳入并安全配平 ----

    def test_per_merchant_topic_rule_77_is_converged_and_balanced(self):
        rules = replay(self.statements, BASELINE_ROWS)
        rule = rules[4]
        self.assertRate(rule, "creator_rate", 0.0)
        self.assertRate(rule, "merchant_rate", 0.95)          # 0.75 + 0.20
        self.assertRate(rule, "platform_commission_rate", 0.03)
        self.assertRate(rule, "inviter_rate", 0.02)
        self.assertAlmostEqual(
            1.00,
            rule["platform_commission_rate"] + rule["merchant_rate"]
            + rule["creator_rate"] + rule["inviter_rate"], delta=1e-9)

    def test_three_global_rules_are_converged_with_sum_preserved(self):
        rules = replay(self.statements, BASELINE_ROWS)
        for rule_id, before in ((1, 0.80 + 0.10), (2, 0.70 + 0.20), (3, 0.70 + 0.15)):
            with self.subTest(rule=rule_id):
                original = dict(zip(RULE_COLUMNS,
                                    next(r for r in BASELINE_ROWS if r[0] == rule_id)))
                self.assertRate(rules[rule_id], "creator_rate", 0.0)
                self.assertRate(rules[rule_id], "merchant_rate", before)
                self.assertRate(rules[rule_id], "platform_commission_rate",
                                original["platform_commission_rate"])
                self.assertRate(rules[rule_id], "inviter_rate", original["inviter_rate"])

    def test_coupon_and_inactive_and_deleted_rules_are_untouched(self):
        rules = replay(self.statements, BASELINE_ROWS)
        for rule_id in (5, 6, 7):
            with self.subTest(rule=rule_id):
                self.assertUnchanged(rules, rule_id)

    # ---- 负控：旧作用域 / 遗漏目标 ----

    def test_负控_旧作用域只清全局时回读必须红(self):
        mutated = narrow_to_old_scope(self.statements, ("snapshot", "update"))
        with self.assertRaises(GuardTripped):
            replay(mutated, BASELINE_ROWS)

    def test_负控_连计数回读一起写窄时终态回读仍须独立打红(self):
        mutated = narrow_to_old_scope(
            self.statements, ("snapshot", "update", "count_readback"))
        with self.assertRaises(GuardTripped):
            replay(mutated, BASELINE_ROWS)

    def test_负控_整份退回旧作用域即复现缺陷本身(self):
        """全部写窄 = 修复前那份迁移：它会全绿，而 77 号规则原封不动地留在生产里。"""
        mutated = narrow_to_old_scope(
            self.statements,
            ("prewrite_guard", "snapshot", "update", "count_readback",
             "endstate_readback"))
        rules = replay(mutated, BASELINE_ROWS)
        self.assertRate(rules[4], "creator_rate", 0.20)
        self.assertRate(rules[4], "merchant_rate", 0.75)
        self.assertRate(rules[2], "creator_rate", 0.0)

    # ---- 负控：无法安全配平的目标必须在【写前】红 ----

    def assertRefusedBeforeAnyWrite(self, rows):
        with self.assertRaises(GuardTripped) as caught:
            replay(self.statements, rows)
        # 【写前】的证据不是读代码，是撞红那一刻回读出来的表状态：逐行仍是原值。
        at_failure = caught.exception.rules
        self.assertTrue(STAGES["prewrite_guard"](caught.exception.statement),
                        "撞红的不是写前闸，说明已经先写过账了")
        for row in rows:
            original = dict(zip(RULE_COLUMNS, row))
            self.assertEqual(original, at_failure[original["id"]])

    def test_负控_creator_rate为NULL必须在写前红(self):
        self.assertRefusedBeforeAnyWrite(with_rate(BASELINE_ROWS, 4, creator_rate=None))

    def test_负控_merchant_rate为NULL必须在写前红(self):
        self.assertRefusedBeforeAnyWrite(with_rate(BASELINE_ROWS, 4, merchant_rate=None))

    def test_负控_负费率必须在写前红(self):
        self.assertRefusedBeforeAnyWrite(with_rate(BASELINE_ROWS, 4, creator_rate=-0.01))

    def test_负控_四项和超过1必须在写前红(self):
        self.assertRefusedBeforeAnyWrite(with_rate(BASELINE_ROWS, 4, merchant_rate=0.99))

    def test_NULL不会被静默合并成NULL_merchant_rate(self):
        """P3-1:`merchant_rate + NULL` 会静默写成 NULL,而 `<>` 对 NULL 恒不成立。

        把写前闸摘掉即复现该静默路径 —— 终态回读必须仍然抓得住它。
        """
        rows = with_rate(BASELINE_ROWS, 4, creator_rate=None)
        without_prewrite = [s for s in self.statements
                            if not STAGES["prewrite_guard"](s)]
        with self.assertRaises(GuardTripped):
            replay(without_prewrite, rows)


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""H8 费率迁移引用 cms_registration 的列必须真实存在(mapper resultMap 为权威列表)。

用法:python3 ci/test_migration_h8_registration_columns.py   (纯 stdlib,CI 直跑)

【为什么要这条契约】
2026-08-16 第三轮复审抓到:migration_20260815_tandianri_h8_rate_convergence.sql 的两道存量
guard 写了 registration.pay_status / registration.refund_status / registration.amount ——
cms_registration 上这三列【都不存在】,ORDER 2/2 必报 ERROR 1054,整份迁移一句持久写都跑不完。
而当时 125 条 Python 契约全绿:--dry-run 只证明分句与形状,migration_gates 只看语句形状与
WHERE 边界,【没有任何一层把 SQL 里的列名与 mapper 对齐】。
同一个错误本仓 2026 年已经踩过一次 —— migration_settlement_resui_2fix.sql:37 的历史警告注释
原文就写着「原稿用 amount/pay_status=1 是错的」。留了教训、没留门禁,于是又踩一遍。

【范围故意最小】只锁本迁移引用的 cms_registration 列,不做全仓 schema 对账。
【已知边界】权威列表 = CmsRegistrationMapper 的 resultMap。表上存在但没进 resultMap 的列
(如 del_flag)会被判红 —— 这是有意的 fail-closed:宁可让作者回来确认一次,不要静默放行。
"""
import os
import re
import unittest

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATION = os.path.join(
    REPO, "chengyinhub-admin", "sql",
    "migration_20260815_tandianri_h8_rate_convergence.sql")
MAPPER = os.path.join(
    REPO, "chengyinhub-system", "src", "main", "resources", "mapper", "business",
    "CmsRegistrationMapper.xml")

_LINE_COMMENT = re.compile(r"--[^\n]*")
_RESULT_MAP = re.compile(
    r"<resultMap\b[^>]*\bid=\"CmsRegistrationResult\"[^>]*>(.*?)</resultMap>", re.DOTALL)
_MAPPED_COLUMN = re.compile(r"column=\"([A-Za-z_][A-Za-z0-9_]*)\"")
# cms_registration 后面【必须】跟别名,否则本契约核不到列 —— 核不到就报缺陷,不静默跳过。
_ALIAS_STOPWORD = (r"(?!(?:ON|WHERE|USING|SET|JOIN|LEFT|RIGHT|INNER|CROSS|STRAIGHT_JOIN"
                   r"|NATURAL|GROUP|ORDER|HAVING|LIMIT|UNION|AND|OR)\b)")
_REGISTRATION_SOURCE = re.compile(
    r"\b(?:FROM|JOIN)\s+cms_registration\b"
    r"(?:\s+(?:AS\s+)?(?P<alias>" + _ALIAS_STOPWORD + r"[A-Za-z_][A-Za-z0-9_]*))?",
    re.IGNORECASE)


def mapped_registration_columns(mapper_xml):
    """cms_registration 的权威列集合 = CmsRegistrationResult resultMap 的 column 列表。"""
    block = _RESULT_MAP.search(mapper_xml)
    if block is None:
        raise ValueError("CmsRegistrationMapper.xml 里找不到 CmsRegistrationResult resultMap")
    return set(_MAPPED_COLUMN.findall(block.group(1)))


def unknown_registration_columns(sql_text, mapped_columns):
    """返回 SQL 里引用了、但 cms_registration 上不存在的列名(排序去重)。

    别名缺失时返回哨兵 '<cms_registration 未取别名>' —— 契约无法核列即视为不通过。
    """
    sql = _LINE_COMMENT.sub("", sql_text)
    aliases = set()
    problems = set()
    for source in _REGISTRATION_SOURCE.finditer(sql):
        alias = source.group("alias")
        if alias is None:
            problems.add("<cms_registration 未取别名>")
        else:
            aliases.add(alias)
    for alias in aliases:
        referenced = re.findall(
            r"\b" + re.escape(alias) + r"\.([A-Za-z_][A-Za-z0-9_]*)", sql)
        problems.update(column for column in referenced if column not in mapped_columns)
    return sorted(problems)


class H8RegistrationColumnContractTest(unittest.TestCase):
    def setUp(self):
        with open(MAPPER, encoding="utf-8") as handle:
            self.mapped = mapped_registration_columns(handle.read())
        with open(MIGRATION, encoding="utf-8") as handle:
            self.sql = handle.read()

    def test_mapper_result_map_is_the_authoritative_column_list(self):
        # 权威列表本身要先证明取对了,否则下面的比对可能是空集恒真。
        self.assertIn("payment_status", self.mapped)
        self.assertIn("registration_status", self.mapped)
        self.assertIn("payable_amount", self.mapped)
        self.assertNotIn("pay_status", self.mapped)
        self.assertNotIn("refund_status", self.mapped)
        self.assertNotIn("amount", self.mapped)

    def test_h8_rate_convergence_only_references_real_registration_columns(self):
        self.assertEqual([], unknown_registration_columns(self.sql, self.mapped))

    def test_负控_旧列名复发必须被判红(self):
        # 把第三轮实际踩到的那三个列名注入回去 —— 门禁必须逐个点名,不能只报「有问题」。
        mutated = (self.sql
                   .replace("registration.payment_status = 2", "registration.pay_status = 1")
                   .replace("registration.registration_status = 2", "registration.refund_status = 0")
                   .replace("registration.payable_amount > 0", "registration.amount > 0"))
        self.assertNotEqual(self.sql, mutated, "变异未生效,负控本身是假绿")
        self.assertEqual(
            ["amount", "pay_status", "refund_status"],
            unknown_registration_columns(mutated, self.mapped))

    def test_负控_cms_registration_不取别名时契约必须判红而不是静默跳过(self):
        self.assertEqual(
            ["<cms_registration 未取别名>"],
            unknown_registration_columns(
                "SELECT 1 FROM cms_registration WHERE pay_status = 1", self.mapped))


if __name__ == "__main__":
    unittest.main(verbosity=2)

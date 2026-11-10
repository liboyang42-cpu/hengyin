"""qx-c1 #2 迁移门禁：新建表不得隐式落成 utf8mb4_0900_ai_ci。

MySQL 8 上 `DEFAULT CHARSET=utf8mb4` 不写 COLLATE 会落成 utf8mb4_0900_ai_ci，与库内既有
utf8mb4_general_ci 表做字符串 join 时报 1267 Illegal mix of collations
（GiftEntitlementMapper：cms_registration.parent_order_no = marketing_product_order.order_no）。

判据：
- 2026-11-07 起（Questo 批）新增迁移里每个没写表级 COLLATE 的 CREATE TABLE，都必须被
  migration_zz_questo_collation_align_20261109.sql 对齐（表默认 + 列），并在依赖台账里声明对其建表迁移的依赖；
- 对齐迁移之后再新建的表必须自己写 COLLATE（不再靠追加对齐文件兜底）；
- 对齐迁移只改列，不用 CONVERT TO（那会把显式 utf8mb4_bin / ascii_bin 的哈希、键列一起改掉）。

用法：python3 -m unittest -v ci/test_questo_collation_alignment.py
"""
import json
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SQL = os.path.join(ROOT, "chengyinhub-admin", "sql")
ALIGN = "migration_zz_questo_collation_align_20261109.sql"
ALIGN_STEM = ALIGN[len("migration_"):-len(".sql")]
# Questo 批的第一份迁移日期；更早的迁移已在主干/生产，不属于本门禁。
SINCE = "20261107"

CREATE = re.compile(r"create\s+table\s+(?:if\s+not\s+exists\s+)?`?(\w+)`?\s*\(", re.I)
DATE = re.compile(r"(20\d{6})")


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"(?m)--[^\n]*$", " ", text)


def created_tables(text):
    """[(table, table_options_after_closing_paren)]"""
    text = _strip_comments(text)
    out = []
    for match in CREATE.finditer(text):
        i, depth, quote = match.end(), 1, None
        while depth and i < len(text):
            ch = text[i]
            if quote:
                if ch == quote:
                    quote = None
            elif ch in "'\"`":
                quote = ch
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        end = text.find(";", i)
        out.append((match.group(1), text[i:end if end >= 0 else len(text)]))
    return out


def migration_date(name):
    found = DATE.search(name)
    return found.group(1) if found else ""


class QuestoCollationAlignmentTest(unittest.TestCase):
    def setUp(self):
        self.files = sorted(f for f in os.listdir(SQL) if f.startswith("migration_") and f.endswith(".sql"))
        with open(os.path.join(SQL, ALIGN), encoding="utf-8") as fh:
            self.align = fh.read()
        with open(os.path.join(ROOT, "ci", "migration-dependencies.json"), encoding="utf-8") as fh:
            self.deps = json.load(fh)["dependencies"]
        self.aligned = set(re.findall(r"ALTER TABLE `(\w+)`\n  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci",
                                      self.align))

    def implicit_tables(self):
        found = {}
        for name in self.files:
            if name == ALIGN or migration_date(name) < SINCE:
                continue
            with open(os.path.join(SQL, name), encoding="utf-8") as fh:
                for table, options in created_tables(fh.read()):
                    if not re.search(r"\bcollate\b", options, re.I):
                        found.setdefault(table, set()).add(name)
        return found

    def test_every_implicit_collation_table_is_aligned_and_ordered(self):
        implicit = self.implicit_tables()
        self.assertTrue(implicit, "negative control: the Questo batch really created implicit-collation tables")
        missing = sorted(t for t in implicit if t not in self.aligned)
        self.assertEqual([], missing, "新表没写 COLLATE（MySQL 8 默认 0900_ai_ci，join 旧表会 1267）")
        declared = set(self.deps.get(ALIGN_STEM, []))
        undeclared = sorted({f[len("migration_"):-len(".sql")] for files in implicit.values() for f in files} - declared)
        self.assertEqual([], undeclared, "对齐迁移必须声明依赖于建表迁移，保证先建后改")

    def test_align_migration_never_converts_whole_tables(self):
        self.assertNotRegex(self.align, r"(?i)convert\s+to\s+character\s+set",
                            "CONVERT TO 会改掉显式 utf8mb4_bin/ascii_bin 的哈希、键列")
        for line in re.findall(r"(?m)^  MODIFY COLUMN .*$", self.align):
            self.assertIn("CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", line)
            self.assertNotRegex(line, r"(?i)_bin\b", line)
        self.assertIn("parent_order_no' AND collation_name = 'utf8mb4_general_ci'", self.align,
                      "目标排序规则必须以既有 cms_registration.parent_order_no 为锚点预检")
        self.assertIn("ALTER TABLE `marketing_product_order`", self.align)
        self.assertRegex(self.align, r"MODIFY COLUMN `order_no` varchar\(\d+\) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")

    def test_tables_created_after_the_align_migration_declare_their_collation(self):
        later = [f for f in self.files if f != ALIGN and migration_date(f) > "20261109"]
        offenders = []
        for name in later:
            with open(os.path.join(SQL, name), encoding="utf-8") as fh:
                for table, options in created_tables(fh.read()):
                    if not re.search(r"\bcollate\b", options, re.I):
                        offenders.append("%s:%s" % (name, table))
        self.assertEqual([], offenders, "新建表必须写表级 COLLATE（与库内既有表一致：utf8mb4_general_ci）")

    def test_parser_sees_an_implicit_create_table(self):
        sample = "CREATE TABLE IF NOT EXISTS t (a VARCHAR(8) COMMENT 'x(y)') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
        self.assertEqual([("t", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")], created_tables(sample))


if __name__ == "__main__":
    unittest.main()

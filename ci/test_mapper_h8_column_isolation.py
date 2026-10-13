#!/usr/bin/env python3
"""H8 新列的【mapper 隔离】契约：通用 SQL 不许碰，H8 专用 SQL 必须全带且只有一个调用方。

用法:python3 ci/test_mapper_h8_column_isolation.py   (纯 stdlib,CI 直跑)

【为什么要这条契约】
2026-08-16 第五轮 Standards P2-1:H8 把 5 个新列
  cms_points_setting.rule_version
  ums_member_points_detail.points_rule_id / points_rule_version / points_rule_value
  ums_member_points_detail.publisher_settlement_topic_id
【无条件】写进了两个**共享** mapper 的通用语句(selectXxxVo / updateXxx /
insertUmsMemberPointsDetailWithBusinessKey)。这 5 列只由
`chengyinhub-admin/sql/migration_20260815_tandianri_h8_publisher_points_funds.sql` 建，
而「合并 PR 即自动部署 jar」，迁移落库却是**人工步骤** ⇒ 顺序一反(jar 先上):
  - 通用积分事件 11 到店核销 / 12 节点打卡 / 13 通关主题的 `award()`、
  - 既存的核销奖励 `CmsRegistrationServiceImpl.awardPointsOnce`、
  - 小程序积分明细页、后台积分规则列表与导出
全部 ERROR 1054 —— 打击面是**全站积分域 + 核销接口**，与数据无关，纯结构性。

【本契约锁什么】
  ① 通用语句一个 H8 列都不许引用(含 `<if>` 里的条件引用);
  ② H8 专用语句必须【完整】引用它那一份列，且这 5 列合起来一个不漏;
  ③ 每个 H8 专用 mapper 方法在 main 源码里【只有一个调用方】=
     PointsAwardServiceImpl(唯一 H8 服务路径);
  ④ 通用的 insertUmsMemberPointsDetailWithBusinessKey 反过来【不许】出现在 H8 路径里 ——
     否则等于把新列又塞回通用语句。
每条都配了摘列 / 塞列 / 加调用方的负控用例，摘任一条都会红。

【已知边界(不得从本契约推导 PASS)】
  - 这是【静态】契约:它证明 SQL 文本里没有对新列的引用，不证明真 MySQL 上跑得通;
  - 它不管 DDL 是否已落库，只管「jar 不会因为迁移没跑就在通用路径上 1054」;
  - H8 路径在缺列时【仍然】必须响亮 1054 —— 那正是 ② 要保证的，本契约不允许把它降级。
"""
import os
import re
import unittest
import xml.etree.ElementTree as ElementTree

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPPER_DIR = os.path.join(
    REPO, "chengyinhub-system", "src", "main", "resources", "mapper", "business")

POINTS_SETTING_MAPPER = "CmsPointsSettingMapper.xml"
POINTS_DETAIL_MAPPER = "UmsMemberPointsDetailMapper.xml"

# 这 5 列只由 ORDER 1/2 建；通用语句引用它们 = jar 先于迁移就全站 1054。
H8_COLUMNS = {
    POINTS_SETTING_MAPPER: ("rule_version",),
    POINTS_DETAIL_MAPPER: ("points_rule_id", "points_rule_version",
                           "points_rule_value", "publisher_settlement_topic_id"),
}

# H8 专用语句 → 它必须【完整】引用的列。两份合起来必须覆盖全部 5 列。
H8_STATEMENTS = {
    POINTS_SETTING_MAPPER: {
        "selectPublisherSettlementRules": ("rule_version",),
    },
    POINTS_DETAIL_MAPPER: {
        "insertPublisherSettlementPointsDetail": (
            "points_rule_id", "points_rule_version",
            "points_rule_value", "publisher_settlement_topic_id"),
    },
}

# 唯一 H8 服务路径。H8 专用 mapper 方法只能出现在这里。
H8_SERVICE_PATH = os.path.join(
    "chengyinhub-system", "src", "main", "java", "com", "chengyinhub",
    "business", "service", "impl", "PointsAwardServiceImpl.java")

# 通用写入口：核销奖励积分(awardPointsOnce)用它，H8 路径不许再用它。
GENERIC_BUSINESS_KEY_INSERT = "insertUmsMemberPointsDetailWithBusinessKey"
GENERIC_BUSINESS_KEY_CALLER = os.path.join(
    "chengyinhub-system", "src", "main", "java", "com", "chengyinhub",
    "business", "service", "impl", "CmsRegistrationServiceImpl.java")

MAIN_JAVA_ROOTS = (
    os.path.join("chengyinhub-admin", "src", "main", "java"),
    os.path.join("chengyinhub-system", "src", "main", "java"),
    os.path.join("chengyinhub-framework", "src", "main", "java"),
    os.path.join("chengyinhub-quartz", "src", "main", "java"),
    os.path.join("chengyinhub-common", "src", "main", "java"),
)

_STATEMENT_TAGS = ("select", "insert", "update", "delete")


def _element_sql(element, fragments):
    """把一条语句摊平成纯 SQL 文本，`<include refid>` 就地展开。

    只取文本节点，不取 test= 之类的属性 —— ERROR 1054 只由文本里的列名引发。
    """
    parts = []
    if element.text:
        parts.append(element.text)
    for child in element:
        if child.tag == "include":
            parts.append(fragments.get(child.get("refid"), ""))
        else:
            parts.append(_element_sql(child, fragments))
        if child.tail:
            parts.append(child.tail)
    return " ".join(parts)


def load_statements(mapper_file):
    """返回 {statement id: 展开后的 SQL 文本}。"""
    root = ElementTree.parse(os.path.join(MAPPER_DIR, mapper_file)).getroot()
    fragments = {}
    for node in root.findall("sql"):
        fragments[node.get("id")] = _element_sql(node, {})
    statements = {}
    for node in root:
        if node.tag in _STATEMENT_TAGS:
            statements[node.get("id")] = _element_sql(node, fragments)
    return statements


def references(sql_text, column):
    return re.search(r"\b%s\b" % re.escape(column), sql_text) is not None


def assert_generic_statements_avoid_h8_columns(mapper_file, statements):
    h8_ids = set(H8_STATEMENTS[mapper_file])
    offenders = []
    for statement_id, sql_text in sorted(statements.items()):
        if statement_id in h8_ids:
            continue
        for column in H8_COLUMNS[mapper_file]:
            if references(sql_text, column):
                offenders.append("%s 引用了 %s" % (statement_id, column))
    if offenders:
        raise AssertionError(
            "%s 的通用语句引用了 H8 新列(迁移未落库即 ERROR 1054):%s"
            % (mapper_file, "; ".join(offenders)))


def assert_h8_statements_reference_their_columns(mapper_file, statements):
    missing = []
    for statement_id, columns in sorted(H8_STATEMENTS[mapper_file].items()):
        if statement_id not in statements:
            raise AssertionError("%s 缺少 H8 专用语句 %s" % (mapper_file, statement_id))
        for column in columns:
            if not references(statements[statement_id], column):
                missing.append("%s 少了 %s" % (statement_id, column))
    if missing:
        raise AssertionError(
            "%s 的 H8 专用语句必须完整引用快照列(少一列即静默降级成 null):%s"
            % (mapper_file, "; ".join(missing)))


def java_files():
    found = []
    for root in MAIN_JAVA_ROOTS:
        base = os.path.join(REPO, root)
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                if name.endswith(".java"):
                    found.append(os.path.join(dirpath, name))
    return found


def callers_of(method, files_text):
    """只认【调用】(`xxx.method(`)，不认接口里的那条声明 —— 否则 mapper 接口自己恒被算成调用方。"""
    invocation = re.compile(r"\.\s*%s\s*\(" % re.escape(method))
    return sorted(path for path, text in files_text.items() if invocation.search(text))


def assert_single_h8_caller(method, files_text):
    found = callers_of(method, files_text)
    if found != [H8_SERVICE_PATH]:
        raise AssertionError(
            "H8 专用 mapper 方法 %s 的调用方必须恰好是 %s，实得 %s"
            % (method, H8_SERVICE_PATH, found))


class MapperH8ColumnIsolationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.statements = {name: load_statements(name) for name in H8_COLUMNS}
        cls.files_text = {}
        for path in java_files():
            with open(path, encoding="utf-8") as handle:
                cls.files_text[os.path.relpath(path, REPO)] = handle.read()

    # ---- 观测面本身要先证明取对了，否则下面全是空跑恒真 ----

    def test_parser_really_sees_the_shared_mappers(self):
        for mapper_file, expected in (
                (POINTS_SETTING_MAPPER,
                 ("selectCmsPointsSettingList", "selectCmsPointsSettingById",
                  "insertCmsPointsSetting", "updateCmsPointsSetting")),
                (POINTS_DETAIL_MAPPER,
                 ("selectUmsMemberPointsDetailList", "selectUmsMemberPointsDetailById",
                  "insertUmsMemberPointsDetail", GENERIC_BUSINESS_KEY_INSERT,
                  "updateUmsMemberPointsDetail"))):
            with self.subTest(mapper=mapper_file):
                statements = self.statements[mapper_file]
                for statement_id in expected:
                    self.assertIn(statement_id, statements)
                # `<include refid>` 必须真展开：Vo 里的列名要能在语句文本里看见
                probe = "member_id" if mapper_file == POINTS_DETAIL_MAPPER else "event_type"
                self.assertTrue(references(statements[expected[1]], probe),
                                "include 未展开，整份契约会是空跑恒真")

    def test_five_h8_columns_are_all_covered_by_dedicated_statements(self):
        covered = set()
        for mapper_file, spec in H8_STATEMENTS.items():
            for columns in spec.values():
                covered.update(columns)
        expected = set()
        for columns in H8_COLUMNS.values():
            expected.update(columns)
        self.assertEqual(expected, covered, "H8 专用语句必须覆盖全部 5 个新列")

    def test_java_scan_really_reads_the_repository(self):
        self.assertIn(H8_SERVICE_PATH, self.files_text)
        self.assertIn(GENERIC_BUSINESS_KEY_CALLER, self.files_text)
        self.assertGreater(len(self.files_text), 200)

    # ---- ① 通用语句一个 H8 列都不许引用 ----

    def test_generic_statements_never_reference_h8_columns(self):
        for mapper_file in H8_COLUMNS:
            with self.subTest(mapper=mapper_file):
                assert_generic_statements_avoid_h8_columns(
                    mapper_file, self.statements[mapper_file])

    def test_负控_把新列塞回通用语句必须红(self):
        cases = ((POINTS_SETTING_MAPPER, "updateCmsPointsSetting",
                  " rule_version = rule_version + 1,"),
                 (POINTS_DETAIL_MAPPER, "selectUmsMemberPointsDetailList",
                  " , publisher_settlement_topic_id "))
        for mapper_file, statement_id, injected in cases:
            with self.subTest(mapper=mapper_file):
                mutated = dict(self.statements[mapper_file])
                self.assertIn(statement_id, mutated, "变异目标不存在，负控本身是假绿")
                mutated[statement_id] = mutated[statement_id] + injected
                with self.assertRaises(AssertionError):
                    assert_generic_statements_avoid_h8_columns(mapper_file, mutated)

    # ---- ② H8 专用语句必须完整引用 ----

    def test_h8_statements_reference_every_snapshot_column(self):
        for mapper_file in H8_STATEMENTS:
            with self.subTest(mapper=mapper_file):
                assert_h8_statements_reference_their_columns(
                    mapper_file, self.statements[mapper_file])

    def test_负控_摘掉H8专用语句里任一列必须红(self):
        for mapper_file, spec in H8_STATEMENTS.items():
            for statement_id, columns in spec.items():
                for column in columns:
                    with self.subTest(mapper=mapper_file, column=column):
                        mutated = dict(self.statements[mapper_file])
                        stripped = re.sub(r"\b%s\b" % re.escape(column), "removed_column",
                                          mutated[statement_id])
                        self.assertNotEqual(mutated[statement_id], stripped,
                                            "变异未生效，负控本身是假绿")
                        mutated[statement_id] = stripped
                        with self.assertRaises(AssertionError):
                            assert_h8_statements_reference_their_columns(
                                mapper_file, mutated)

    def test_负控_删掉整条H8专用语句必须红(self):
        for mapper_file, spec in H8_STATEMENTS.items():
            for statement_id in spec:
                with self.subTest(mapper=mapper_file, statement=statement_id):
                    mutated = dict(self.statements[mapper_file])
                    del mutated[statement_id]
                    with self.assertRaises(AssertionError):
                        assert_h8_statements_reference_their_columns(
                            mapper_file, mutated)

    # ---- ③④ 调用关系:H8 专用方法只有一个调用方，通用写入口不许被 H8 复用 ----

    def test_h8_mapper_methods_are_called_only_from_the_single_h8_service(self):
        for spec in H8_STATEMENTS.values():
            for method in spec:
                with self.subTest(method=method):
                    assert_single_h8_caller(method, self.files_text)

    def test_负控_H8专用方法多一个调用方必须红(self):
        for spec in H8_STATEMENTS.values():
            for method in spec:
                with self.subTest(method=method):
                    mutated = dict(self.files_text)
                    mutated[GENERIC_BUSINESS_KEY_CALLER] = (
                        mutated[GENERIC_BUSINESS_KEY_CALLER] + "\nmapper.%s(detail);\n" % method)
                    with self.assertRaises(AssertionError):
                        assert_single_h8_caller(method, mutated)

    def test_generic_business_key_insert_is_not_reused_by_the_h8_path(self):
        found = callers_of(GENERIC_BUSINESS_KEY_INSERT, self.files_text)
        self.assertEqual([GENERIC_BUSINESS_KEY_CALLER], found,
                         "通用业务键写入口只能留给核销奖励积分；H8 必须走自己的专用语句")


if __name__ == "__main__":
    unittest.main(verbosity=2)

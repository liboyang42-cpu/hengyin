#!/usr/bin/env python3
"""分句器 + 三道门禁的单测(门禁 1-a 本体)。

用法:python3 ci/test_migration_gates.py   (纯 stdlib,CI 直跑)

【为什么每道门禁都必须带负控】
这次的病(autoRefundTask 8 天零告警)就是 review / CI / 对账【三道防线全绿着】放行的。
没有负控的门禁 = 第四道全绿的防线 —— 你根本不知道它在不在工作。
所以下面每一道门都有一条 test_负控_*,先证明「把病注入回去,它会红」。

【样本自己造,不引用 migration_c3_team_job.sql】
那个真病例文件哪天真被删,引用它的单测就白做了。所以形状照抄、文件自造。
"""
import json
import copy
import hashlib
import os
import re
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
from sql_splitter import split_statements, unterminated_token  # noqa: E402
from migration_gates import (  # noqa: E402
    _collation_key,
    _cmd_dep_order,
    _schema_contract_dump,
    master_dependencies,
    check_dependency_declared,
    check_dependency_ratchet,
    check_dependency_order,
    check_manifest_dependency_order,
    check_schema_manifest_order_source,
    select_added_migrations,
    check_pr_declaration,
    check_sql_text,
    find_unregistered_beans,
)

# migration_c3_team_job.sql 的形状(不是它本身):remark 里一个半角分号。
# 裸 split(";") 会把 INSERT 从中间劈开 → 1064 → 任务从没跑过一次。
真病例形状 = """-- 成团/未成团自动退款 定时任务注册(F4)
INSERT INTO sys_job
    (job_name, invoke_target, remark)
SELECT '成团未成团自动退款', 'demoTask.run()',
       'F4 未成团自动退余额/积分(不接微信原路);默认暂停,真机验证后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'demoTask.run()');
"""


class 分句器(unittest.TestCase):
    """门禁 1-a:锁住 tokenizer 行为,防未来有人改坏它。"""

    def test_字符串里的分号不切(self):
        """病根本体:引号内的分号是数据,不是分隔符。"""
        stmts = split_statements(真病例形状)
        self.assertEqual(len(stmts), 1)
        self.assertIn("不接微信原路);默认暂停", stmts[0])

    def test_负控_裸split会切坏而tokenizer不会(self):
        """负控:证明 tokenizer 确实不是裸 split —— 换回去就红。

        这条是【永久对照】。任务书要求的手工负控(把实现换回裸 split)跑完就没了,
        而这条每次 CI 都在证明两者行为不同。若哪天有人把 tokenizer 改回裸 split,
        它会立刻红。

        数字与真文件实测一致(2026-07-16,master 552a5cb2):
          旧裸 split → 2 段,两段引号都不闭合;新 tokenizer → 1 条,引号闭合。
        """
        裸切 = [s.strip() for s in 真病例形状.split(";") if s.strip()]
        self.assertEqual(len(裸切), 2, "裸 split 把 1 条语句从中间劈成 2 段(这就是病)")
        self.assertEqual(unterminated_token(裸切[0]), "'", "劈开处引号断了 → 1064")
        self.assertEqual(len(split_statements(真病例形状)), 1, "tokenizer 必须切成 1 条")

    def test_负控_判据2数分号检测不出这个病(self):
        """负控:钉死「判据②(语句数 == 分号数)」为什么作废,防有人好心加回来。

        判据②无效的真正原因比「碰巧放行」更硬:【裸切段数恒等于分号总数】,这是个
        恒等式,不是巧合 —— 「数分号」和「裸 split」本就是同一个计算的两次表达,
        互相印证不出任何问题,对【任何】文件都恒绿。
        (设计文档 §0-bis 举的 3 == 3 算错了:该文件只有 1 条语句、2 个分号,
         实测是 2 == 2。数字错了,结论对。)
        """
        文本 = 真病例形状
        分号总数 = 文本.count(";")
        裸切段数 = len([s for s in 文本.split(";") if s.strip()])
        self.assertEqual(分号总数, 2)
        self.assertEqual(裸切段数, 分号总数, "判据②在真病例上恒等 → 放行 → 所以它无效")

    def test_整行注释里的分号不切(self):
        """边界要说准:整行 -- 注释里的分号【本来就是安全的】,别传「注释里不能有分号」。"""
        sql = """-- 说明:先删;再插
DELETE FROM t;
INSERT INTO t VALUES (1);
"""
        self.assertEqual(len(split_statements(sql)), 2)

    def test_分隔线整行剥掉不漏破折号进SQL(self):
        """回归:`--- xxx` / `-------` 这类分隔线必须整行丢掉。

        只按 MySQL 的行尾规则(-- 后须跟空白)判,`---` 就不算注释 → 漏一个 `-` 进 SQL
        → 执行时 1064,而门禁 1-b 抓不到(没有引号问题)→ 一路放行到生产。
        所以【整行规则】(旧实现的行为)必须保留,不能被行尾规则替换掉。
        """
        for 分隔线 in ("--- 分隔线", "------------", "-----", "--"):
            stmts = split_statements("%s\nSELECT 1;\n" % 分隔线)
            self.assertEqual(stmts, ["SELECT 1"], "分隔线 %r 漏进了 SQL" % 分隔线)

    def test_行内减号不当成注释(self):
        """MySQL 规则:`a--b` 是两个减号,不是注释。行尾规则不能松成「见 -- 就剥」。"""
        self.assertEqual(split_statements("SELECT 3--1;"), ["SELECT 3--1"])

    def test_行尾注释里的分号不切(self):
        sql = "DELETE FROM t;   -- 先删;再插\nINSERT INTO t VALUES (1);\n"
        stmts = split_statements(sql)
        self.assertEqual(len(stmts), 2)
        self.assertNotIn("先删", stmts[1], "注释应被剥掉,不该粘进下一条语句")

    def test_块注释里的分号不切(self):
        sql = "DELETE FROM t /* 注意;这里 */ WHERE id=1;\nINSERT INTO t VALUES (1);\n"
        self.assertEqual(len(split_statements(sql)), 2)

    def test_可执行注释不被剥掉(self):
        """/*! */ 是 MySQL 可执行注释,剥掉 = 静默丢语句 —— 正是要杀的失败类型。"""
        stmts = split_statements("/*!40101 SET NAMES utf8 */;\nSELECT 1;\n")
        self.assertEqual(len(stmts), 2)
        self.assertIn("SET NAMES utf8", stmts[0])

    def test_正常多语句正确切分(self):
        sql = "ALTER TABLE a ADD COLUMN b INT;\nUPDATE a SET b=1;\nSELECT 1;\n"
        self.assertEqual(len(split_statements(sql)), 3)

    def test_双引号与反引号内的分号不切(self):
        sql = 'INSERT INTO `wei;rd` VALUES ("a;b");\nSELECT 1;\n'
        self.assertEqual(len(split_statements(sql)), 2)

    def test_转义引号不误判为闭合(self):
        r"""'It\'s' 和 'It''s' 都是一个字符串,里头的分号仍不该切。"""
        self.assertEqual(len(split_statements(r"SELECT 'It\'s; ok';" + "\nSELECT 1;")), 2)
        self.assertEqual(len(split_statements("SELECT 'It''s; ok';\nSELECT 1;")), 2)

    def test_末尾无分号也算一条(self):
        self.assertEqual(len(split_statements("SELECT 1")), 1)

    def test_空文件与纯注释切出零条(self):
        self.assertEqual(split_statements(""), [])
        self.assertEqual(split_statements("-- 只有注释;没有语句\n"), [])

    def test_runner_assertion_comment_is_bound_to_following_statement(self):
        statements = split_statements(
            "-- runner: assert=scalar-true\nSELECT 1;\nSELECT 2;")

        self.assertEqual(2, len(statements))
        self.assertTrue(statements[0].startswith("-- runner: assert=scalar-true\n"))
        self.assertEqual("SELECT 2", statements[1])

    def test_符号闭合判定(self):
        self.assertIsNone(unterminated_token("SELECT 'a;b';"))
        self.assertEqual(unterminated_token("SELECT 'a;b;"), "'")

    def test_没闭合的块注释会吞掉后面的语句(self):
        """没闭合的 /* 把后面的语句整块吞掉 —— 不报错、零告警,正是要杀的失败类型。

        注意它【逐条判恒绿】:吞完之后剩下的 SELECT 1 自己是干净的。
        所以判据必须对整个文件判,不能对拆出的单条语句判。
        """
        文本 = "SELECT 1;\n/* 忘了闭合的注释\nDELETE FROM t;\n"
        self.assertEqual(split_statements(文本), ["SELECT 1"], "DELETE 被静默吞掉了")
        self.assertEqual(unterminated_token(文本), "/*")
        for st in split_statements(文本):
            self.assertIsNone(unterminated_token(st), "逐条判是恒绿的 —— 所以必须整文件判")


class 门禁1b_迁移SQL可执行(unittest.TestCase):
    """新增/修改的迁移文件必须能被真执行器正确分句。"""

    def test_干净文件通过(self):
        self.assertEqual(check_sql_text("x.sql", "ALTER TABLE a ADD COLUMN b INT;"), [])

    def test_负控_无where的update与delete必须红(self):
        for sql in (
                "UPDATE cms_topic SET product_type = 1;",
                "DELETE FROM cms_topic;",
                "UPDATE cms_topic SET remark = 'contains WHERE text';"):
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems)
                self.assertIn("WHERE", problems[0])

    def test_负控_无where写的四种逃逸形状必须红(self):
        for sql in (
                "DELETE IGNORE FROM cms_topic;",
                "DELETE LOW_PRIORITY FROM cms_topic;",
                "DELETE t FROM cms_topic t;",
                "TRUNCATE TABLE cms_topic;",
                "TRUNCATE cms_topic;"):
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems)
                self.assertIn("WHERE", problems[0])

    def test_负控_子查询where不能冒充外层update_delete边界(self):
        for sql in (
                "UPDATE t SET c = (SELECT x FROM y WHERE y.id = 1);",
                "DELETE FROM t USING t JOIN (SELECT id FROM y WHERE y.id = 1) picked ON picked.id = t.id;"):
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems)
                self.assertIn("WHERE", problems[0])

    def test_delete修饰词与alias带where时通过(self):
        for sql in (
                "DELETE IGNORE FROM cms_topic WHERE id = 23;",
                "DELETE LOW_PRIORITY FROM cms_topic WHERE id = 23;",
                "DELETE t FROM cms_topic t WHERE t.id = 23;"):
            with self.subTest(sql=sql):
                self.assertEqual(check_sql_text("x.sql", sql), [])

    def test_负控_runner断言前缀不能绕过无where写门禁(self):
        for sql in (
                "-- runner: assert=rowcount-positive\n"
                "UPDATE cms_registration SET purchase_kind = 3;",
                "-- runner: assert=rowcount-positive\n"
                "DELETE FROM cms_registration;"):
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems)
                self.assertIn("WHERE", problems[0])

    def test_带where的窄范围update通过(self):
        self.assertEqual(
            check_sql_text("x.sql", "UPDATE cms_topic SET product_type=1 WHERE id=23;"),
            [],
        )

    def test_负控_声明readback却没有机器断言必须红(self):
        sql = """-- readback：执行方必须从数据库回读
SELECT COUNT(*) FROM sys_menu WHERE perms = 'business:audit:submit';
"""
        problems = check_sql_text("migration_audit.sql", sql)
        self.assertTrue(problems)
        self.assertIn("runner 断言", problems[0])

    def test_负控_声明readback且select前有sql注释仍必须红(self):
        sql = """-- readback：执行方必须从数据库回读
/*!99999 optional readback prelude */ SELECT COUNT(*)
FROM sys_menu WHERE perms = 'business:audit:submit';
"""
        problems = check_sql_text("migration_audit.sql", sql)
        self.assertTrue(problems, "SELECT 前有 SQL 注释也必须识别为未绑定机器断言的 readback")
        self.assertIn("runner 断言", problems[0])

    def test_声明readback且绑定机器断言通过(self):
        sql = """-- readback：执行方必须从数据库回读
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM sys_menu WHERE perms = 'business:audit:submit';
"""
        self.assertEqual(check_sql_text("migration_audit.sql", sql), [])

    def test_带sql注释的readback绑定机器断言后通过(self):
        sql = """-- readback：执行方必须从数据库回读
-- runner: assert=scalar-true
/*!99999 optional readback prelude */ SELECT COUNT(*) = 1
FROM sys_menu WHERE perms = 'business:audit:submit';
"""
        self.assertEqual(check_sql_text("migration_audit.sql", sql), [])

    def test_探店日三份迁移的回读声明真的被门禁认出来(self):
        """★这三份文件曾经全程在门禁盲区里。

        它们原本写的是「-- 执行后必须从对面回读为 1：…」,而 _READBACK_DECLARATION 只认
        `-- readback:` / `-- 回读合同` / `-- 执行后的对端回读必须` 三种措辞 ——
        一个字之差,正则不认 ⇒「声明为回读的 SELECT 必须绑 runner 断言」这条规则
        对本批**从未通电**:把 `-- runner: assert=scalar-true` 那一行删掉,门禁照样绿,
        回读 SELECT 退化成一条结果被丢弃的语句。现在措辞已统一成门禁认得的写法。
        """
        import migration_gates

        for name in (
            "migration_20260816_tandianri_batch3_funds_club_payee.sql",
            "migration_20260816_tandianri_batch3_funds_club_compensation.sql",
            "migration_20260816_tandianri_batch3_funds_player_cooldown.sql",
        ):
            path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            self.assertTrue(
                migration_gates._READBACK_DECLARATION.search(text),
                "%s 的回读声明措辞必须能被门禁认出,否则这条规则对它永远不通电" % name,
            )

    def test_负控_摘掉runner断言绑定后这三份迁移必须红(self):
        """★门禁自己的负控:把承重的那一行删掉,它必须真的红。

        绿是廉价的,能变红才是价值(总纲「写完门禁必须做负控」)。
        """
        for name in (
            "migration_20260816_tandianri_batch3_funds_club_payee.sql",
            "migration_20260816_tandianri_batch3_funds_club_compensation.sql",
            "migration_20260816_tandianri_batch3_funds_player_cooldown.sql",
        ):
            path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            self.assertEqual(check_sql_text(name, text), [], "%s 原样必须绿" % name)

            without_binding = "\n".join(
                line for line in text.splitlines() if not line.strip().startswith("-- runner: assert=")
            )
            problems = check_sql_text(name, without_binding)
            self.assertTrue(problems, "%s 摘掉 runner 断言绑定之后必须红" % name)
            self.assertIn("runner 断言", problems[0])

    def test_探店日承重权限必须真授到角色而不是只种菜单(self):
        """★只种 sys_menu 不写 sys_role_menu = 又一颗谁也拿不到的功能位。

        两条都是本批新造出来的承重点,今天却只有超管(`*:*:*`)匹配得上:
          · `business:clubextra:approve` —— 开售硬闸 `ExtraApproverReachability` 拿它判
            「这个项目负责人按不按得动预批按钮」。没有任何角色持有它 ⇒ 唯一能被冻进
            `extra_approver_user_id` 的人只剩 userId=1 ⇒ 「项目负责人事前审批」退化成
            「超管自己批自己」。
          · `business:topic:query` —— `/topic` 这条动态路由的准入
            (`chengyinhub-ui/src/router/index.js` 的 `permissions`,由
            `filterDynamicRoutes` → `auth.hasPermiOr` 过滤)。四个 A 的全部按钮都在
            `/topic/detail` 这一张页上;cy_finance / cy_operator 拿不到它就<b>打不开那张页</b>,
            API 层这一轮修好的可达性在控制台上仍然点不动。

        判据是「授权行 + 对面回读」,与同批 payee 两权互斥那三段式同一把尺子。
        """
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql",
                            "migration_20260816_tandianri_batch3_funds_club_compensation.sql")
        with open(path, encoding="utf-8") as handle:
            text = handle.read()

        # ★只判「这个变量在 INSERT 块里出现过」是<b>不可变红</b>的:每条 INSERT 的 NOT EXISTS 守卫里
        #   也写着同一个变量,把 SELECT 那一行的权限换成别的,变量照样在块里 ⇒ 断言恒绿。
        #   所以这里抽的是**真正被插进去的那一对** (role, perm)。
        granted = set(re.findall(r"SELECT (@cy_role_\w+), (@cy_perm_\w+) FROM dual", text))
        readbacks = "\n".join(re.findall(r"-- runner: assert=scalar-true\s*\n([\s\S]*?);", text))

        for perms, variable in (("business:clubextra:approve", "@cy_perm_extra_approve"),
                                ("business:topic:query", "@cy_perm_topic_query")):
            self.assertTrue(perms in text, "%s 必须出现在本批迁移里" % perms)
            self.assertTrue("SET %s :=" % variable in text,
                            "%s 必须先被解析成 menu_id(%s)" % (perms, variable))
            self.assertTrue(variable in readbacks,
                            "%s 的授权必须进回读断言 —— 只回读菜单存在,读不到「这个人真拿得到它」" % perms)

        # 逐对点名:EXTRA 事前审批落运营侧;主题详情页两个资金角色都要有,否则谁打不开谁点不动。
        for pair in (("@cy_role_operator", "@cy_perm_extra_approve"),
                     ("@cy_role_finance", "@cy_perm_topic_query"),
                     ("@cy_role_operator", "@cy_perm_topic_query")):
            self.assertIn(pair, granted,
                          "缺授权行 %s → %s;已有的是 %s" % (pair[1], pair[0], sorted(granted)))

    def test_负控_把承重权限的授权行摘掉之后上一条必须红(self):
        """绿是廉价的,能变红才是价值(总纲「写完门禁必须做负控」)。"""
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql",
                            "migration_20260816_tandianri_batch3_funds_club_compensation.sql")
        with open(path, encoding="utf-8") as handle:
            text = handle.read()

        for role, variable in (("@cy_role_operator", "@cy_perm_extra_approve"),
                               ("@cy_role_finance", "@cy_perm_topic_query"),
                               ("@cy_role_operator", "@cy_perm_topic_query")):
            # 只把 SELECT 那一行的权限换掉(NOT EXISTS 守卫里的同名变量原样留着)——
            # 这正是 2026-08-16 R4 负控实测过的那种「变量还在、插进去的却是别的权限」形状。
            stripped = text.replace("SELECT %s, %s FROM dual" % (role, variable),
                                    "SELECT %s, @cy_perm_batch_paid FROM dual" % role)
            granted = set(re.findall(r"SELECT (@cy_role_\w+), (@cy_perm_\w+) FROM dual", stripped))
            self.assertNotIn((role, variable), granted,
                             "%s → %s 的授权行一旦被改掉,上一条断言就必须失去它的落点" % (variable, role))

    def test_真病例形状现在通过_因为分句器修好了(self):
        """F-1 的回归断言:字符串里的半角分号【不再是病】,不该再拦它。

        ⚠️ 任务书 §3.3 给 1-b 写的负控是「造一个故意写半角分号的 SQL,它必须红」——
        那条来自「分句器不修」的旧世界观,和 §2「两个都做」的拍板互斥:分句器修好后,
        字符串里的半角分号本来就该绿。真正该红的是下面两条(引号未闭合 / DELIMITER 形状)。
        """
        self.assertEqual(check_sql_text("x.sql", 真病例形状), [])

    def test_负控_引号未闭合必须红(self):
        problems = check_sql_text("x.sql", "INSERT INTO t VALUES ('abc);")
        self.assertEqual(len(problems), 1)
        self.assertIn("未闭合", problems[0])

    def test_负控_块注释未闭合必须红(self):
        """吞掉 DELETE 却一声不吭 —— 门禁必须红。逐条判的话这里恒绿。"""
        problems = check_sql_text("x.sql", "SELECT 1;\n/* 忘了闭合\nDELETE FROM t;\n")
        self.assertEqual(len(problems), 1)
        self.assertIn("/*", problems[0])

    def test_负控_存储过程必须红(self):
        """没有 DELIMITER 的过程会被普通分号切坏,必须显式红。"""
        for 形状 in ("CREATE PROCEDURE p() BEGIN SELECT 1; END",
                     "CREATE FUNCTION f() RETURNS INT BEGIN RETURN 1; END",
                     "CREATE TRIGGER tg BEFORE INSERT ON t FOR EACH ROW BEGIN SET @a=1; END"):
            problems = check_sql_text("x.sql", 形状)
            self.assertTrue(problems, "%s 必须被拦下" % 形状[:24])
            self.assertIn("DELIMITER", problems[0])

    def test_负控_runner断言开头的存储过程仍必须红(self):
        """★合并 #732 时手写的那一处判据的负控:routine 头必须在【剥掉 runner 元数据之后】判。

        `-- runner: assert=` 是候选引入的机器合同,写在语句最前面。若 routine 门禁直接拿原始
        语句去解析,首 token 变成 `-`,`_created_routine_kind` 返回 None ⇒ 没有 DELIMITER 的
        存储过程被【静默放行】,而且两侧既有测试全绿、零告警。本条就是让那个改动能变红。
        """
        sql = "-- runner: assert=scalar-true\nCREATE PROCEDURE p() BEGIN SELECT 1; END"
        problems = check_sql_text("x.sql", sql)
        self.assertTrue(problems, "带 runner 前缀的无 DELIMITER 过程必须仍被拦下")
        self.assertIn("DELIMITER", problems[0])

    def test_DELIMITER过程按客户端分隔符完整分句(self):
        sql = """DROP PROCEDURE IF EXISTS p;
DELIMITER $$
CREATE PROCEDURE p()
BEGIN
  SELECT 1;
  SELECT 'literal $$ and ; stays data';
END$$
DELIMITER ;
CALL p();
"""
        stmts = split_statements(sql)
        self.assertEqual(len(stmts), 3)
        self.assertTrue(stmts[1].startswith("CREATE PROCEDURE p()"))
        self.assertIn("SELECT 1;", stmts[1])
        self.assertEqual(check_sql_text("migration_proc.sql", sql), [])

    def test_DELIMITER双斜线过程也能完整分句(self):
        sql = """DELIMITER //
CREATE PROCEDURE p()
BEGIN
  SELECT 1;
END //
DELIMITER ;
CALL p();
"""
        self.assertEqual(len(split_statements(sql)), 2)
        self.assertEqual(check_sql_text("migration_proc.sql", sql), [])

    def test_负控_DELIMITER未恢复默认值必须红(self):
        sql = "DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$\n"
        problems = check_sql_text("migration_proc.sql", sql)
        self.assertTrue(problems)
        self.assertIn("DELIMITER", problems[0])

    def test_负控_DELIMITER使用字母数字必须红(self):
        sql = "DELIMITER end\nCREATE PROCEDURE p() BEGIN SELECT 1; ENDend\nDELIMITER ;\n"
        problems = check_sql_text("migration_proc.sql", sql)
        self.assertTrue(problems)
        self.assertIn("安全标点", problems[0])

    def test_负控_DELIMITER不能与井号注释冲突(self):
        sql = "DELIMITER #\nCREATE PROCEDURE p() BEGIN SELECT 1; END#\nDELIMITER ;\n"
        problems = check_sql_text("migration_proc.sql", sql)
        self.assertTrue(problems)
        self.assertIn("安全标点", problems[0])

    def test_负控_DELIMITER运算符必须红(self):
        """自定义 delimiter 若会自然出现在 routine 正文，会提前切句却整体放绿。"""
        samples = {
            "!=": "IF 1 != 2 THEN SET @x = 1; END IF;",
            ":=": "SET @x := 1;",
            "<>": "IF 1 <> 2 THEN SET @x = 1; END IF;",
            "||": "SELECT 1 || 0;",
            "-": "SELECT 2 - 1;",
        }
        for delimiter, body in samples.items():
            with self.subTest(delimiter=delimiter):
                sql = ("DELIMITER {0}\nCREATE PROCEDURE p() BEGIN {1} END{0}\n"
                       "DELIMITER ;\n".format(delimiter, body))
                problems = check_sql_text("migration_proc.sql", sql)
                self.assertTrue(problems, "%s 不得作为 delimiter" % delimiter)
                self.assertIn("安全标点", problems[0])

    def test_负控_合法DEFINER变体的裸routine必须红(self):
        samples = (
            "CREATE DEFINER = 'u' @ 'h' PROCEDURE p() BEGIN SELECT 1; END;",
            "CREATE DEFINER = 'u ser'@'h' FUNCTION f() RETURNS INT BEGIN RETURN 1; END;",
            "CREATE DEFINER = `u` @ `h` PROCEDURE p() BEGIN SELECT 1; END;",
            "CREATE DEFINER = CURRENT_USER() FUNCTION f() RETURNS INT BEGIN RETURN 1; END;",
            "CREATE OR REPLACE DEFINER=user@localhost PROCEDURE p() BEGIN SELECT 1; END;",
            "CREATE /*!50017 DEFINER='u'@'h'*/ TRIGGER tg BEFORE INSERT ON t "
            "FOR EACH ROW BEGIN SET @a=1; END;",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems, "DEFINER 变体不得绕过裸 routine 门禁")
                self.assertIn("DELIMITER", problems[0])

    def test_负控_DEFINER裸账号对象关键字碰撞仍识别routine(self):
        """user/host 都可是合法裸 identifier，必须先消费完整账号。"""
        samples = (
            "CREATE DEFINER=user@localhost PROCEDURE p() BEGIN SELECT 1; END;",
            "CREATE DEFINER=role@localhost PROCEDURE p() BEGIN SELECT 1; END;",
            "CREATE DEFINER=server@localhost FUNCTION f() RETURNS INT BEGIN RETURN 1; END;",
            "CREATE DEFINER=view@localhost TRIGGER tg BEFORE INSERT ON t "
            "FOR EACH ROW BEGIN SET @a=1; END;",
            "CREATE DEFINER=app@view PROCEDURE p() BEGIN SELECT 1; END;",
            "CREATE DEFINER=app@event FUNCTION f() RETURNS INT BEGIN RETURN 1; END;",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems, "DEFINER 账号关键字碰撞不得绕过门禁")
                self.assertIn("DELIMITER", problems[0])

    def test_DEFINER普通VIEW_EVENT即使正文含routine关键字也通过(self):
        samples = (
            "CREATE DEFINER=user@localhost VIEW v AS SELECT t.procedure FROM t;",
            "CREATE DEFINER=user@localhost EVENT e ON SCHEDULE EVERY 1 DAY "
            "DO SET @function=1;",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                self.assertEqual(check_sql_text("x.sql", sql), [])

    def test_负控_非法或不完整DEFINER必须fail_closed(self):
        samples = (
            "CREATE DEFINER user@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=user@ PROCEDURE p() BEGIN SELECT 1; END;",
            "CREATE DEFINER=@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=CURRENT_USER( EVENT e ON SCHEDULE EVERY 1 DAY DO SELECT 1;",
            "CREATE DEFINER=user@localhost",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems, "非法/不完整 DEFINER 必须 fail-closed")
                self.assertIn("DEFINER", problems[0])

    def test_负控_CURRENT_USER是完整排他的DEFINER值(self):
        samples = (
            "CREATE DEFINER=CURRENT_USER@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=CURRENT_USER()@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=CURRENT_USER extra VIEW v AS SELECT 1;",
            "CREATE DEFINER=CURRENT_USER() extra VIEW v AS SELECT 1;",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems, "CURRENT_USER special value 后不得接 account/object 外 token")
                self.assertIn("DEFINER", problems[0])

    def test_负控_VIEW的ALGORITHM前缀不得绕过非法DEFINER(self):
        samples = (
            "CREATE ALGORITHM=UNDEFINED DEFINER=app@ VIEW v AS SELECT 1;",
            "CREATE OR REPLACE ALGORITHM=MERGE DEFINER=@localhost VIEW v AS SELECT 1;",
            "CREATE ALGORITHM=TEMPTABLE DEFINER=CURRENT_USER@localhost VIEW v AS SELECT 1;",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems, "VIEW ALGORITHM 前缀不得让非法 DEFINER 放绿")
                self.assertIn("DEFINER", problems[0])

    def test_VIEW固定头部顺序的可选子句组合(self):
        samples = (
            "CREATE VIEW v AS SELECT 1;",
            "CREATE OR REPLACE VIEW v AS SELECT 1;",
            "CREATE ALGORITHM=UNDEFINED VIEW v AS SELECT 1;",
            "CREATE ALGORITHM=MERGE DEFINER=app@localhost VIEW v AS SELECT 1;",
            "CREATE ALGORITHM=TEMPTABLE SQL SECURITY INVOKER VIEW v AS SELECT 1;",
            "CREATE OR REPLACE ALGORITHM=UNDEFINED DEFINER=CURRENT_USER "
            "SQL SECURITY DEFINER VIEW v AS SELECT 1;",
            "CREATE OR REPLACE ALGORITHM=MERGE DEFINER=CURRENT_USER() "
            "SQL SECURITY INVOKER VIEW v AS SELECT 1;",
            "CREATE DEFINER=app@localhost SQL SECURITY DEFINER VIEW v AS SELECT 1;",
            "CREATE SQL SECURITY INVOKER VIEW v AS SELECT 1;",
            "CREATE ALGORITHM=UNDEFINED VIEW v AS SELECT 'DEFINER=app@ PROCEDURE';",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                self.assertEqual(check_sql_text("x.sql", sql), [])

    def test_负控_VIEW头部子句非法或乱序必须红(self):
        samples = (
            "CREATE ALGORITHM VIEW v AS SELECT 1;",
            "CREATE ALGORITHM= VIEW v AS SELECT 1;",
            "CREATE ALGORITHM=HASH VIEW v AS SELECT 1;",
            "CREATE ALGORITHM=UNDEFINED DEFINER app@localhost VIEW v AS SELECT 1;",
            "CREATE SQL SECURITY VIEW v AS SELECT 1;",
            "CREATE SQL SECURITY OWNER VIEW v AS SELECT 1;",
            "CREATE DEFINER=app@localhost ALGORITHM=MERGE VIEW v AS SELECT 1;",
            "CREATE SQL SECURITY INVOKER ALGORITHM=MERGE VIEW v AS SELECT 1;",
        )
        for sql in samples:
            with self.subTest(sql=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems, "VIEW 头部非法/乱序必须 fail-closed")

    def test_合法未引号account的routine用正确delimiter通过(self):
        accounts = ("1app@localhost", "$app@localhost", "用户@localhost", "app@本机")
        for account in accounts:
            with self.subTest(account=account):
                sql = ("DELIMITER //\nCREATE DEFINER={0} PROCEDURE p() "
                       "BEGIN SELECT 1; END//\nDELIMITER ;\n".format(account))
                self.assertEqual(check_sql_text("x.sql", sql), [])

    def test_负控_合法未引号account的裸routine仍必须红(self):
        accounts = ("1app@localhost", "$app@localhost", "用户@localhost", "app@本机")
        for account in accounts:
            with self.subTest(account=account):
                sql = ("CREATE DEFINER={0} PROCEDURE p() "
                       "BEGIN SELECT 1; END;".format(account))
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems)
                self.assertIn("DELIMITER", problems[0])

    def test_account_identifier的引号数字美元与BMP边界(self):
        valid = (
            "CREATE DEFINER='123'@'456' VIEW v AS SELECT 1;",
            "CREATE DEFINER=a1$@h$ VIEW v AS SELECT 1;",
            "CREATE DEFINER=1$app@本机2 VIEW v AS SELECT 1;",
            "CREATE DEFINER=é@主机 VIEW v AS SELECT 1;",
            "CREATE DEFINER=$app VIEW v AS SELECT 1;",
        )
        invalid = (
            "CREATE DEFINER=123@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=app@123 VIEW v AS SELECT 1;",
            "CREATE DEFINER=app-name@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=app.name@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=''@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=app@'' VIEW v AS SELECT 1;",
            "CREATE DEFINER=``@localhost VIEW v AS SELECT 1;",
            "CREATE DEFINER=app@😀 VIEW v AS SELECT 1;",
        )
        for sql in valid:
            with self.subTest(valid=sql):
                self.assertEqual(check_sql_text("x.sql", sql), [])
        for sql in invalid:
            with self.subTest(invalid=sql):
                problems = check_sql_text("x.sql", sql)
                self.assertTrue(problems, "纯数字/非法字符/空 account 必须 fail-closed")
                self.assertIn("DEFINER", problems[0])

    def test_字符串里的routine文字不误红(self):
        sql = "INSERT INTO notes(txt) VALUES ('CREATE PROCEDURE p()');"
        self.assertEqual(check_sql_text("x.sql", sql), [])

    def test_注释掉的存储过程不误红(self):
        """剥注释是分句器干的活,所以这条能免费拿到 —— 注释里提一嘴不该红。"""
        self.assertEqual(check_sql_text("x.sql", "-- 以后可能加 CREATE PROCEDURE p()\nSELECT 1;"), [])

    def test_块注释里的routine文字不误红(self):
        sql = "/* 以后可能加 CREATE FUNCTION f() */\nSELECT 1;"
        self.assertEqual(check_sql_text("x.sql", sql), [])

    def test_P1资金历史奖励回填必须在preflight_guard之后(self):
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        path = os.path.join(root, "chengyinhub-admin/sql/migration_p1_funds_core_uniqueness_20260724.sql")
        with open(path, encoding="utf-8") as f:
            self.assertEqual(check_sql_text(path, f.read()), [])

    def test_负控_P1资金历史奖励回填早于preflight_guard必须红(self):
        sql = """UPDATE ums_member_points_detail SET reward_business_key = 'registration:2:0';
INSERT INTO p1_funds_core_guard (id)
SELECT 1
FROM (SELECT 1 AS violation) guard_violation;
"""
        problems = check_sql_text("migration_p1_funds_core_uniqueness_20260724.sql", sql)
        self.assertTrue(any("早于 fail-closed preflight guard" in problem
                            for problem in problems))


class 门禁2_task_bean注册(unittest.TestCase):
    """有 bean 必须有注册 SQL。单向:bean 被删时注册也该没,不该报错。"""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.root, "a/task"))
        os.makedirs(os.path.join(self.root, "chengyinhub-admin/sql"))

    def tearDown(self):
        shutil.rmtree(self.root)

    def _写bean(self, name):
        with open(os.path.join(self.root, "a/task/%s.java" % name.capitalize()), "w",
                  encoding="utf-8") as f:
            f.write('@Component("%s")\npublic class X {}\n' % name)

    def _写sql(self, filename, content):
        with open(os.path.join(self.root, "chengyinhub-admin/sql", filename), "w",
                  encoding="utf-8") as f:
            f.write(content)

    def _写java(self, cls, src):
        with open(os.path.join(self.root, "a/task/%s.java" % cls), "w",
                  encoding="utf-8") as f:
            f.write(src)

    def test_有注册则通过(self):
        self._写bean("demoTask")
        self._写sql("migration_demo.sql",
                    "INSERT INTO sys_job (invoke_target) VALUES ('demoTask.run()');")
        self.assertEqual(find_unregistered_beans(self.root), [])

    # ── 以下五条负控:bean 正则放宽的理由 ────────────────────────────
    # 旧正则 @Component\(\s*"([A-Za-z0-9_]*[Tt]ask)"\s*\) 只认字面量 @Component("xxxTask")。
    # 下面四种写法【都能被 quartz 真调度】(JobInvokeUtil 走 SpringUtils.getBean(beanName),
    # 认的是 bean 名,不是注解怎么写的),但旧正则一个都抓不到 ⇒ 静默逃逸,
    # 而逃逸的后果正是本门禁要防的病:部署了也永不调度、不报错、零告警。

    def test_负控_裸Component靠类名推bean也要注册(self):
        """Spring 默认 bean 名 = 类名首字母小写 ⇒ FooTask 类的 bean 名就是 fooTask。"""
        self._写java("FooTask", '@Component\npublic class FooTask {}\n')
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["fooTask"])

    def test_负控_value写法也要注册(self):
        self._写java("BarTask", '@Component(value = "barTask")\npublic class BarTask {}\n')
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["barTask"])

    def test_负控_Service注解也要注册(self):
        self._写java("BazTask", '@Service("bazTask")\npublic class BazTask {}\n')
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["bazTask"])

    def test_负控_bean名叫Job也要注册(self):
        """命名约定不是机器强制的:叫 fooJob 的 quartz 任务同样得注册。"""
        self._写java("FooJob", '@Component("fooJob")\npublic class FooJob {}\n')
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["fooJob"])

    def test_Scheduled自调度豁免(self):
        """★ 放宽正则的核心风险闸:@Scheduled = Spring 自己驱动,不经 quartz、不进 sys_job。

        没有这条豁免,放宽正则会把 AccountDeregistrationJob 这类误报成「漏注册」= 假红。
        实测(master 702d6077):全仓 18 个 *Task/*Job bean 里恰好 1 个命中豁免。
        """
        self._写java("SelfSchedJob",
                     '@Component\npublic class SelfSchedJob {\n'
                     '    @Scheduled(cron = "0 0 3 * * ?")\n'
                     '    public void run() {}\n}\n')
        self.assertEqual(find_unregistered_beans(self.root), [])

    def test_leader_schedule_requires_actual_startup_and_matching_typed_callback(self):
        self._写java("LeaderDemoJob", '@Component\npublic class LeaderDemoJob { public void run() {} }\n')
        path = os.path.join(self.root, "chengyinhub-admin/src/main/java/com/chengyinhub/web/job/LeaderScheduledJobsConfig.java")
        os.makedirs(os.path.dirname(path))
        source = '''import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
@Configuration
public class LeaderScheduledJobsConfig {
    private final MySqlSchedulerCoordinator coordinator;
    private final LeaderDemoJob work;
    @PostConstruct
    public void register() {
        coordinator.registerCron("leaderDemoJob.run()", "0 15 3 * * ?", work::run);
    }
}
'''
        for method in ('registerCron', 'registerFixedDelay'):
            with open(path, "w", encoding="utf-8") as target:
                changed = source.replace('registerCron', method)
                if method == 'registerFixedDelay':
                    changed = changed.replace('"0 15 3 * * ?"', 'Duration.ofSeconds(5), Duration.ZERO')
                target.write(changed)
            self.assertEqual([], find_unregistered_beans(self.root))
        for changed in (
                source.replace('coordinator.registerCron', '// coordinator.registerCron'),
                source.replace('coordinator.registerCron', 'other.registerCron'),
                source.replace('leaderDemoJob.run()', 'anotherJob.run()'),
                source.replace('work::run', 'work::missing'),
                source.replace('LeaderDemoJob work', 'OtherJob work'),
                source.replace('@Configuration', ''),
                source.replace('@PostConstruct', ''),
                source.replace('coordinator.registerCron', 'if (false) coordinator.registerCron'),
                source.replace('leaderDemoJob.run()', 'leaderDemoJob.missing()').replace('work::run', 'work::missing')):
            with self.subTest(changed=changed):
                with open(path, "w", encoding="utf-8") as target:
                    target.write(changed)
                self.assertEqual(['leaderDemoJob'], [bean for bean, _ in find_unregistered_beans(self.root)])

    def test_负控_注销SQL不算注册(self):
        """★ 方向陷阱:DELETE FROM sys_job 是【注销】,把它算成「有注册」= 方向搞反。

        旧实现把整个 .sql 拼成 blob 搜 'bean.',DELETE 语句里也含 'demoTask.run()' ⇒ 误判已注册。
        """
        self._写bean("demoTask")
        self._写sql("migration_decommission.sql",
                    "DELETE FROM sys_job WHERE invoke_target = 'demoTask.run()';")
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["demoTask"])

    def test_负控_部署包里的注册不算数(self):
        """_deploy_/bak_/_precheck_ 是不在库上跑的辅助件(与 migration-layout-check.sh 同口径)。

        历史部署包里躺着一条 INSERT,不等于生产库里有这条注册。
        """
        self._写bean("demoTask")
        self._写sql("_deploy_abc123_job.sql",
                    "INSERT INTO sys_job (invoke_target) VALUES ('demoTask.run()');")
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["demoTask"])

    def test_负控_无注册必须红(self):
        self._写bean("demoTask")
        missing = find_unregistered_beans(self.root)
        self.assertEqual([b for b, _ in missing], ["demoTask"])

    def test_负控_注册被注释掉算没注册(self):
        """假绿防护:被注释掉的 INSERT 长得和真注册一模一样,裸 grep 会放行。

        所以 find_registered_targets 先过分句器剥注释,而不是 grep。
        """
        self._写bean("demoTask")
        self._写sql("migration_demo.sql",
                    "-- INSERT INTO sys_job (invoke_target) VALUES ('demoTask.run()');\n")
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["demoTask"])

    def test_前缀相似的bean不互相蹭注册(self):
        """fooTask 不该被 barFooTask 的注册蹭中 —— 所以带前引号搜。"""
        self._写bean("fooTask")
        self._写sql("migration_x.sql",
                    "INSERT INTO sys_job (invoke_target) VALUES ('barFooTask.run()');")
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["fooTask"])

    def test_删掉bean后不再要求注册(self):
        """方向单向:没 bean 就不该因为「注册也没了」而报错。"""
        self.assertEqual(find_unregistered_beans(self.root), [])

    def test_负控_放在task目录外的bean照样要注册(self):
        """「保护半径的另一半是目录」—— 只扫 task/ 的话,放 tasks/ 的任务就无声逃逸。

        #43/#48 的迁移就是前缀对、目录错,被 -maxdepth 1 漏掉 → 永不落库、零告警。
        同一个坑不踩第二次:门禁扫全仓库,不认目录。
        """
        os.makedirs(os.path.join(self.root, "b/tasks"))
        with open(os.path.join(self.root, "b/tasks/StrayTask.java"), "w",
                  encoding="utf-8") as f:
            f.write('@Component("strayTask")\npublic class StrayTask {}\n')
        self.assertEqual([b for b, _ in find_unregistered_beans(self.root)], ["strayTask"])

    def test_不叫Task的Component不管(self):
        """只管 *Task 命名的 bean —— 普通 @Component 不是定时任务,别误红。"""
        os.makedirs(os.path.join(self.root, "c/service"))
        with open(os.path.join(self.root, "c/service/FooService.java"), "w",
                  encoding="utf-8") as f:
            f.write('@Component("fooService")\npublic class FooService {}\n')
        self.assertEqual(find_unregistered_beans(self.root), [])


class 门禁3_PR迁移声明(unittest.TestCase):
    """PR 动了迁移就必须显式声明 —— 夹带必须无法静默。"""

    def test_没动迁移则不管(self):
        self.assertIsNone(check_pr_declaration("test(xcx): 改个样式", "", []))

    def test_负控_夹带迁移却没声明必须红(self):
        """44797404 那个病:标题写 test(xcx),却夹带了碰钱的 sys_job 注册。"""
        problem = check_pr_declaration("test(xcx): 改个样式", "顺手修了点东西",
                                       ["chengyinhub-admin/sql/migration_x.sql"])
        self.assertIsNotNone(problem)
        self.assertIn("migration_x.sql", problem)

    def test_标了影响分类三通过(self):
        self.assertIsNone(check_pr_declaration(
            "feat: 加个任务", "影响分类:③ 数据库迁移 —— 合前先落库",
            ["chengyinhub-admin/sql/migration_x.sql"]))

    def test_影响分类是标题下方表格也通过(self):
        """本仓真实格式:「## 影响分类」标题 + 下方列表里写 ③,不在同一行。"""
        self.assertIsNone(check_pr_declaration(
            "feat: 加个任务",
            "## 影响分类\n\n| 类 | 说明 |\n|---|---|\n| ③ | 数据库迁移,合前先落库 |",
            ["chengyinhub-admin/sql/migration_x.sql"]))

    # ── 以下三条负控:锚点+窗口 换掉裸子串匹配的理由 ──────────────────
    # 旧规则 _MIGRATION_MARKERS = ("③", "迁移") 是裸子串匹配,与真相【反相关】。
    # 这三条在旧规则下【全部会绿】(即放行),那才是病。

    def test_负控_写不含迁移也算声明必须红(self):
        """★ 最反直觉的一条:「本 PR 不含迁移」含「迁移」二字 ⇒ 旧规则判为已声明并放行。

        说自己没有迁移 = 完成了迁移声明,这是把门禁焊死在开的位置上。
        """
        problem = check_pr_declaration(
            "feat: 改个样式", "本 PR 不含迁移。",
            ["chengyinhub-admin/sql/migration_x.sql"])
        self.assertIsNotNone(problem)

    def test_负控_只写迁移二字不算声明必须红(self):
        """正文顺嘴提一句「迁移」不是声明。旧规则会放行。"""
        problem = check_pr_declaration(
            "feat: 加任务 + 迁移", "参考上次迁移的做法写的",
            ["chengyinhub-admin/sql/migration_x.sql"])
        self.assertIsNotNone(problem)

    def test_负控_列举序号三不算声明必须红(self):
        """本仓 ①②③ 常用作列举序号(#80「### ③ NPC 收编进网关」),与影响分类无关。

        没有「影响分类」锚点时,光有 ③ 不算声明 —— 否则门禁被自己的行文习惯架空。
        """
        problem = check_pr_declaration(
            "feat(ai): AI 治理三件事", "### ③ NPC 收编进网关\n收编完成。",
            ["chengyinhub-admin/sql/migration_x.sql"])
        self.assertIsNotNone(problem)

    # ── 否定的 ③ 不算声明:「影响分类」段里写「无 ③」= 说自己没迁移 ─────────
    # 与「不含迁移」同一个病:锚点+窗口 只看 ③ 在不在,不看它被否定了没有。
    # 形状取自真实 PR 描述(#1143/#1148/#1151/#1152/#1157/#1161/#517/#603/#702/#866/#951/#1127/#922)。

    def test_负控_影响分类里写无三必须红(self):
        """★ 本条的主目标:「## 部署影响分类\n- 无 ③」旧规则放行。"""
        problem = check_pr_declaration(
            "fix: 改个判断", "## 部署影响分类\n- 无 ③",
            ["chengyinhub-admin/sql/migration_x.sql"])
        self.assertIsNotNone(problem)

    def test_负控_各种否定写法都必须红(self):
        for body in ("## 影响分类\n- 无 ① / ③ / ④",
                     "## 影响分类\n- 无 ①后端 / ③迁移 / ④CI",
                     "## 影响分类\n- 没有 ③ 迁移",
                     "## 影响分类\n- 不含 ③",
                     "## 影响分类\n- no ③ migration",
                     "影响分类:无 ③",
                     "## 影响分类\n- ③ 迁移：**无**　④ CI：无",
                     "## 影响分类\n- ③ 数据库迁移/数据：**否**（无 sql）",
                     "## 影响分类\n- **③ 无**：无 SQL 迁移",
                     "## 影响分类\n- **③ 无迁移**；**④ 无 CI 改动**。",
                     "## 影响分类\n- ①③④⑤：无。",
                     "## 影响分类\n命中 ① **0**、③ **0**",
                     "## 影响分类\n未命中 ①后端运行时 / ③数据库迁移 / ④CI 控制面",
                     "## 影响分类\n只改小程序。零 ①/③/④。",
                     "## 影响分类\n| ③ 数据库迁移 | **无** | 不需要落库 |",
                     "## 影响分类\n| ①②③⑤ | ❌ | 无后端运行时 / 无迁移 |",
                     "## 影响分类\n- ③ 数据库迁移：不命中",
                     "## 影响分类\n- [ ] ③ 数据库迁移/数据",
                     "## 影响分类\n- **③ 数据库迁移** —— **无**。两列早已在库",
                     "## 影响分类\n- ③ 不涉及",
                     "## 影响分类\n- ③ 迁移：不需要",
                     "## 影响分类\n- ③ 迁移：N/A",
                     "## 影响分类\n| ③ 数据库迁移/数据 | ✗ | 无 SQL,无需落库 |",
                     "## 影响分类\n①后端运行时:否 · ③数据库迁移:否 · ④CI:否"):
            with self.subTest(body=body):
                self.assertIsNotNone(check_pr_declaration(
                    "fix: x", body, ["chengyinhub-admin/sql/migration_x.sql"]))

    def test_真迁移PR的肯定写法仍通过(self):
        """反向护栏:同行别处出现「无/不」不能把真声明判成否定(#1113/#1097/#1134/#1091)。"""
        for body in ("## 影响分类\n- ③ 数据库迁移(仅 sys_job 注册 seed,无 schema 改动):`migration_x.sql`",
                     "## 影响分类\n- ③ 迁移（**合前先落库**）· ① 后端运行时 · ④ ci/",
                     "## 影响分类\n- ③数据库迁移/数据：合并前须单独授权并执行 migration_x.sql",
                     "## 影响分类\n- **③ 迁移 → 已落库 ✅**：`migration_x.sql`",
                     "## 影响分类\n- 无 ②(不动小程序)· ③ 迁移:合前先落库",
                     "## 影响分类\n- 无小程序改动,③ 迁移合前落库",
                     "## 影响分类\n- ③ 迁移(x.sql)· ④ CI：无",
                     "## 影响分类\n- ③ 迁移 — 无 schema 改动,仅 insert sys_job",
                     "## 影响分类\n- ③ 迁移：无损加列 migration_x.sql",
                     "## 影响分类\n- ③ 迁移：否则部署报 Unknown column,合前先落库",
                     "## 影响分类\n- ③ 迁移:0_init.sql 先落库",
                     "## 影响分类\n- ③ migration: no-op backfill, apply first"):
            with self.subTest(body=body):
                self.assertIsNone(check_pr_declaration(
                    "feat: x", body, ["chengyinhub-admin/sql/migration_x.sql"]))


class 依赖顺序门禁(unittest.TestCase):
    """门禁 5:一批未落库的迁移,按【文件名顺序】跑必须满足批内依赖。

    真病例(2026-08-18 在生产 schema 快照上实测):探店日候选 25 条按文件名跑,
    migration_20260817_tandianri_r7_distribution_backfill.sql 第 1 句就
    1054 Unknown column 'organizer.declared_by' —— 加那一列的
    migration_shop_day_organizer_actor_20260815.sql 文件名排在它后面('2' < 's')。
    """

    全仓 = [
        "migration_shop_day_organizer_actor_20260815.sql",
        "migration_20260817_tandianri_r7_distribution_backfill.sql",
        "migration_product_role_20260716.sql",
        "migration_web_admin_roles_20260719.sql",     # 已在 master,不在本批
    ]
    依赖 = {
        "20260817_tandianri_r7_distribution_backfill": ["shop_day_organizer_actor_20260815"],
        "shop_day_organizer_actor_20260815": ["product_role_20260716"],
    }

    def test_负控_依赖排在后面必须红且点名是哪两条(self):
        """★ 本门禁的主目标。它坏了,落库那一刻才会发现,而那时已经在生产上了。"""
        批 = ["migration_20260817_tandianri_r7_distribution_backfill.sql",
              "migration_shop_day_organizer_actor_20260815.sql"]
        v = check_dependency_order(批, self.全仓, self.依赖)
        self.assertEqual(
            v, [("migration_20260817_tandianri_r7_distribution_backfill.sql",
                 "migration_shop_day_organizer_actor_20260815.sql")],
            "没精确点名是哪两条的顺序错了 —— 只说「顺序有问题」等于让人自己去找")

    def test_顺序正确必须绿(self):
        """收紧过头会制造假故障:把被依赖者改名排到前面就该转绿。"""
        全仓 = ["migration_20260814_organizer_actor.sql",
                "migration_20260817_tandianri_r7_distribution_backfill.sql"]
        依赖 = {"20260817_tandianri_r7_distribution_backfill": ["20260814_organizer_actor"]}
        self.assertEqual(check_dependency_order(全仓, 全仓, 依赖), [])

    def test_依赖在批外的不算违反(self):
        """指向 master 里早已落库的迁移:它已经就位了,本批顺序管不着也不该管。

        不区分这一条,门禁会把 master 上 16 对历史「违反」全报出来 ⇒ 永久红 ⇒ 被绕过。
        """
        批 = ["migration_20260816_tandianri_batch3_funds_club_payee.sql"]
        全仓 = 批 + ["migration_web_admin_roles_20260719.sql"]
        依赖 = {"20260816_tandianri_batch3_funds_club_payee": ["web_admin_roles_20260719"]}
        self.assertEqual(check_dependency_order(批, 全仓, 依赖), [])

    def test_负控_同一批里第二对也要抓到(self):
        """只抓第一对就停 = 修完一条又红一条,人会以为门禁在抖。"""
        批 = ["migration_20260817_tandianri_r6_funds_final_closure.sql",
              "migration_20260817_tandianri_r7_distribution_backfill.sql",
              "migration_product_role_20260716.sql",
              "migration_shop_day_organizer_actor_20260815.sql"]
        依赖 = {
            "20260817_tandianri_r6_funds_final_closure": ["product_role_20260716"],
            "20260817_tandianri_r7_distribution_backfill": ["shop_day_organizer_actor_20260815"],
        }
        self.assertEqual(len(check_dependency_order(批, 批, 依赖)), 2)

    def test_依赖指向仓库里没有的迁移不崩(self):
        """依赖声明可能引用被删/改名的文件。这里只管顺序,别在这条上炸掉整道门禁。"""
        批 = ["migration_a_20260101.sql"]
        self.assertEqual(check_dependency_order(批, 批, {"a_20260101": ["ghost_00000000"]}), [])

    def test_负控_自己依赖自己也要抓(self):
        """dep_name >= name 里的等号:自依赖是声明写错了,不能静默放行。"""
        批 = ["migration_a_20260101.sql"]
        self.assertEqual(len(check_dependency_order(批, 批, {"a_20260101": ["a_20260101"]})), 1)


class 依赖声明门禁(unittest.TestCase):
    """门禁 5 的另一半:本批新增的迁移必须有依赖声明,不写不算过。

    没有这一条,顺序门可以一边报「0 对违反」一边有一批文件从来没被看过 ——
    实测候选那 25 条新增里有 5 条(20%)在 dependencies.json 里没有条目。
    """

    def test_负控_新增迁移没有依赖条目必须红并点名(self):
        batch = ["migration_20260817_a.sql", "migration_20260817_b.sql"]
        deps = {"20260817_a": []}
        self.assertEqual(check_dependency_declared(batch, deps), ["migration_20260817_b.sql"])

    def test_空依赖数组算已声明(self):
        """写 [] 是一句「我确认它不依赖任何其它迁移」的明确断言,不是省略。"""
        self.assertEqual(check_dependency_declared(["migration_x_20260817.sql"], {"x_20260817": []}), [])

    def test_有依赖也算已声明(self):
        self.assertEqual(
            check_dependency_declared(["migration_x_20260817.sql"], {"x_20260817": ["y_20260101"]}), [])

    def test_空批不红(self):
        self.assertEqual(check_dependency_declared([], {}), [])

    def test_负控_全都没声明时要全部点名不能只报第一条(self):
        """只报第一条 = 修完一条又红一条,人会以为门禁在抖(同 dep-order 那条的理由)。"""
        batch = ["migration_a_20260817.sql", "migration_b_20260817.sql", "migration_c_20260817.sql"]
        self.assertEqual(len(check_dependency_declared(batch, {})), 3)


class 依赖顺序_collation双序(unittest.TestCase):
    """G6:门禁比的是码点序,人手里的 ls 是 locale 序 —— 实测本仓 74 处位置不同。

    只比码点序 ⇒「门禁绿」不等于「按 ls 跑安全」,而这道门禁的全部价值就建立在
    「人会按 ls 顺序跑」上。判据改成:依赖必须在【两种序】下都排在前面。
    """

    def test_负控_码点序满足但locale序不满足必须红(self):
        """migration_club.sql vs migration_club_apply_fields.sql 就是这一族:
        码点序 '.'(0x2E) < '_'(0x5F) ⇒ club.sql 在前;
        忽略标点后 'club' < 'clubapplyfields' ⇒ 也在前……换个方向才打架,见下面用例。
        这里造一对真打架的:x_y vs xy。
        """
        batch = ["migration_xy_20260101.sql", "migration_x_y_20260101.sql"]
        # 码点序:'_'(0x5F) < 'y'(0x79) ⇒ x_y 在前;忽略标点:'xy20260101' == 'xy20260101' 前缀相同,
        # 但 x_y 去标点后是 'xy20260101',xy 去标点后也是 'xy20260101' ⇒ 模型序判为「不小于」⇒ 红。
        deps = {"xy_20260101": ["x_y_20260101"]}
        v = check_dependency_order(batch, batch, deps)
        self.assertEqual(len(v), 1, "两种序打架的一对没被判红 —— 那门禁绿不代表 ls 跑安全")

    def test_两种序都满足则绿(self):
        batch = ["migration_20260817_late.sql", "migration_20260101_early.sql"]
        deps = {"20260817_late": ["20260101_early"]}
        self.assertEqual(check_dependency_order(batch, batch, deps), [])

    def test_负控_两种序都不满足当然红(self):
        batch = ["migration_20260101_early.sql", "migration_20260817_late.sql"]
        deps = {"20260101_early": ["20260817_late"]}
        self.assertEqual(len(check_dependency_order(batch, batch, deps)), 1)

    def test_collation_key_忽略标点并小写(self):
        self.assertEqual(_collation_key("migration_club.sql"), "migrationclubsql")
        self.assertEqual(_collation_key("migration_Club-X.sql"), "migrationclubxsql")


class 依赖声明棘轮(unittest.TestCase):
    """G5:依赖声明【只许加不许删】。

    没有这一条,顺序门一行 JSON 就能消掉 —— 把 A 依赖 B 那条边删了,门看不见边、当场转绿,
    而落库时该 1054 还是 1054。「闸红了就把闸拆了」是最省事的绕法。
    """

    def test_负控_删掉一条依赖边必须红并点名(self):
        lost = check_dependency_ratchet({"a": []}, {"a": ["b"]})
        self.assertEqual(len(lost), 1)
        self.assertIn("a", lost[0])
        self.assertIn("b", lost[0])

    def test_负控_删掉整条条目必须红(self):
        lost = check_dependency_ratchet({}, {"a": ["b"]})
        self.assertEqual(len(lost), 1)
        self.assertIn("整条删掉", lost[0])

    def test_加边放行(self):
        self.assertEqual(check_dependency_ratchet({"a": ["b", "c"]}, {"a": ["b"]}), [])

    def test_加新条目放行(self):
        self.assertEqual(check_dependency_ratchet({"a": ["b"], "z": []}, {"a": ["b"]}), [])

    def test_原样不动放行(self):
        self.assertEqual(check_dependency_ratchet({"a": ["b"]}, {"a": ["b"]}), [])

    def test_负控_一次删多条要全部点名(self):
        """只报第一条 = 修完一条又红一条,人会以为门禁在抖。"""
        lost = check_dependency_ratchet({"a": []}, {"a": ["b", "c", "d"]})
        self.assertEqual(len(lost), 3)


class SharedLedgerExecutionOrder(unittest.TestCase):
    def test_notice_contract_dump_keeps_reviewed_empty_ast_fields(self):
        import ast
        # Fixed 3.11-format serialization: hosted 3.14's compact default must
        # not change source fingerprints, nor may we refresh the approved hashes.
        self.assertEqual(
            "Call(func=Name(id='connect', ctx=Load()), args=[], keywords=[])",
            _schema_contract_dump(ast.parse("connect()", mode="eval").body))
        self.assertNotEqual(
            _schema_contract_dump(ast.parse("connect()", mode="eval").body),
            _schema_contract_dump(ast.parse("connect(database='foreign')", mode="eval").body))

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ledger-order-gate-")
        sql = os.path.join(self.tmp, "chengyinhub-admin/sql")
        os.makedirs(sql)
        for name in ("a_child", "z_parent"):
            with open(os.path.join(sql, "migration_" + name + ".sql"), "w") as handle:
                handle.write("SELECT 1;\n")
        self.dependencies = {"a_child": ["z_parent"], "z_parent": []}
        self.batch = ["migration_a_child.sql", "migration_z_parent.sql"]

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def manifest(self, dependencies=None):
        from migration_ledger import build_manifest
        return build_manifest(self.tmp, "a" * 40,
                              dependencies=self.dependencies if dependencies is None else dependencies)

    def test_real_ledger_resolves_filename_conflict_and_covers_all_edges(self):
        self.assertTrue(check_dependency_order(self.batch, self.batch, self.dependencies))
        self.assertEqual(check_manifest_dependency_order(self.manifest(), self.batch,
                                                         self.dependencies), [])

    def test_missing_edge_reversed_order_and_missing_batch_path_are_rejected(self):
        original = self.manifest()
        mutations = []
        missing_edge = copy.deepcopy(original)
        missing_edge["migrations"][1]["dependsOn"] = []
        mutations.append(missing_edge)
        reversed_order = copy.deepcopy(original)
        reversed_order["migrations"].reverse()
        for index, migration in enumerate(reversed_order["migrations"], 1):
            migration["order"] = index
        mutations.append(reversed_order)
        missing_path = copy.deepcopy(original)
        missing_path["migrations"] = missing_path["migrations"][:1]
        mutations.append(missing_path)
        duplicate_path = copy.deepcopy(original)
        duplicate_path["migrations"].append(duplicate_path["migrations"][0])
        mutations.append(duplicate_path)
        for manifest in mutations:
            with self.subTest(manifest=manifest):
                self.assertTrue(check_manifest_dependency_order(manifest, self.batch,
                                                                self.dependencies))

    def test_real_ledger_rejects_missing_dependency_and_cycle(self):
        from migration_ledger import LedgerError
        for dependencies in ({"a_child": ["absent"]},
                             {"a_child": ["z_parent"], "z_parent": ["a_child"]}):
            with self.subTest(dependencies=dependencies), self.assertRaises(LedgerError):
                self.manifest(dependencies)

    def _schema_source(self):
        with open(os.path.join(REPO_ROOT, "scripts/e2e/build_schema.py"), encoding="utf-8") as handle:
            return handle.read()

    def _assert_schema_mutations_rejected(self, source, mutations):
        import ast
        for name, needle, replacement in mutations:
            with self.subTest(mutation=name):
                self.assertIn(needle, source, "mutation must target the actual approved runner")
                mutated = source.replace(needle, replacement)
                self.assertNotEqual(source, mutated, "a no-op is not a negative control")
                ast.parse(mutated)  # Rejection must prove the contract, not broken Python indentation.
                self.assertTrue(check_schema_manifest_order_source(mutated))

    def test_actual_schema_runner_uses_ledger_order_and_mutations_are_rejected(self):
        source = self._schema_source()
        self.assertEqual(check_schema_manifest_order_source(source), [])
        stop = "if not ok and (strict_notice or not a.keep_going):"
        keep = "keep_going=False if strict_notice else a.keep_going,"
        loop = ('        for m in ordered:\n'
                '            strict_notice = ((bool(notice_forward) and m["migrationId"] == NOTICE_V2)\n'
                '                             or (bool(broadcast_forward) and m["migrationId"] == BROADCAST_UNION))\n'
                '            ok = run_file(cur, REPO / m["path"], m["migrationId"], results,\n'
                '                          keep_going=False if strict_notice else a.keep_going,\n'
                '                          schema_only=True, observations=observations)\n'
                '            if not ok and (strict_notice or not a.keep_going):\n'
                '                break\n')
        self.assertIn(loop, source)
        nested = '        if a.keep_going:\n' + ''.join('    ' + line for line in loop.splitlines(True))
        mutations = [
            ("foreign manifest import", "from ci.migration_ledger import build_manifest,", "from unrelated import build_manifest,"),
            ("sort manifest", 'all_migrations = manifest["migrations"]', 'all_migrations = sorted(manifest["migrations"])'),
            ("delete selection", "selected = migrations_after_baseline(all_migrations)", "selected = []"),
            ("reverse iteration", "for m in ordered:", "for m in reversed(ordered):"),
            ("mutate ordered", "        for m in ordered:", "        ordered.reverse()\n        for m in ordered:"),
            ("foreign path", 'REPO / m["path"], m["migrationId"]', 'REPO / "other.sql", m["migrationId"]'),
            ("remove failure stop", stop, "if False:"),
            ("invert keep-going", stop, "if not ok and (strict_notice or a.keep_going):"),
            ("force keep-going", keep, "keep_going=True,"),
            ("lose result", "            ok = run_file(cur, REPO / m", "            run_file(cur, REPO / m"),
            ("wrong finally", "        c.close()", "        c.commit()"),
            ("reorder selection", '    all_migrations = manifest["migrations"]\n    selected = migrations_after_baseline(all_migrations)',
             '    selected = migrations_after_baseline(all_migrations)\n    all_migrations = manifest["migrations"]'),
            ("nested loop", loop, nested),
            ("duplicate loop", loop, loop + loop),
            ("additional reversed loop", loop, loop + loop.replace("for m in ordered:", "for m in reversed(ordered):")),
            ("continue after failure", loop, loop.replace("break", "continue")),
        ]
        self._assert_schema_mutations_rejected(source, mutations)

    def test_notice_forward_identity_dependency_and_replacement_mutations_are_rejected(self):
        source = self._schema_source()
        self._assert_schema_mutations_rejected(source, [
            ("drop pending pair count", 'if len(rows) != 1 or len(pending) != 1:', 'if False:'),
            ("ignore pending identity", 'or pending_entry != entry', 'or False'),
            ("ignore manifest fingerprint", 'entry.get("sha256") != expected_sha', 'False'),
            ("ignore file fingerprint", 'hashlib.sha256(target.read_bytes()).hexdigest() != expected_sha', 'False'),
            ("allow symlink", 'target.is_symlink() or not target.is_file()', 'not target.is_file()'),
            ("ignore declared order", 'entry.get("order") != index + 1', 'False'),
            ("allow postdeploy pair", 'entry.get("executionPhase") is not None', 'False'),
            ("drop dependency check", 'if entry.get("dependsOn") != expected_deps:', 'if False:'),
            ("invent dependency", '["20261013_b_im_notification_intent"] if migration_id == NOTICE_PRODUCER', '["another_parent"] if migration_id == NOTICE_PRODUCER'),
            ("drop upstream guard", 'if len(upstream) != 1 or upstream[0] >= positions[NOTICE_PRODUCER]:', 'if False:'),
            ("reverse pair order", 'positions[NOTICE_PRODUCER] < positions[NOTICE_OLD] < positions[NOTICE_V2]', 'positions[NOTICE_PRODUCER] < positions[NOTICE_V2] < positions[NOTICE_OLD]'),
            ("reverse pending order", 'pending_positions[NOTICE_PRODUCER] < pending_positions[NOTICE_OLD] < pending_positions[NOTICE_V2]', 'pending_positions[NOTICE_PRODUCER] < pending_positions[NOTICE_V2] < pending_positions[NOTICE_OLD]'),
            ("fake old identity", 'NOTICE_OLD = "20261024_c_before_start_notice_typed"', 'NOTICE_OLD = "other"'),
            ("fake v2 identity", 'NOTICE_V2 = "20261024_g_before_start_notice_metadata_v2"', 'NOTICE_V2 = "other"'),
            ("fake producer identity", 'NOTICE_PRODUCER = "20261024_d_committed_official_notice_wx"', 'NOTICE_PRODUCER = "other"'),
            ("fake path", '"chengyinhub-admin/sql/migration_20261024_g_before_start_notice_metadata_v2.sql",', '"chengyinhub-admin/sql/other.sql",'),
            ("fake approved file digest", 'a36889f13d1de308edfcba06409ef153438521268f355881006864ae842bc02c', '0' * 64),
            ("replace v2 instead of old", '"selected": [row for row in selected if row["migrationId"] != NOTICE_OLD]', '"selected": [row for row in selected if row["migrationId"] != NOTICE_V2]'),
            ("silently retain both", '"selected": [row for row in selected if row["migrationId"] != NOTICE_OLD]', '"selected": selected'),
            ("foreign host allowed", 'ALLOWED_HOSTS = {"127.0.0.1", "localhost", "::1"}', 'ALLOWED_HOSTS = {"127.0.0.1", "localhost", "::1", "foreign"}'),
            ("foreign schema allowed", 'SCRATCH_SCHEMA = re.compile(r"cy_e2e_[A-Za-z0-9_]+\\Z")', 'SCRATCH_SCHEMA = re.compile(r".*")'),
            ("connect foreign database", 'database=db, autocommit=True', 'database="foreign", autocommit=True'),
            ("skip loopback check", 'if a.host not in ALLOWED_HOSTS:', 'if False:'),
            ("skip scratch check", 'if not SCRATCH_SCHEMA.fullmatch(a.schema):', 'if False:'),
            ("shadow selector", '    notice_forward = notice_forward_plan(all_migrations, selected)',
             '    notice_forward_plan = lambda *args: {}\n    notice_forward = notice_forward_plan(all_migrations, selected)'),
            ("mutate pinned identities", '    notice_forward = notice_forward_plan(all_migrations, selected)',
             '    NOTICE_FORWARD_FILES[NOTICE_OLD] = ("fake.sql", "0" * 64)\n    notice_forward = notice_forward_plan(all_migrations, selected)'),
        ])

    def test_notice_forward_fresh_preparation_and_strict_execution_mutations_are_rejected(self):
        source = self._schema_source()
        self._assert_schema_mutations_rejected(source, [
            ("skip selector", 'notice_forward = notice_forward_plan(all_migrations, selected)', 'notice_forward = None'),
            ("swap selector inputs", 'notice_forward_plan(all_migrations, selected)', 'notice_forward_plan(selected, all_migrations)'),
            ("drop bypass", '        if notice_forward or broadcast_forward:\n', '        if (notice_forward or broadcast_forward) and not a.drop:\n'),
            ("invert fresh readback", 'fresh[0] != 0', 'fresh[0] < 0'),
            ("allow noninteger readback", 'type(fresh[0]) is not int', 'False'),
            ("probe foreign schema", 'schema_name=%s", (a.schema,)', 'schema_name=%s", ("foreign",)'),
            ("adopt concurrent schema", '"CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4"', '"CREATE DATABASE IF NOT EXISTS `%s` DEFAULT CHARACTER SET utf8mb4"'),
            ("delete existing before probe", '        if notice_forward or broadcast_forward:\n', '        if notice_forward or broadcast_forward:\n            cur.execute("DROP DATABASE IF EXISTS `%s`" % a.schema)\n'),
            ("drop exact replacement selection", 'selected = notice_forward["selected"] if notice_forward else selected', 'selected = selected'),
            ("reverse replacement selection", 'selected = notice_forward["selected"] if notice_forward else selected', 'selected = list(reversed(notice_forward["selected"])) if notice_forward else selected'),
            ("hide replacement receipt", 'REPLACED_UNAPPLIED %s -> %s fingerprints=%s', 'APPLIED %s -> %s fingerprints=%s'),
            ("delete initial ordered", '    ordered = [m for m in selected if m.get("executionPhase") != "POST_DEPLOY"]\n', ''),
            ("add initial skip", 'ordered = [m for m in selected if m.get("executionPhase") != "POST_DEPLOY"]', 'ordered = [m for m in selected if m.get("executionPhase") != "POST_DEPLOY" and m["migrationId"] != "other"]'),
            ("wrong deferred phase", 'deferred = [m for m in selected if m.get("executionPhase") == "POST_DEPLOY"]', 'deferred = []'),
            ("missing validation", '    validate_target(a)\n    if a.policy_source_custody:', '    if a.policy_source_custody:'),
            ("change validated schema", '    notice_forward = notice_forward_plan(all_migrations, selected)', '    a.schema = "foreign"\n    notice_forward = notice_forward_plan(all_migrations, selected)'),
            ("foreign reconnect", '    c = connect(a, a.schema)', '    c = connect(a, "foreign")'),
            ("additional connection", '    c = connect(a, a.schema)', '    c = connect(a, a.schema)\n    c = connect(a, "foreign")'),
            ("modify selected item", '    notice_forward = notice_forward_plan(all_migrations, selected)', '    selected[0] = {}\n    notice_forward = notice_forward_plan(all_migrations, selected)'),
            ("modify replacement result", '    if deferred:\n', '    notice_forward["selected"] = selected\n    if deferred:\n'),
            ("disable strict v2", 'bool(notice_forward) and m["migrationId"] == NOTICE_V2', 'False'),
            ("strict wrong migration", 'bool(notice_forward) and m["migrationId"] == NOTICE_V2', 'bool(notice_forward) and m["migrationId"] == NOTICE_OLD'),
            ("v2 keep-going", 'keep_going=False if strict_notice else a.keep_going,', 'keep_going=a.keep_going,'),
            ("v2 ignores failure", 'if not ok and (strict_notice or not a.keep_going):', 'if not ok and not a.keep_going:'),
            ("wrong label", 'REPO / m["path"], m["migrationId"]', 'REPO / m["path"], "other"'),
            ("wrong schema_only", 'schema_only=True, observations=observations)', 'schema_only=False, observations=observations)'),
            ("added loop skip", '            strict_notice = ((bool(notice_forward)', '            if m["migrationId"] == "other":\n                continue\n            strict_notice = ((bool(notice_forward)'),
            ("extra run_file", '        for m in ordered:', '        run_file(cur, REPO / "other.sql", "other", results, keep_going=True)\n        for m in ordered:'),
            ("extra nonledger loop", '        for m in ordered:', '        for row in selected:\n            print(row)\n        for m in ordered:'),
        ])


    def test_broadcast_forward_identity_dependency_and_replacement_mutations_are_rejected(self):
        source = self._schema_source()
        start = source.index('BROADCAST_D = ')
        end = source.index('\ndef main():', start)
        block = source[start:end]
        controls = [
            ("drop pending pair count", 'if len(rows) != 1 or len(pending) != 1:', 'if False:'),
            ("ignore pending identity", 'or pending_entry != entry', 'or False'),
            ("ignore manifest fingerprint", 'entry.get("sha256") != expected_sha', 'False'),
            ("ignore file fingerprint", 'hashlib.sha256(target.read_bytes()).hexdigest() != expected_sha', 'False'),
            ("allow symlink", 'target.is_symlink() or not target.is_file()', 'not target.is_file()'),
            ("ignore declared order", 'entry.get("order") != index + 1', 'False'),
            ("allow postdeploy", 'entry.get("executionPhase") is not None', 'False'),
            ("drop dependency check", 'entry.get("dependsOn") != deps', 'False'),
            ("invent dependency", '[BROADCAST_D, BROADCAST_OLD] if key == BROADCAST_UNION', '["another_parent"] if key == BROADCAST_UNION'),
            ("drop upstream guard", 'len(upstream) != 1 or upstream[0] >= positions[BROADCAST_D]', 'False'),
            ("reverse pair order", 'positions[BROADCAST_D] < positions[BROADCAST_OLD] < positions[BROADCAST_UNION]', 'positions[BROADCAST_D] < positions[BROADCAST_UNION] < positions[BROADCAST_OLD]'),
            ("reverse pending order", 'pending_positions[BROADCAST_D] < pending_positions[BROADCAST_OLD] < pending_positions[BROADCAST_UNION]', 'pending_positions[BROADCAST_D] < pending_positions[BROADCAST_UNION] < pending_positions[BROADCAST_OLD]'),
            ("fake D identity", 'BROADCAST_D = "20261023_d_official_broadcast_delivery"', 'BROADCAST_D = "other"'),
            ("fake old identity", 'BROADCAST_OLD = "20261026_official_broadcast_delivery"', 'BROADCAST_OLD = "other"'),
            ("fake union identity", 'BROADCAST_UNION = "20261028_official_broadcast_protocol_union"', 'BROADCAST_UNION = "other"'),
            ("fake path", '"chengyinhub-admin/sql/migration_20261028_official_broadcast_protocol_union.sql",', '"chengyinhub-admin/sql/other.sql",'),
            ("fake approved digest", '44dc1ee52ab1591f0e893f26aaed8b6b1af4948ab53f98307c41a30c637b8b8b', '0' * 64),
            ("replace union instead of old", '"selected": [row for row in selected if row["migrationId"] != BROADCAST_OLD]', '"selected": [row for row in selected if row["migrationId"] != BROADCAST_UNION]'),
            ("silently retain both", '"selected": [row for row in selected if row["migrationId"] != BROADCAST_OLD]', '"selected": selected'),
        ]
        mutations = []
        for name, needle, replacement in controls:
            self.assertIn(needle, block, "broadcast control must target its actual selector only")
            mutations.append(("broadcast " + name, block, block.replace(needle, replacement)))
        self._assert_schema_mutations_rejected(source, mutations)

    def test_broadcast_forward_fresh_preparation_and_strict_execution_mutations_are_rejected(self):
        source = self._schema_source()
        self._assert_schema_mutations_rejected(source, [
            ("skip broadcast selector", 'broadcast_forward = broadcast_forward_plan(all_migrations, selected)', 'broadcast_forward = None'),
            ("swap broadcast inputs", 'broadcast_forward_plan(all_migrations, selected)', 'broadcast_forward_plan(selected, all_migrations)'),
            ("omit broadcast absence gate", 'if notice_forward or broadcast_forward:', 'if notice_forward:'),
            ("require both before fresh gate", 'if notice_forward or broadcast_forward:', 'if notice_forward and broadcast_forward:'),
            ("drop broadcast replacement", 'selected = broadcast_forward["selected"] if broadcast_forward else selected', 'selected = selected'),
            ("reverse broadcast replacement", 'selected = broadcast_forward["selected"] if broadcast_forward else selected', 'selected = list(reversed(broadcast_forward["selected"])) if broadcast_forward else selected'),
            ("additional selected rebind", '    if deferred:\n', '    selected = selected\n    if deferred:\n'),
            ("additional ordered rebind", '        for m in ordered:', '        ordered = ordered\n        for m in ordered:'),
            ("extra migration filter", 'selected = broadcast_forward["selected"] if broadcast_forward else selected', 'selected = [m for m in broadcast_forward["selected"] if m["migrationId"] != "other"] if broadcast_forward else selected'),
            ("rebind broadcast selector", '    broadcast_forward = broadcast_forward_plan(all_migrations, selected)', '    broadcast_forward_plan = lambda *args: {}\n    broadcast_forward = broadcast_forward_plan(all_migrations, selected)'),
            ("mutate broadcast identities", '    broadcast_forward = broadcast_forward_plan(all_migrations, selected)', '    BROADCAST_FORWARD_FILES[BROADCAST_OLD] = ("fake.sql", "0" * 64)\n    broadcast_forward = broadcast_forward_plan(all_migrations, selected)'),
            ("mutate broadcast selection", '    if deferred:\n', '    broadcast_forward["selected"] = selected\n    if deferred:\n'),
            ("disable union strictness", 'bool(broadcast_forward) and m["migrationId"] == BROADCAST_UNION', 'False'),
            ("strict old instead of union", 'bool(broadcast_forward) and m["migrationId"] == BROADCAST_UNION', 'bool(broadcast_forward) and m["migrationId"] == BROADCAST_OLD'),
            ("omit broadcast replacement receipt", '(broadcast_forward, BROADCAST_OLD, BROADCAST_UNION)', '(None, BROADCAST_OLD, BROADCAST_UNION)'),
            ("invent third replacement", '(broadcast_forward, BROADCAST_OLD, BROADCAST_UNION)):', '(broadcast_forward, BROADCAST_OLD, BROADCAST_UNION), (broadcast_forward, "other", "new")):'),
            ("wrong receipt identity", '(broadcast_forward, BROADCAST_OLD, BROADCAST_UNION)', '(broadcast_forward, BROADCAST_UNION, BROADCAST_OLD)'),
            ("mark replacement applied", 'REPLACED_UNAPPLIED %s -> %s fingerprints=%s', 'APPLIED %s -> %s fingerprints=%s'),
        ])

    def test_schema_contract_accepts_only_nonsemantic_layout_changes(self):
        source = self._schema_source()
        layout = source.replace('    notice_forward = notice_forward_plan(all_migrations, selected)',
                                '    # formatting and comments are not contract changes\n'
                                '    notice_forward = notice_forward_plan(\n'
                                '        all_migrations, selected\n    )')
        self.assertNotEqual(source, layout)
        self.assertEqual([], check_schema_manifest_order_source(layout))
        broadcast_layout = source.replace(
            '    broadcast_forward = broadcast_forward_plan(all_migrations, selected)',
            '    # formatting the second fixed selector also preserves its contract\n'
            '    broadcast_forward = broadcast_forward_plan(\n'
            '        all_migrations, selected\n    )')
        self.assertNotEqual(source, broadcast_layout)
        self.assertEqual([], check_schema_manifest_order_source(broadcast_layout))

class 依赖顺序门禁_选批口径(unittest.TestCase):
    """★ 这一组钉的是【批怎么选】,不是【选出来之后怎么判】。

    2026-08-18 的假阳性就出在选批那一层:批用了 --diff-filter=AM,于是
    「已在 master、已在生产落库、本分支只改了一行注释」的迁移也被算进批去比先后。
    候选那边为了消这条违反去回退注释,撞上契约测试才发现改错了。
    把负控只写在 check_dependency_order 上 = 闸放错了层:那个函数当时没坏,坏的是喂给它的批。
    所以这里造真 git 仓库,走 select_added_migrations 的真实路径。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="dep-order-batch-")
        self.sql = os.path.join(self.tmp, "chengyinhub-admin", "sql")
        os.makedirs(self.sql)
        self._git("init", "-q", "-b", "main")
        self._git("config", "user.email", "t@t")
        self._git("config", "user.name", "t")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _git(self, *args):
        import subprocess
        return subprocess.run(["git", "-C", self.tmp] + list(args),
                              capture_output=True, text=True, check=True)

    def _write(self, name, text):
        with open(os.path.join(self.sql, name), "w", encoding="utf-8") as f:
            f.write(text)

    def _commit(self, msg):
        self._git("add", "-A")
        self._git("commit", "-q", "-m", msg)

    def _base(self):
        return self._git("rev-parse", "HEAD").stdout.strip()

    def _dependencies(self, dependencies):
        import json
        path = os.path.join(self.tmp, "ci")
        os.makedirs(path, exist_ok=True)
        with open(os.path.join(path, "migration-dependencies.json"), "w", encoding="utf-8") as handle:
            json.dump({"schemaVersion": 1, "dependencies": dependencies}, handle)

    def _diverged_dependency_refs(self):
        self._dependencies({"original": ["upstream"], "upstream": []})
        self._commit("fixed source declarations")
        base = self._base()
        self._git("checkout", "-q", "-b", "future-master")
        self._dependencies({"original": ["upstream"], "upstream": [], "future": ["original"]})
        self._commit("later independent master declarations")
        future = self._base()
        self._git("update-ref", "refs/remotes/origin/master", future)
        self._git("checkout", "-q", "-b", "candidate", base)
        self._dependencies({"original": ["upstream"], "upstream": [], "candidate": []})
        self._commit("candidate keeps fixed source edges")
        return base, future

    def test_dependency_ratchet_uses_fixed_base_not_future_master(self):
        base, _ = self._diverged_dependency_refs()
        expected = {"original": ["upstream"], "upstream": []}
        self.assertEqual((expected, base), master_dependencies(self.tmp, base))
        self.assertEqual((expected, base), master_dependencies(self.tmp))
        # No new migration is needed to enforce the ratchet; later unrelated
        # master additions must not become false candidate deletions.
        self.assertEqual(0, _cmd_dep_order(self.tmp, ["--base", base]))

    def test_fixed_base_deleted_edge_is_rejected_even_without_new_sql(self):
        base, _ = self._diverged_dependency_refs()
        self._dependencies({"original": [], "upstream": [], "candidate": []})
        self.assertEqual(1, _cmd_dep_order(self.tmp, ["--base", base]))

    def test_fixed_base_deleted_entry_is_rejected_even_without_new_sql(self):
        base, _ = self._diverged_dependency_refs()
        self._dependencies({"upstream": [], "candidate": []})
        self.assertEqual(1, _cmd_dep_order(self.tmp, ["--base", base]))

    def test_unreadable_or_nonancestor_explicit_base_cannot_fallback(self):
        _, future = self._diverged_dependency_refs()
        for base in ("not-a-ref", future):
            with self.subTest(base=base):
                self.assertEqual((None, None), master_dependencies(self.tmp, base))
                self.assertEqual(1, _cmd_dep_order(self.tmp, ["--base", base]))

    def test_负控_只改了历史迁移的注释不许进批(self):
        """★ 本次假阳性的回归钉子。

        历史迁移已在生产跑过,本分支只改它一行注释 —— 它不在本次落库批次里,
        不该参与排序,更不该因此报违反。
        """
        self._write("migration_history_20260101.sql", "-- 历史\nALTER TABLE t ADD COLUMN c INT NULL;\n")
        self._commit("master: 历史迁移")
        base = self._base()
        self._git("checkout", "-q", "-b", "feature")
        self._write("migration_history_20260101.sql",
                    "-- 历史(补一句说明,不改 DDL)\nALTER TABLE t ADD COLUMN c INT NULL;\n")
        self._commit("feature: 只改注释")
        self.assertEqual(
            select_added_migrations(self.tmp, base), [],
            "仅修改的历史迁移被算进批了 —— 这正是 2026-08-18 那条假阳性")

    def test_本分支新增的迁移必须进批(self):
        """收紧过头就成了「永远不会红」,那比假阳性更坏。"""
        self._write("migration_history_20260101.sql", "-- 历史\n")
        self._commit("master")
        base = self._base()
        self._git("checkout", "-q", "-b", "feature")
        self._write("migration_20260817_new_a.sql", "-- A\n")
        self._write("migration_20260815_new_b.sql", "-- B\n")
        self._commit("feature: 新增两条")
        self.assertEqual(sorted(select_added_migrations(self.tmp, base)),
                         ["migration_20260815_new_b.sql", "migration_20260817_new_a.sql"])

    def test_负控_端到端_新增两条且依赖排在后面必须红并点名(self):
        """★「不能漏」这一头:选批 + 判定串起来跑一遍,重现候选那条真场景的形状。

        A 依赖 B,但 A 的文件名排在 B 前面 ⇒ 按 ls 顺序跑,A 先跑就会踩空。
        """
        self._write("migration_history_20260101.sql", "-- 历史\n")
        self._commit("master")
        base = self._base()
        self._git("checkout", "-q", "-b", "feature")
        self._write("migration_20260817_r7_distribution_backfill.sql", "-- 依赖 organizer_actor\n")
        self._write("migration_shop_day_organizer_actor_20260815.sql", "-- 加 declared_by 列\n")
        self._commit("feature: 新增两条,顺序是错的")
        batch = select_added_migrations(self.tmp, base)
        deps = {"20260817_r7_distribution_backfill": ["shop_day_organizer_actor_20260815"]}
        v = check_dependency_order(batch, batch + ["migration_history_20260101.sql"], deps)
        self.assertEqual(
            v, [("migration_20260817_r7_distribution_backfill.sql",
                 "migration_shop_day_organizer_actor_20260815.sql")],
            "收紧选批之后把真危险的那一对也漏掉了 —— 那这道门禁就白建了")

    def test_改名一个历史迁移新名字必须进批(self):
        """改名是修这类顺序问题的正规手法(候选就是这么修的)。

        对 master 上的文件改名,三点 diff 会算成 R;没有 --no-renames 的话
        --diff-filter=A 看不见新名字 ⇒ 恰好漏掉刚被改名以修顺序的那个文件。
        """
        self._write("migration_shop_day_organizer_actor_20260815.sql", "-- 加 declared_by\n")
        self._commit("master: 原名")
        base = self._base()
        self._git("checkout", "-q", "-b", "feature")
        self._git("mv", "chengyinhub-admin/sql/migration_shop_day_organizer_actor_20260815.sql",
                  "chengyinhub-admin/sql/migration_20260815_shop_day_organizer_actor.sql")
        self._commit("feature: 改名以修顺序")
        self.assertIn("migration_20260815_shop_day_organizer_actor.sql",
                      select_added_migrations(self.tmp, base),
                      "改名后的新文件名没进批 —— 刚修好的顺序反而没人验")

    def test_辅助前缀的_sql_不进批(self):
        """_precheck_ / _deploy_ / bak_ 不是要落库的迁移。"""
        self._write("migration_history_20260101.sql", "-- 历史\n")
        self._commit("master")
        base = self._base()
        self._git("checkout", "-q", "-b", "feature")
        self._write("_precheck_something_20260817.sql", "-- 只读预检\n")
        self._write("migration_20260817_real.sql", "-- 真迁移\n")
        self._commit("feature")
        self.assertEqual(select_added_migrations(self.tmp, base), ["migration_20260817_real.sql"])

    def test_子目录里的_sql_不进批(self):
        """要落库的迁移只认 chengyinhub-admin/sql 第一层(与 migration-layout 门同口径)。"""
        self._write("migration_history_20260101.sql", "-- 历史\n")
        self._commit("master")
        base = self._base()
        self._git("checkout", "-q", "-b", "feature")
        os.makedirs(os.path.join(self.sql, "baseline"))
        with open(os.path.join(self.sql, "baseline", "migration_nested_20260817.sql"), "w",
                  encoding="utf-8") as f:
            f.write("-- 子目录\n")
        self._commit("feature")
        self.assertEqual(select_added_migrations(self.tmp, base), [])


class 商家俱乐部新增迁移_结构回读合同(unittest.TestCase):
    """IF NOT EXISTS 只代表语句被接受，不能证明同名表结构正确。

    本批每个可重复建表迁移都必须在末尾从目标库回读：InnoDB、关键列，以及 DDL
    声明的每一个业务唯一键的精确有序列。这个测试由 migration-safety-check.sh
    直接执行，避免结构回读只停在人工说明里。
    """

    MIGRATIONS = {
        "migration_business_audit_event_20260823.sql":
            ("business_audit_event",),
        "migration_merchant_operator_20260823.sql":
            ("merchant_operator", "merchant_operator_invite"),
        "migration_merchant_operator_crm_followup_20260823.sql":
            ("merchant_customer_tag_assignment", "merchant_customer_note"),
        "migration_merchant_operator_crm_phase1a_20260823.sql":
            ("merchant_customer_segment", "merchant_crm_export_task"),
        "migration_merchant_operator_crm_phase1a_delivery_20260823.sql":
            ("merchant_crm_marketing_consent", "merchant_crm_campaign",
             "merchant_crm_campaign_recipient", "merchant_crm_delivery_guard"),
        "migration_merchant_review_20260823.sql":
            ("merchant_review", "merchant_review_report"),
        "migration_club_post_announcement_20260823.sql":
            ("club_post", "club_post_revision"),
        "migration_club_role_assignment_20260823.sql":
            ("club_role_assignment",),
        "migration_club_role_governance_20260823.sql":
            ("club_governance_ban", "club_governance_owner_transfer", "club_governance_case"),
        "migration_club_role_event_ops_20260823.sql":
            ("club_event_series", "club_event_occurrence", "club_event_waitlist",
             "club_event_attendance_correction", "club_event_notification_campaign",
             "club_event_notification_delivery", "club_event_notification_outbox"),
        "migration_club_role_membership_20260823.sql":
            ("club_membership_setting", "club_membership_plan", "club_membership_invoice",
             "club_membership_term", "club_membership_refund_review"),
    }

    # 独立真源：CREATE 表体去除格式/注释后的 SHA-256。修改 nullable/default/
    # generated/CHECK 任一承重形状都必须显式更新合同，不能只改运行时回读常量。
    EXACT_SOURCE_TABLE_HASHES = {
        "migration_business_audit_event_20260823.sql": {
            "business_audit_event": "1aef8dffa38020ff454aa4272e7d6686e634656a0fafdea7b7e125c7e8627ecf",
        },
        "migration_merchant_operator_20260823.sql": {
            "merchant_operator": "f7ecedf0bdf526ad063408dd49d50e883567a70fa38604c9b79d3d5130afc11c",
            "merchant_operator_invite": "dab0fbec1e0bb3ae0f220b3f110b8ba60e701d9f14ec469a05a0beaa3b9c9596",
        },
        "migration_merchant_review_20260823.sql": {
            "merchant_review": "ca1cae3891788d97f363c9edb4f913f9cd709a05a3b0ef3c409e92ad6340680f",
            "merchant_review_report": "b795ccc930a011d41b108a0aae851c362bdee942d0730253032dec5d881618f7",
        },
        "migration_club_role_assignment_20260823.sql": {
            "club_role_assignment": "a85611377c5c6e4ff281fe8565428f08b8dbf05ad6299168ae6ed18000955032",
        },
        "migration_club_role_governance_20260823.sql": {
            "club_governance_ban": "327a2bbe39ba1490a7fc8ec4a5285a886373930d496f94640ee6f25426ff35f7",
            "club_governance_owner_transfer": "0da7c45a9b12e2cb4a1ac394c25d79ddcaccf8431a1ce25096555f8d1bb2bb5d",
            "club_governance_case": "5b5dece70f02d8ffc9f9f7c75176d95bc72763b7547d77a23a2154b18ff3154e",
        },
        "migration_club_role_event_ops_20260823.sql": {
            "club_event_series": "bdc1b1587850f3d34a575d9c39eff04344e56d9722d0052613b80ed84f3fb22a",
            "club_event_occurrence": "22c1fdf66c555633c45850985e5689ab61a301fd1f27ad05863db2bec021ea5d",
            "club_event_waitlist": "a035aee9d466a646c0256d520ef6558b864750702ff41050c7e7d5a0c9dd3c5b",
            "club_event_attendance_correction": "e0d0ee77974d7789c0e55ccc98d5e961e726ef26a552a9a499722af2855db9b1",
            "club_event_notification_campaign": "9fbc6b1d5f133083cb5867323ba6c0f1cad4294fa89c1e3d07437adda687956c",
            "club_event_notification_delivery": "5c95e7f89d0e22663de5c35feff9ced564771dfd98d3de5171a1d649040e61a8",
            "club_event_notification_outbox": "31449ec863cbc3dda145295ec032824f7e1e2c2dbba4271d82c7633c63051ce3",
        },
        "migration_merchant_operator_crm_followup_20260823.sql": {
            "merchant_customer_tag_assignment": "ade74ec1274b2d4a4708e471ae3640f2670aa6c4dd90dbcfee99e521f96bc53e",
            "merchant_customer_note": "b62cd3d0c6b3cfc239684cf2d2a4304281167d77052f51bd601a634a4d0e563e",
        },
        "migration_club_post_announcement_20260823.sql": {
            "club_post_revision": "0305864ec0860bcceb0e1b4e5c0459c729d5e493ef237142ba91a87e7114627f",
        },
    }

    # 来自独立本地 MySQL 实际 INFORMATION_SCHEMA 的精确列签名：列名/类型/
    # nullable/default/extra/charset/非 utf8mb4 collation/完整生成表达式。
    EXACT_COLUMN_SIGNATURES = {
        "migration_business_audit_event_20260823.sql": {
            "business_audit_event": (15, "195a299082a792b8e2398c721635b371556132eddbd77ed44dc631b0545e03ef"),
        },
        "migration_merchant_operator_20260823.sql": {
            "merchant_operator": (15, "fad84dd726fbf1b7138ba2e01167220fa324eec996a16bc2b2b2aa82a289e4d3"),
            "merchant_operator_invite": (17, "ea63bad38792d85cc03a7c8051dc189f9140b2dda5bbcf23029896f2f12e4ed1"),
        },
        "migration_merchant_review_20260823.sql": {
            "merchant_review": (23, "15661b6eaa811587c70f6565bb5eceb07e4c6f759d9ffee59dcd57fd44711470"),
            "merchant_review_report": (11, "19d639ddd2039ee83b895787b54025d9dd99e7407083e781e1923f555916e77b"),
        },
        "migration_club_role_assignment_20260823.sql": {
            "club_role_assignment": (19, "ccc3b7adc415ccf486399b075aac12b9efa95e1745b5b4d50635a228634c9c9d"),
        },
        "migration_club_role_governance_20260823.sql": {
            "club_governance_ban": (20, "4b38730d38e2f6b348f12a811be74554349807dbcadeebce3169d43f86adc00c"),
            "club_governance_owner_transfer": (8, "206c444ccf03e678c7f96cab6e73ac4abff6d3173ffc52ef7437120283cfa3b2"),
            "club_governance_case": (20, "2f1322f38f87879fbe1eba05642e50103660ab566a9a90a0c96236e96b30d266"),
        },
        "migration_club_role_event_ops_20260823.sql": {
            "club_event_series": (18, "d5976a29688ce06d82f32518ddcbe0b568238477f7980fd4f6f331391d670f00"),
            "club_event_occurrence": (9, "8cbec1815825c711d9d6d745104d4b3f330739d374831c11b45bb98ae0c02fab"),
            "club_event_waitlist": (13, "6d30563c23642f6b0ee43a6e77684e6e99be7e4726abb7bc7f2412a2ba6fc2d3"),
            "club_event_attendance_correction": (13, "de8608fb34f50d11b12247dbc6aaee5386f2381e0bbf2a3154e2524853973185"),
            "club_event_notification_campaign": (15, "b7bc11e0f1a8301a758da34ec5f777b536565d249b8b68634d3bea9be7c76316"),
            "club_event_notification_delivery": (12, "fbf79ae33f6fa32e56bc0f31ea6def90ee92633c565f48278656bce2520f0f98"),
            "club_event_notification_outbox": (6, "e2b2a8b6d56022ba2d23ba7188f810a2580f1d9b1c0f235fd523566ac217bee6"),
        },
        "migration_merchant_operator_crm_followup_20260823.sql": {
            "merchant_customer_tag_assignment": (9, "bb8d1b130e402a43ef35e8b3486d9d1a9b904d8776a4ac5e3201f59355d47c22"),
            "merchant_customer_note": (14, "81548a731b1acd71d5618bd75a83fb887219b9c5dbe7fb2bb01d648dcbb06785"),
        },
        "migration_club_post_announcement_20260823.sql": {
            "club_post": (4, "cd1708419c1b0faeae7e33480385bdd5cccedc2374ddaf3220a4c50f66c15596"),
            "club_post_revision": (9, "77b527b91cf0223e5448ff56b03b262540ca583f77407c1a62fbcfece0d87da4"),
        },
    }

    # CHECK 签名同时固定名称、ENFORCED 和保留括号分组的完整 CHECK_CLAUSE。
    EXACT_CHECK_SIGNATURES = {
        "migration_merchant_operator_20260823.sql": {
            "merchant_operator": (2, "a9212f9b3cd42111b861b555c21c2b0b42444db92da1a2b4c52b6b13a86577a8"),
            "merchant_operator_invite": (2, "84dbf097e1f586edba1177c2645affb4a1447ecdd5d3fbce80b017d99982a2dd"),
        },
        "migration_merchant_review_20260823.sql": {
            "merchant_review": (2, "a5ffb90520794fa14adad2b446c4bef2819c2b92335eb658e4989abfa7e5d51a"),
        },
        "migration_club_role_assignment_20260823.sql": {
            "club_role_assignment": (3, "040e56193b66e9ad8e0fbc94f2d1c5c2b5975c7d15a0ac1c7598fb41714ef0bd"),
        },
        "migration_club_role_governance_20260823.sql": {
            "club_governance_ban": (3, "f86a88e011fb7317602243bf8dd8ad499613046178c8a250d8ec72d41219702b"),
            "club_governance_owner_transfer": (1, "51009325bbc6ba7e3a3e0f54b580dc26b3d1b001714158eb265f2103467342d8"),
            "club_governance_case": (4, "535473dc2fe9455acacc4c62ee78962121799e797e5d586f8551c2bcc498ae84"),
        },
        "migration_club_role_event_ops_20260823.sql": {
            "club_event_series": (4, "f279bdcc72a395aed10ef315104d1e923d6f8040853f237b1777dce399a87b70"),
            "club_event_occurrence": (1, "3d7cf2bb8b671a8bcc94fb87672e62847eb5181554132509778f03b8a8f5ced6"),
            "club_event_waitlist": (2, "6d901bd5b6dc071b8dc60038cf94af499a2da3eb0c6061c0cabc2326dc335ba2"),
            "club_event_attendance_correction": (1, "3b29e6d8bad2a4da568e2200b9b8df61fe9346f7a9ea8ec013a8596de99fe979"),
            "club_event_notification_campaign": (3, "083eca97b48d5a21b1e7363ccb50328db8b76aadbadbbfdbcfeaa9d03ef6db39"),
            "club_event_notification_delivery": (3, "804d4baa93572ac7b0dbc88f6fc8a5741ff15bc56a380b0500df762384bec876"),
            "club_event_notification_outbox": (1, "b6fa7fd93485aceee6fc946defeb6d3a04c489119d8b289afa9d9c603c11b63b"),
        },
        "migration_merchant_operator_crm_followup_20260823.sql": {
            "merchant_customer_tag_assignment": (1, "27a03fe9613e48edc4291cc8929b9ad03bf11b3bf2065bde5592e3c9a591dbf8"),
            "merchant_customer_note": (1, "a5d5f8902b8398201138736a606637be75702bfd3b80347f6959ae0fe4f1f561"),
        },
        "migration_club_post_announcement_20260823.sql": {
            "club_post": (1, "0f9423cb74931199f999d6a9ec27e055f1b51eb954b083be97afbaabf81c837f"),
            "club_post_revision": (1, "64fc12be38727a8fa9f88c730a3700b7eae5b503ac4a96ae5742b974ead0f289"),
        },
    }

    @staticmethod
    def _normal_columns(raw):
        return ",".join(re.sub(r"[`\s]", "", raw).split(","))

    @staticmethod
    def _created_table_bodies(text):
        pattern = re.compile(
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z0-9_]+)`?\s*\("
            r"([\s\S]*?)\)\s*ENGINE\s*=",
            re.IGNORECASE,
        )
        return {table.lower(): body for table, body in pattern.findall(text)}

    @classmethod
    def _created_columns(cls, text):
        created = {}
        non_columns = {
            "PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "CHECK", "FOREIGN",
            "CASE", "WHEN", "THEN", "ELSE", "END",
        }
        for table, body in cls._created_table_bodies(text).items():
            columns = set()
            for line in body.splitlines():
                match = re.match(r"\s*`?([A-Za-z_][A-Za-z0-9_]*)`?\s+", line)
                if match and match.group(1).upper() not in non_columns:
                    columns.add(match.group(1).lower())
            created[table.lower()] = columns
        return created

    @staticmethod
    def _source_table_hash(body):
        without_comments = re.sub(
            r"\bCOMMENT\s+'(?:''|[^'])*'", "", body, flags=re.IGNORECASE)
        canonical = re.sub(r"[`\s]", "", without_comments).lower()
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    @staticmethod
    def _club_post_replay_problems(text):
        compact = re.sub(r"[`\s]", "", text).lower()
        required = (
            "@club_post_announcement_shape_exact",
            "prepareclub_post_announcement_alter_stmt",
            "createtableifnotexistsclub_post_revision",
            "cd1708419c1b0faeae7e33480385bdd5cccedc2374ddaf3220a4c50f66c15596",
            "0f9423cb74931199f999d6a9ec27e055f1b51eb954b083be97afbaabf81c837f",
        )
        problems = ["ClubPost 分段 DDL 缺可重放精确守卫: %s" % fragment
                    for fragment in required if fragment not in compact]
        owned_columns = (
            "column_namein('is_pinned','pinned_at','version','update_time')")
        pre_alter = compact.split("set@club_post_announcement_alter_sql", 1)[0]
        terminal = compact.split("createtableifnotexistsclub_post_revision", 1)[-1]
        if pre_alter.count(owned_columns) < 2 or terminal.count(owned_columns) < 2:
            problems.append("ClubPost 旧态/终态守卫没有同时固定四个新增列")
        pinned_index = "1:btree:yes:0:5:5:club_id,del_flag,is_pinned,pinned_at,id"
        if pinned_index not in pre_alter or pinned_index not in terminal:
            problems.append("ClubPost ALTER 前与终态没有同时固定置顶索引形状")
        return problems

    @classmethod
    def _problems(cls, name, text):
        problems = []
        marker = "-- runner: assert=scalar-true"
        if marker not in text:
            return ["缺机器可执行的 scalar-true 结构回读"]
        readback = text.split(marker, 1)[1]
        created = cls._created_columns(text)
        created_bodies = cls._created_table_bodies(text)
        for fragment in ("information_schema.tables", "information_schema.columns",
                         "information_schema.statistics"):
            if fragment.lower() not in readback.lower():
                problems.append("结构回读缺 %s" % fragment)
        if not re.search(r"(?:upper\s*\(\s*)?engine\s*\)?\s*=\s*['\"]innodb['\"]",
                         readback, re.IGNORECASE):
            problems.append("结构回读没有断言 InnoDB")
        if ("as assertion_ok" not in readback.lower()
                and not re.search(r"SELECT\s+COUNT\s*\(\s*\*\s*\)\s*=\s*\d+", readback,
                                  re.IGNORECASE)):
            problems.append("结构回读结果没有生成可由 runner 判真的标量")
        for table in cls.MIGRATIONS[name]:
            if table.lower() not in readback.lower():
                problems.append("结构回读没有点名表 %s" % table)

        expected_sources = cls.EXACT_SOURCE_TABLE_HASHES.get(name, {})
        for table, expected_hash in expected_sources.items():
            body = created_bodies.get(table)
            if body is None:
                problems.append("精确表体合同缺表 %s" % table)
            elif cls._source_table_hash(body) != expected_hash:
                problems.append("承重列/生成表达式/CHECK DDL 漂移 %s" % table)

        expected_columns = cls.EXACT_COLUMN_SIGNATURES.get(name, {})
        if expected_columns:
            for fragment in ("group_concat_max_len", "generation_expression", "column_hash"):
                if fragment not in readback.lower():
                    problems.append("精确列签名回读缺 %s" % fragment)
            for table, (column_count, expected_hash) in expected_columns.items():
                if expected_hash not in readback.lower():
                    problems.append("精确列签名回读漂移 %s(%s列)" %
                                    (table, column_count))

        expected_checks = cls.EXACT_CHECK_SIGNATURES.get(name, {})
        if expected_checks:
            for fragment in ("check_clause", "enforced", "check_hash"):
                if fragment not in readback.lower():
                    problems.append("精确 CHECK 签名回读缺 %s" % fragment)
            for table, (check_count, expected_hash) in expected_checks.items():
                if expected_hash not in readback.lower():
                    problems.append("精确 CHECK 签名回读漂移 %s(%s条)" %
                                    (table, check_count))

        if name == "migration_club_post_announcement_20260823.sql":
            problems.extend(cls._club_post_replay_problems(text))

        for table, raw_columns in re.findall(
                r"table_name\s*=\s*['\"]([A-Za-z0-9_]+)['\"]\s+AND\s+column_name\s+IN\s*\(([^)]*)\)",
                readback, re.IGNORECASE):
            columns = re.findall(r"['\"]([A-Za-z0-9_]+)['\"]", raw_columns)
            actual = created.get(table.lower())
            if actual is None:
                continue
            for column in columns:
                if column.lower() not in actual:
                    problems.append("结构回读断言了 CREATE 不存在的列 %s.%s" %
                                    (table, column))
        columns_start = readback.lower().find("information_schema.columns")
        columns_end = readback.lower().find("information_schema.statistics", columns_start)
        if columns_start >= 0 and columns_end > columns_start:
            columns_window = readback[columns_start:columns_end]
            window_asserted_columns = sum(
                len(re.findall(r"['\"]([A-Za-z0-9_]+)['\"]", raw_columns))
                for _, raw_columns in re.findall(
                    r"table_name\s*=\s*['\"]([A-Za-z0-9_]+)['\"]\s+AND\s+column_name\s+IN\s*\(([^)]*)\)",
                    columns_window, re.IGNORECASE))
            expected = re.search(r"\)\s*=\s*(\d+)", columns_window)
            if expected and window_asserted_columns and int(expected.group(1)) != window_asserted_columns:
                problems.append("结构回读列计数 %s 与显式列集合 %s 不一致" %
                                (expected.group(1), window_asserted_columns))

        uniques = re.findall(
            r"UNIQUE\s+KEY\s+`?([A-Za-z0-9_]+)`?\s*\(([^)]+)\)",
            text, re.IGNORECASE)
        if not uniques:
            problems.append("迁移没有业务唯一键，无法证明幂等边界")
        for index_name, raw_columns in uniques:
            columns = cls._normal_columns(raw_columns)
            exact_index = re.compile(
                r"index_name\s*=\s*['\"]%s['\"][\s\S]{0,240}?"
                r"cols\s*=\s*['\"]%s['\"]" %
                (re.escape(index_name), re.escape(columns)), re.IGNORECASE)
            ordered_columns = [column for column in columns.split(",") if column]
            exact_sequence = False
            for occurrence in re.finditer(
                    r"index_name\s*=\s*['\"]%s['\"]" % re.escape(index_name),
                    readback, re.IGNORECASE):
                window = readback[occurrence.start():occurrence.start() + 1400]
                if all(re.search(
                        r"seq_in_index\s*=\s*%d\s+AND\s+column_name\s*=\s*['\"]%s['\"]" %
                        (position, re.escape(column)), window, re.IGNORECASE)
                       for position, column in enumerate(ordered_columns, 1)):
                    exact_sequence = True
                    break
            if not exact_index.search(readback) and not exact_sequence:
                problems.append("结构回读没有精确断言唯一键 %s(%s)" %
                                (index_name, columns))
        return problems

    def test_新增迁移逐表逐唯一键结构回读(self):
        for name in self.MIGRATIONS:
            with self.subTest(name=name):
                path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
                with open(path, encoding="utf-8") as handle:
                    text = handle.read()
                self.assertEqual(check_sql_text(name, text), [], "%s 基础迁移门禁" % name)
                self.assertEqual(self._problems(name, text), [], "%s 结构回读" % name)

    def test_负控_摘掉任一唯一键精确列回读必须红(self):
        name = "migration_merchant_review_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        poisoned = text.replace(
            "cols = 'registration_id,merchant_owner_member_id'",
            "cols = 'registration_id'")
        self.assertNotEqual(poisoned, text, "负控必须真实改到承重断言")
        problems = self._problems(name, poisoned)
        self.assertTrue(problems, "唯一键列回读被削弱后必须红")
        self.assertIn("uk_merchant_review_registration_merchant", "\n".join(problems))

    def test_负控_回读列不存在于CREATE必须红(self):
        name = "migration_club_role_event_ops_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        head, readback = text.split("-- runner: assert=scalar-true", 1)
        poisoned_readback = readback.replace("'occurrence_at'", "'occurrence_date'", 1)
        self.assertNotEqual(poisoned_readback, readback, "负控必须真实改到结构回读")
        problems = self._problems(
            name, head + "-- runner: assert=scalar-true" + poisoned_readback)
        self.assertIn("club_event_occurrence.occurrence_date", "\n".join(problems))

    def test_负控_承重nullable毒化必须红(self):
        name = "migration_business_audit_event_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        poisoned = text.replace("owner_member_id BIGINT NOT NULL",
                                "owner_member_id BIGINT DEFAULT NULL", 1)
        self.assertNotEqual(poisoned, text, "负控必须真实毒化 nullable")
        self.assertIn("business_audit_event", "\n".join(self._problems(name, poisoned)))

    def test_负控_生成表达式毒化必须红(self):
        name = "migration_merchant_operator_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        poisoned = text.replace("CASE WHEN status = 'ACTIVE' THEN operator_member_id",
                                "CASE WHEN status = 'NEVER' THEN operator_member_id", 1)
        self.assertNotEqual(poisoned, text, "负控必须真实毒化 generated expression")
        self.assertIn("merchant_operator", "\n".join(self._problems(name, poisoned)))

    def test_负控_CHECK正文毒化必须红(self):
        name = "migration_club_role_event_ops_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        poisoned = text.replace(
            "'WAITING','OFFERED','CLAIMED','CONVERTED','CANCELLED','EXPIRED'",
            "'WAITING','CLAIMED','CONVERTED','CANCELLED','EXPIRED'", 1)
        self.assertNotEqual(poisoned, text, "负控必须真实毒化 CHECK")
        self.assertIn("club_event_waitlist", "\n".join(self._problems(name, poisoned)))

    def test_负控_CHECK括号分组毒化必须红(self):
        name = "migration_merchant_operator_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        poisoned = text.replace(
            "(role_code IN ('MERCHANT_MANAGER','MERCHANT_CHECKIN','MERCHANT_MARKETING','MERCHANT_FINANCE'))",
            "((role_code IN ('MERCHANT_MANAGER','MERCHANT_CHECKIN','MERCHANT_MARKETING','MERCHANT_FINANCE')))", 1)
        self.assertNotEqual(poisoned, text, "负控必须真实毒化 CHECK 括号分组")
        self.assertIn("merchant_operator", "\n".join(self._problems(name, poisoned)))

    def test_负控_精确回读hash毒化必须红(self):
        name = "migration_merchant_review_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        expected = self.EXACT_CHECK_SIGNATURES[name]["merchant_review"][1]
        poisoned = text.replace(expected, "0" * 64, 1)
        self.assertNotEqual(poisoned, text, "负控必须真实毒化精确回读 hash")
        self.assertIn("merchant_review", "\n".join(self._problems(name, poisoned)))

    def test_负控_ClubPost首段成功后重跑守卫不得摘除(self):
        name = "migration_club_post_announcement_20260823.sql"
        path = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql", name)
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        poisoned = text.replace("CREATE TABLE IF NOT EXISTS club_post_revision",
                                "CREATE TABLE club_post_revision", 1)
        self.assertNotEqual(poisoned, text, "负控必须真实摘掉 revision 重放")
        self.assertTrue(self._club_post_replay_problems(poisoned),
                        "摘掉首段成功后重跑守卫必须红")


class 商家俱乐部Quartz迁移_部署阶段合同(unittest.TestCase):
    """依赖新 bean 的 Quartz 注册必须与 pre-deploy schema 物理拆分。"""

    PAIRS = {
        "migration_merchant_operator_crm_phase1a_delivery_20260823.sql": (
            "migration_merchant_operator_crm_phase1a_delivery_job_20260825.sql",
            "merchantCrmDeliveryRecoveryTask.run()",
        ),
        "migration_club_role_event_ops_20260823.sql": (
            "migration_club_role_event_ops_job_20260825.sql",
            "clubEventOpsTask.run()",
        ),
        "migration_club_role_media_review_outbox_20260824.sql": (
            "migration_club_role_media_review_outbox_job_20260825.sql",
            "clubPostMediaReviewTask.run()",
        ),
    }

    @staticmethod
    def _migration_id(filename):
        return filename[len("migration_"):-len(".sql")]

    @classmethod
    def _problems(cls, schema_name, schema_text, job_name, job_text, dependencies):
        problems = []
        if "sys_job" in schema_text.lower():
            problems.append("pre-deploy schema 混入 sys_job")
        if "post-deploy" not in job_text.lower():
            problems.append("job migration 未声明 post-deploy")
        if re.search(r"\bCREATE\s+TABLE\b", job_text, re.IGNORECASE):
            problems.append("post-deploy job migration 混入 schema DDL")
        target = cls.PAIRS[schema_name][1]
        if target.lower() not in job_text.lower():
            problems.append("job migration 未注册 %s" % target)
        for fragment in (
                "INSERT INTO sys_job", "WHERE NOT EXISTS", "-- runner: assert=scalar-true",
                "SELECT COUNT(*) = 1", "job_name =", "job_group = 'DEFAULT'",
                "cron_expression = '0 * * * * ?'", "misfire_policy = '3'",
                "concurrent = '1'", "status = '0'"):
            if fragment.lower() not in job_text.lower():
                problems.append("job migration 缺精确配置或读回: %s" % fragment)
        job_id = cls._migration_id(job_name)
        schema_id = cls._migration_id(schema_name)
        if schema_id not in dependencies.get(job_id, []):
            problems.append("依赖图未声明 %s -> %s" % (job_id, schema_id))
        return problems

    def _load_dependencies(self):
        path = os.path.join(REPO_ROOT, "ci", "migration-dependencies.json")
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)["dependencies"]

    def test_schema与post_deploy_job物理拆分且依赖有向(self):
        dependencies = self._load_dependencies()
        sql_dir = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql")
        for schema_name, (job_name, _target) in self.PAIRS.items():
            with self.subTest(schema=schema_name, job=job_name):
                with open(os.path.join(sql_dir, schema_name), encoding="utf-8") as handle:
                    schema_text = handle.read()
                with open(os.path.join(sql_dir, job_name), encoding="utf-8") as handle:
                    job_text = handle.read()
                self.assertEqual(
                    self._problems(schema_name, schema_text, job_name, job_text, dependencies),
                    [],
                )

    def test_负控_schema重新混入sys_job必须红(self):
        schema_name = "migration_club_role_event_ops_20260823.sql"
        job_name, _target = self.PAIRS[schema_name]
        dependencies = self._load_dependencies()
        sql_dir = os.path.join(REPO_ROOT, "chengyinhub-admin", "sql")
        with open(os.path.join(sql_dir, schema_name), encoding="utf-8") as handle:
            schema_text = handle.read()
        with open(os.path.join(sql_dir, job_name), encoding="utf-8") as handle:
            job_text = handle.read()
        poisoned = schema_text + "\nINSERT INTO sys_job (invoke_target) VALUES ('clubEventOpsTask.run()');\n"
        problems = self._problems(
            schema_name, poisoned, job_name, job_text, dependencies)
        self.assertIn("pre-deploy schema 混入 sys_job", problems)

class 商家俱乐部Quartz提前启用_暂停Repair合同(unittest.TestCase):
    """持久态止血以任务身份 exact-three，不能把环境内 job_id 写进 canonical SQL。"""

    FILENAME = "migration_merchant_z_quartz_jobs_pause_repair_20260826.sql"
    MIGRATION_ID = FILENAME[len("migration_"):-len(".sql")]
    JOB_MIGRATIONS = {
        "merchant_operator_crm_phase1a_delivery_job_20260825",
        "club_role_event_ops_job_20260825",
        "club_role_media_review_outbox_job_20260825",
    }
    TARGETS = {
        "merchantCrmDeliveryRecoveryTask.run()": "商家CRM触达恢复",
        "clubEventOpsTask.run()": "俱乐部活动运营维护",
        "clubPostMediaReviewTask.run()": "俱乐部动态图片送审恢复",
    }

    @classmethod
    def _problems(cls, text, dependencies):
        problems = []
        statements = [
            " ".join(statement.lower().split())
            for statement in split_statements(text)
        ]
        normalized = "\n".join(statements)
        for fragment in (
                "-- runner: requires=transaction",
                "CREATE TEMPORARY TABLE merchant_club_quartz_pause_guard",
                "-- runner: assert=rowcount-positive",
                "INSERT INTO merchant_club_quartz_pause_guard",
                "UPDATE sys_job SET status = '1'",
                "DROP TEMPORARY TABLE merchant_club_quartz_pause_guard"):
            haystack = text.lower() if fragment.startswith("-- runner:") else normalized
            if " ".join(fragment.lower().split()) not in " ".join(haystack.split()):
                problems.append("pause repair 缺 fail-closed 片段: %s" % fragment)
        update_statements = [
            statement for statement in statements
            if statement.startswith("update sys_job set ")
        ]
        if len(update_statements) != 1:
            problems.append("pause repair UPDATE sys_job 必须恰好一句")
        elif "where cast(invoke_target as binary) in" not in update_statements[0]:
            problems.append("pause repair UPDATE 必须按 binary invoke_target 集合")
        if "job_id" in normalized:
            problems.append("pause repair executable SQL 不得绑定环境内 job_id")
        if normalized.count("or cast(job_name as binary) in") < 2:
            problems.append("pause repair pre/post 必须把额外同名 target 纳入并集守卫")
        for target, name in cls.TARGETS.items():
            if target.lower() not in normalized:
                problems.append("pause repair 缺目标: %s" % target)
            if name.lower() not in normalized:
                problems.append("pause repair 缺目标名称映射: %s -> %s" % (target, name))
        if normalized.count("count(distinct cast(invoke_target as binary))") < 2:
            problems.append("pause repair pre/post 均必须证明 3 个不同 invoke_target")
        for exact_config in (
                "cast(job_group as binary) = cast('default' as binary)",
                "cast(cron_expression as binary) = cast('0 * * * * ?' as binary)",
                "cast(misfire_policy as binary) = cast('3' as binary)",
                "cast(concurrent as binary) = cast('1' as binary)",
                "cast(status as binary) in (cast('0' as binary), cast('1' as binary))",
                "not (cast(status as binary) <=> cast('1' as binary))",
                "cast(status as binary) = cast('1' as binary)",
                "cast(update_by as binary) = cast('admin' as binary)",
                "cast(remark as binary) <=> cast(concat("):
            if exact_config not in normalized:
                problems.append("pause repair 缺 binary 精确配置守卫: %s" % exact_config)
        for identity_column in (
                "invoke_target", "job_name", "job_group", "cron_expression",
                "misfire_policy", "concurrent"):
            if re.search(
                    r"\b%s\s*(?:=|<>|<=>|\bin\s*\()" % identity_column,
                    normalized):
                problems.append(
                    "pause repair 仍用 collation 比较身份/配置: %s" % identity_column)
        if re.search(r"\bcase\s+invoke_target\b", normalized):
            problems.append("pause repair CASE 仍用 collation 比较 invoke_target")
        if not all(mapping in text for mapping in (
                "clubEventOpsTask=125",
                "clubPostMediaReviewTask=126",
                "merchantCrmDeliveryRecoveryTask=127")):
            problems.append("pause repair 缺生产当前 ID 的独立只读执行计划")
        for handoff in ("新连接", "调度器", "不能单独作为", "跨越 cron"):
            if handoff.lower() not in text.lower():
                problems.append("pause repair 缺回读/运行时边界: %s" % handoff)
        declared = set(dependencies.get(cls.MIGRATION_ID, []))
        missing = cls.JOB_MIGRATIONS - declared
        if missing:
            problems.append("pause repair 依赖图缺少: %s" % sorted(missing))
        return problems

    def _load(self):
        sql_path = os.path.join(
            REPO_ROOT, "chengyinhub-admin", "sql", self.FILENAME)
        with open(sql_path, encoding="utf-8") as handle:
            text = handle.read()
        with open(os.path.join(REPO_ROOT, "ci", "migration-dependencies.json"),
                  encoding="utf-8") as handle:
            dependencies = json.load(handle)["dependencies"]
        return text, dependencies

    def test_pause_repair_exact_three_fail_closed且可重放(self):
        text, dependencies = self._load()
        self.assertEqual(self._problems(text, dependencies), [])

    def test_负控_把生产job_id写进canonical_update必须红(self):
        text, dependencies = self._load()
        poisoned = text.replace(
            "\nWHERE CAST(invoke_target AS BINARY) IN (",
            "\nWHERE job_id IN (125, 126, 127)\n"
            "  AND CAST(invoke_target AS BINARY) IN (",
            1,
        )
        self.assertNotEqual(poisoned, text, "负控必须真实注入固定 job_id")
        problems = self._problems(poisoned, dependencies)
        self.assertTrue(
            any("不得绑定环境内 job_id" in problem
                for problem in problems),
            "canonical UPDATE 一旦绑定生产 job_id 必须红",
        )

    def test_负控_去掉同名target并集守卫必须红(self):
        text, dependencies = self._load()
        poisoned = text.replace(
            "OR CAST(job_name AS BINARY) IN",
            "OR CAST(invoke_target AS BINARY) IN",
        )
        self.assertNotEqual(poisoned, text, "负控必须真实移除同名 target 守卫")
        problems = self._problems(poisoned, dependencies)
        self.assertTrue(
            any("额外同名 target" in problem
                for problem in problems),
            "额外同名 target 不再纳入 pre/post 守卫时必须红",
        )

    def test_负控_去掉binary身份比较必须红(self):
        text, dependencies = self._load()
        poisoned = text.replace(
            "CAST(invoke_target AS BINARY)", "invoke_target"
        ).replace(
            "CAST(job_name AS BINARY)", "job_name"
        )
        self.assertNotEqual(poisoned, text, "负控必须真实移除 binary 身份比较")
        problems = self._problems(poisoned, dependencies)
        self.assertTrue(
            any("collation 比较" in problem or "binary invoke_target" in problem
                for problem in problems),
            "身份比较退回 utf8mb4_general_ci 时必须红",
        )


class SingleRejectTriggerContract(unittest.TestCase):
    """A literal, single SIGNAL has no internal statement separator; compound routines still fail closed."""

    @staticmethod
    def trigger(body="SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'append only'", timing="BEFORE", event="UPDATE"):
        return "CREATE TRIGGER reject_change %s %s ON facts FOR EACH ROW %s;" % (timing, event, body)

    def test_single_literal_rejection_triggers_need_no_client_delimiter(self):
        for event in ("UPDATE", "DELETE"):
            with self.subTest(event=event):
                sql = self.trigger(event=event)
                self.assertEqual(check_sql_text("unrelated_new_candidate.sql", sql), [])
                self.assertEqual(len(split_statements(sql)), 1)

    def test_canonical_cash_candidate_keeps_both_triggers_and_original_bytes(self):
        path = os.path.join(REPO_ROOT, "chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql")
        with open(path, "rb") as source:
            raw = source.read()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),
                         "cf981d89214b219c41639537f301f2b55b2b568f2d310c06621e6df473b3f4ba")
        sql = raw.decode("utf-8")
        statements = split_statements(sql)
        self.assertEqual(len(statements), 3)
        self.assertEqual(check_sql_text(path, sql), [])
        self.assertIn("cash_fund_fact_no_update BEFORE UPDATE", statements[1])
        self.assertIn("cash_fund_fact_no_delete BEFORE DELETE", statements[2])
        for trigger in statements[1:]:
            self.assertIn("SIGNAL SQLSTATE '45000'", trigger)
            self.assertIn("'cash withdrawal facts are append only'", trigger)

    def test_compound_trigger_still_requires_custom_delimiter(self):
        sql = self.trigger("BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='deny'; END")
        self.assertTrue(any("DELIMITER" in p for p in check_sql_text("compound.sql", sql)))

    def test_other_bodies_timing_events_and_dynamic_expressions_still_require_delimiter(self):
        cases = [self.trigger("SET NEW.amount = 0"), self.trigger("RESIGNAL"),
                 self.trigger("SIGNAL SQLSTATE '01000' SET MESSAGE_TEXT='warning'"),
                 self.trigger("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=CONCAT('a','b')"),
                 self.trigger(timing="AFTER"), self.trigger(event="INSERT"),
                 self.trigger().replace("CREATE TRIGGER", "CREATE DEFINER=CURRENT_USER TRIGGER")]
        for sql in cases:
            with self.subTest(sql=sql):
                self.assertTrue(any("DELIMITER" in p for p in check_sql_text("other.sql", sql)))

    def test_incomplete_or_extended_trigger_statement_cannot_match_the_rejection_grammar(self):
        for sql in (self.trigger()[:-1], self.trigger().replace("FOR EACH ROW", "FOR EACH"),
                    self.trigger().replace(";", " SET @extra=1;"),
                    self.trigger().replace("MESSAGE_TEXT", "MYSQL_ERRNO")):
            with self.subTest(sql=sql):
                self.assertTrue(check_sql_text("bad.sql", sql))

    def test_literal_semicolons_stay_data_and_following_unbounded_write_stays_rejected(self):
        sql = self.trigger("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='BEGIN; UPDATE t SET a=0; END'")
        self.assertEqual(check_sql_text("literal.sql", sql), [])
        self.assertEqual(len(split_statements(sql)), 1)
        self.assertTrue(any("WHERE" in p for p in check_sql_text("bad_write.sql", sql + "\nUPDATE t SET a=0;")))


if __name__ == "__main__":
    unittest.main(verbosity=2)

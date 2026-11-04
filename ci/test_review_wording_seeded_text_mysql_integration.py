"""第十一轮 h2 第 2 项:平台种子文字改名迁移的真库演练(2026-11-04 业主拍板)。

一次性 scratch 库:base schema 基线 → 原样执行两份玩法包种子与 predict 任务注册 → 按原迁移的
perms / 原文插入 14 条后台菜单 → 造「作者改过」与「商家复制件」的对照行 → 本迁移执行两遍。
钉住:
  ① 平台种子行逐字改名,runner 断言为真;
  ② 第二遍零变更(表快照相等),断言照样为真;
  ③ 作者改过的文字、商家复制件、调用目标、主键一个都不动。
"""
import os
import pathlib
import unittest
import uuid

import pymysql

from ci.run_migration import assert_mysql_server_provenance
from scripts.e2e.build_schema import BASELINE, run_file


REPO = pathlib.Path(__file__).resolve().parent.parent
SQL = REPO / "chengyinhub-admin/sql"
MIGRATION = SQL / "migration_20261104_review_wording_seeded_text.sql"
SEED_FILES = (
    SQL / "migration_seed_playpack_templates_20260820.sql",
    SQL / "migration_seed_playpack_batch2_figma_20260821.sql",
    SQL / "migration_predict_void_task_register_20260915.sql",
)
SCHEMA_PREFIX = "cy_review_wording_it_"

# (perms, menu_name, remark):与原迁移逐字相同;名称或备注之一带「探店日」。
MENUS = (
    ("business:clubhours:confirm", "探店日实际工时确认", "A2：确认俱乐部实际工时；宽泛的 business:topic:edit 不得替代"),
    ("business:clubextra:approve", "探店日额外工时审批", "A2：事前预审批与更正；追认不得抬高原预算"),
    ("business:clubquality:confirm", "探店日质量奖励确认", "A1：三维逐项一次确认，每维 0 或 200"),
    ("business:clubquality:exclude", "探店日质量奖励过错排除",
     "A1：已证明由商家/平台造成的过错排除该维计酬；能推翻已确认的 200，故与确认权分开"),
    ("business:editionfinal:execute", "探店日期级FINAL结算", "F3：FINAL 只消费已确认事实，重放返回同一结果"),
    ("business:clubhours:correct", "探店日已确认工时更正",
     "A2：把已确认工时退回重确认，旧事实留痕在 club_edition_hours_correction；与确认权分开"),
    ("business:verificationReview:list", "核销异常复核",
     "探店日批5:核销率/频次异常人工复核队列。只做人审,不改核销事实、不退款、不动资金"),
    ("business:topicnode:add", "主题节点新增",
     "探店日批5:平台从零建店位节点。绑商家的节点由服务端强制按已过审留痕,否则玩家侧静默消失"),
    ("business:topicnode:edit", "主题节点编辑",
     "探店日批5:改店位节点内容。打卡码在页面上只读 —— 换码会让店里已印好的海报全部作废"),
    ("business:topicnode:remove", "主题节点删除",
     "探店日批5:删店位节点。已有玩家进店记录的节点删掉会让那些记录指向空,删前先看漏斗"),
    ("business:roamRevisitReport:list", "漫游复访报表",
     "探店日5-5只读报表权限；仅允许查询SHOP_REVISIT/ROAM_REVISIT 7/30/90日观察窗"),
    ("business:withdrawal:cooldown-config", "玩家投诉补偿冷静期改写",
     "探店日A3：改冷静期小时数的专用入口（PlayerWithdrawalCooldownController#rewrite）；"
     "前值/后值/理由落 player_withdrawal_cooldown_config_audit，只影响新事件"),
    ("business:clubpayee:verify", "俱乐部对公收款人审核",
     "探店日A4：财务一次审核；不设 maker/checker，理由见 ClubCorporatePayeeServiceImpl.verify；"
     "俱乐部侧不得自审由 app 侧零 payee 入口保证"),
    ("business:clubpayee:payout", "俱乐部对公收款人付款解密",
     "探店日A4：受控完整解密，仅付款登记环节使用；与审核权限互斥分派（见本文件末的角色分派与回读）"),
)
# 后台有人改过的一条:名称带探店日但不是种子原文 → 不动。
AUTHOR_MENU = ("business:fixture:author", "探店日人工改过的菜单", "探店日管理员自己写的备注")

SNAPSHOTS = (
    "SELECT id, member_id, name, description, create_by FROM cms_topic ORDER BY id",
    "SELECT id, topic_id, name, description, create_by FROM cms_topic_node ORDER BY id",
    "SELECT id, topic_id, name, description, create_by FROM cms_topic_chapter ORDER BY id",
    "SELECT id, member_id, title, question_name, photo_require_desc, answer_reveal, create_by "
    "FROM cms_member_template ORDER BY id",
    "SELECT menu_id, menu_name, perms, remark FROM sys_menu ORDER BY menu_id",
    "SELECT job_id, job_name, job_group, invoke_target, cron_expression, status, remark FROM sys_job ORDER BY job_id",
)


class ReviewWordingSeededTextMysqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("CHENGYIN_MYSQL_IT") != "1":
            raise RuntimeError("MySQL IT fail closed: CHENGYIN_MYSQL_IT=1 is required")
        cls.host = os.environ["CHENGYIN_MYSQL_IT_HOST"]
        cls.port = int(os.environ["CHENGYIN_MYSQL_IT_PORT"])
        cls.user = os.environ.get("CHENGYIN_MYSQL_IT_USER", "root")
        cls.password = os.environ.get("CHENGYIN_MYSQL_IT_PASSWORD", "")
        if cls.host not in {"127.0.0.1", "localhost", "::1"}:
            raise RuntimeError("MySQL IT only accepts an explicit localhost scratch server")
        cls.schema = SCHEMA_PREFIX + uuid.uuid4().hex[:12]
        cls.admin = pymysql.connect(host=cls.host, port=cls.port, user=cls.user,
                                    password=cls.password, autocommit=True)
        with cls.admin.cursor() as cursor:
            cursor.execute("SELECT VERSION(), @@version_comment")
            version, comment = cursor.fetchone()
            assert_mysql_server_provenance(version, comment)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "admin"):
            if not cls.schema.startswith(SCHEMA_PREFIX):
                raise RuntimeError("refusing to drop an unvalidated scratch schema")
            with cls.admin.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS `{cls.schema}`")
                cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s",
                               (cls.schema,))
                if cursor.fetchone()[0] != 0:
                    raise RuntimeError("scratch schema cleanup readback failed")
            cls.admin.close()

    def setUp(self):
        with self.admin.cursor() as cursor:
            cursor.execute(f"DROP DATABASE IF EXISTS `{self.schema}`")
            cursor.execute(f"CREATE DATABASE `{self.schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        for path in (BASELINE,) + SEED_FILES:
            ok, results = self.run_sql_file(path)
            self.assertTrue(ok, (path.name, results))
            self.assertEqual([], results, path.name)
        self.seed_fixtures()

    def connect(self):
        return pymysql.connect(host=self.host, port=self.port, user=self.user, password=self.password,
                               database=self.schema, autocommit=True, charset="utf8mb4")

    def run_sql_file(self, path):
        connection = self.connect()
        try:
            results = []
            ok = run_file(connection.cursor(), path, path.name, results, False)
            return ok, results
        finally:
            connection.close()

    def query(self, sql, args=()):
        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(sql, args)
                return cursor.fetchall()
        finally:
            connection.close()

    def execute(self, sql, args=()):
        connection = self.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(sql, args)
                return cursor.lastrowid
        finally:
            connection.close()

    def snapshot(self):
        return [self.query(sql) for sql in SNAPSHOTS]

    def seed_fixtures(self):
        for index, (perms, name, remark) in enumerate(MENUS + (AUTHOR_MENU,)):
            self.execute("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,"
                         "menu_type,visible,status,perms,icon,create_by,create_time,remark) "
                         "VALUES(%s,%s,0,%s,'','',1,0,'F','0','0',%s,'#','admin',NOW(),%s)",
                         (88000 + index, name, index, perms, remark))
        # 商家复制件:复制服务写 create_by='system_copy',主题归复制者。
        topic = self.execute("INSERT INTO cms_topic(member_id,topic_type,product_type,name,description,is_template,"
                             "status,user_status,del_flag,create_by,create_time) "
                             "VALUES(7001,1,2,'三个盲盒','三个「盲盒袋子」,复制件',0,1,0,0,'system_copy',NOW())")
        self.execute("INSERT INTO cms_topic_node(topic_id,name,description,status,sort_id,create_by,create_time) "
                     "VALUES(%s,'盲盒一:观察','复制件节点',1,1,'system_copy',NOW())", (topic,))
        self.execute("INSERT INTO cms_member_template(member_id,title,description,validation_method,draft_status,"
                     "status,publish_status,check_status,is_sync,del_flag,create_by,create_time) "
                     "VALUES(7001,'玩法包·盲盒之观察','复制件玩法',3,1,1,0,1,0,0,'system_copy',NOW())")
        # 平台自己的复制件(TopicContentCopyServiceImpl 也会把 member_id 写成平台):create_by 不是种子 → 不动。
        self.execute("INSERT INTO cms_topic(member_id,topic_type,product_type,name,is_template,status,user_status,"
                     "del_flag,create_by,create_time) VALUES(100000,1,2,'三个盲盒',1,1,0,0,'system_copy',NOW())")
        # 平台种子里有人改过的文字:与原文不再逐字相同 → 不动。
        self.execute("UPDATE cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id SET n.name='盲盒三:开口(本店版)' "
                     "WHERE t.create_by='seed_playpack_20260820' AND n.name='盲盒三:开口'")
        self.execute("UPDATE cms_member_template SET photo_require_desc='盲盒二:拍下你最喜欢的角落' "
                     "WHERE member_id=100000 AND create_by='seed_playpack_20260820' AND title='玩法包·盲盒之拍照'")

    def test_renames_platform_seed_rows_only_and_replays_without_change(self):
        before_ids = self.query("SELECT job_id, job_group, invoke_target, cron_expression, status FROM sys_job "
                                "WHERE invoke_target='predictVoidExpiredTask.run()'")
        self.assertEqual(1, len(before_ids))

        self.assertEqual((True, []), self.run_sql_file(MIGRATION))
        first = self.snapshot()
        self.assertEqual((True, []), self.run_sql_file(MIGRATION))
        self.assertEqual(first, self.snapshot(), "第二遍必须零变更")

        seed = "create_by='seed_playpack_20260820'"
        self.assertEqual(((1,),), self.query(
            f"SELECT COUNT(*) FROM cms_topic WHERE member_id=100000 AND is_template=1 AND {seed} "
            "AND name='三个随机卡' AND description LIKE '三个「随机卡袋子」,%%'"))
        self.assertEqual({("随机卡一:观察",), ("随机卡二:拍照",), ("盲盒三:开口(本店版)",)}, set(self.query(
            "SELECT n.name FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id "
            f"WHERE t.member_id=100000 AND t.{seed} AND t.name='三个随机卡'")))
        templates = {row[0]: row[1:] for row in self.query(
            "SELECT title, question_name, photo_require_desc, answer_reveal FROM cms_member_template "
            f"WHERE member_id=100000 AND {seed} AND title LIKE '玩法包·%%之%%' AND title NOT LIKE '%%盲盒%%'")}
        self.assertTrue(templates["玩法包·随机卡之观察"][0].startswith("随机卡一拆开是一张纸条"))
        self.assertEqual("盲盒二:拍下你最喜欢的角落", templates["玩法包·随机卡之拍照"][1], "作者改过的不动")
        self.assertTrue(templates["玩法包·随机卡之开口"][0].startswith("随机卡三是一个问题"))
        self.assertTrue(templates["玩法包·随机卡之开口"][2].startswith("三个随机卡全部拆完!"))
        self.assertEqual(((1,),), self.query(
            "SELECT COUNT(*) FROM cms_topic_chapter c JOIN cms_topic t ON t.id=c.topic_id "
            "WHERE t.create_by='seed_playpack2_20260821' AND t.name='穿搭盲配' AND c.name='随机卡衣柜'"))

        # 复制件原样。
        self.assertEqual(((2,),), self.query(
            "SELECT COUNT(*) FROM cms_topic WHERE create_by='system_copy' AND name='三个盲盒'"))
        self.assertEqual(((1,),), self.query(
            "SELECT COUNT(*) FROM cms_topic_node WHERE create_by='system_copy' AND name='盲盒一:观察'"))
        self.assertEqual(((1,),), self.query(
            "SELECT COUNT(*) FROM cms_member_template WHERE create_by='system_copy' AND title='玩法包·盲盒之观察'"))

        menus = {row[0]: row[1:] for row in self.query("SELECT perms, menu_name, remark FROM sys_menu")}
        for perms, name, remark in MENUS:
            self.assertEqual((name.replace("探店日", "自由探索"), remark.replace("探店日", "自由探索")),
                             menus[perms], perms)
        self.assertEqual(AUTHOR_MENU[1:], menus[AUTHOR_MENU[0]], "后台人工写的菜单不动")

        job = self.query("SELECT job_name, remark, job_id, job_group, invoke_target, cron_expression, status "
                         "FROM sys_job WHERE invoke_target='predictVoidExpiredTask.run()'")
        self.assertEqual(1, len(job))
        self.assertEqual("预测轮次超时作废", job[0][0])
        self.assertEqual("商家超过48小时不给预测答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑", job[0][1])
        self.assertEqual(before_ids[0], job[0][2:], "调用目标、主键、计划与状态不变")

    def test_assertion_fails_closed_when_a_seed_row_still_has_old_text(self):
        """负控:把一行改回旧值后只跑断言段 —— 断言必须为假。"""
        self.assertEqual((True, []), self.run_sql_file(MIGRATION))
        self.execute("UPDATE sys_job SET job_name='竞猜轮次超时作废' WHERE invoke_target='predictVoidExpiredTask.run()'")
        text = MIGRATION.read_text(encoding="utf-8")
        assertion = text[text.rindex("-- runner: assert=scalar-true"):]
        self.assertEqual(((0,),), self.query(assertion.split("\n", 1)[1].rstrip().rstrip(";")))


if __name__ == "__main__":
    unittest.main()

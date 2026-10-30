#!/usr/bin/env python3
"""为 5-1 四条链路准备最小夹具:后台管理员、四个角色的 member、商家主体、俱乐部。

base schema 是 --no-data 的结构快照,migration 只种了菜单/角色,业务侧一行没有。
本脚本只写【夹具】,不替产品码做任何业务动作 —— 建期/审核/开售/购票/核销全部走真实接口或真实 service。
"""
import argparse, pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))
import pymysql

# RuoYi 默认 admin123 的 bcrypt(仅用于 e2e_* 等非 admin/ry 的夹具账号)
ADMIN_HASH = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2"
# 超管夹具口令见 chain_lib.E2E_SUPERADMIN_PASSWORD 的 bcrypt。后端登录前拒绝 admin/ry 使用
# RuoYi 公开默认口令(F-L5S-09),所以一次性 E2E 库的超管不能再用 admin123。
SUPERADMIN_HASH = "$2a$10$aKtf9i3QGSe4YusiaEQm3ugGsvJcyn1owBjDUAH/uzChT8CQauH1i"
RECRUIT_JOB_TARGET = "topicRecruitDeadlineTask.run()"
DISCOVERABLE_MERCHANTS = (
    (8001, 9002, "探店日样板咖啡", 24, "10:00-20:00", "探店日,City Walk", 0,
     "上海市黄浦区咖啡", "街角咖啡补给站", "适合小团到店体验与城市漫步中途补给。"),
    (8002, 9005, "探店日样板书店", 20, "10:00-21:00", "探店日,读书会", 0,
     "上海市黄浦区书店", "城市阅读客厅", "适合读书会与小型文化活动。"),
    (8003, 9006, "探店日样板花店", 16, "09:30-19:00", "探店日,手作体验", 0,
     "上海市黄浦区花店", "街区花艺体验站", "适合花艺手作与主题到店体验。"),
    (8004, 9007, "探店日样板面包", 18, "08:00-18:00", "探店日,美食体验", 0,
     "上海市黄浦区面包", "社区烘焙补给站", "适合小团美食体验与路线补给。"),
)

# 当前业务定义来自 user_permission 现网备份，再叠加后续 P1 禁令/配额迁移：
# player/merchant 不能创建活动；只有 club 可创建活动。不得为跑 E2E 放宽。
DEFAULT_ROLE_PERMISSIONS = (
    ("player", 2, 2, 0, 1, 1, 0, 1, 0, "checkin", 0, 0, 0, 0, 0, "self", 1, 0),
    ("club", 5, None, 3, 3, 1, 1, 1, 0,
     "checkin,scan,quiz,puzzle,collect,occupy,minigame", 1, 1, 0, 1, 1,
     "theme+city", 1, 0),
    ("merchant", 0, None, 0, 3, 0, 0, 1, 1, "checkin,scan", 1, 1, 1, 0, 1,
     "store", 1, 3),
)
MERCHANT_CAPABILITY_SUBJECTS = (
    (8001, 9002), (8002, 9005), (8003, 9006), (8004, 9007), (8005, 9003),
)
CLUB_LEADER_CAPABILITY_SUBJECTS = ((5001, 9003), (5002, 9008))
SYSTEM_ROLE_ACCOUNTS = (
    (4, "e2e_auditor", "E2E 审核", 101),
    (5, "e2e_support", "E2E 客服", 102),
)
VISIBLE_TOPIC_NAME = "E2E 探店日一期"


def assert_local_scratch(host, schema):
    if host not in ("127.0.0.1", "localhost", "::1") or not schema.startswith("cy_e2e_"):
        raise SystemExit("只允许写入本机 cy_e2e_* 测试库")


def seed_recruit_deadline_job(execute):
    execute(
        "INSERT INTO sys_job (job_name,job_group,invoke_target,cron_expression,misfire_policy,"
        "concurrent,status,create_by,create_time,remark) "
        "SELECT '双死线-招募到期判定','DEFAULT',%s,'0 0/10 * * * ?','3','1','0',"
        "'e2e-seed',NOW(),'E2E TopicRecruitDeadlineTask' FROM dual "
        "WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target=%s)",
        (RECRUIT_JOB_TARGET, RECRUIT_JOB_TARGET),
    )

def seed_system_role_accounts(execute):
    """为已定义的审核/客服 sys_role 建立独立可登录夹具，不借用 admin。"""
    for user_id, username, nickname, role_id in SYSTEM_ROLE_ACCOUNTS:
        execute(
            "INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,"
            "create_by,create_time) SELECT %s,100,%s,%s,'00',%s,'0','0','admin',NOW() FROM dual "
            "WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE user_id=%s OR user_name=%s)",
            (user_id, username, nickname, ADMIN_HASH, user_id, username),
        )
        execute(
            "INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,%s FROM sys_user "
            "WHERE user_name=%s AND status='0' AND del_flag='0' "
            "AND NOT EXISTS (SELECT 1 FROM sys_user_role "
            "WHERE role_id=%s AND user_id=sys_user.user_id)",
            (role_id, username, role_id),
        )


def seed_discoverable_merchants(execute):
    """幂等补齐俱乐部“找商家”入口实际渲染所需的承接档案。"""
    sql = (
        "UPDATE mms_merchant SET coop_open=1,business_status=1,capacity=%s,available_time=%s,"
        "suit_activity_types=%s,charge_type=%s,address=%s,city_role=%s,description=%s "
        "WHERE id=%s"
    )
    for merchant_id, _member_id, _name, capacity, available_time, suit_types, charge_type, \
            address, city_role, description in DISCOVERABLE_MERCHANTS:
        execute(sql, (capacity, available_time, suit_types, charge_type, address, city_role,
                      description, merchant_id))


def seed_member_permission_defaults(execute):
    """只补不存在的默认档位；值跟随既有业务基线，不覆盖环境内已有配置。"""
    sql = (
        "INSERT INTO user_permission (role,max_themes,max_nodes_per_theme,max_events,max_coupons,"
        "can_create_theme,can_create_event,can_publish_coupon,can_redeem_coupon,node_types,"
        "can_branch,can_game_mechanic,can_open_as_node,can_design_medal,can_create_group,"
        "data_scope,withdrawable,max_city_nodes,membership_level) "
        "SELECT %s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NULL FROM dual "
        "WHERE NOT EXISTS (SELECT 1 FROM user_permission "
        "WHERE role=%s AND membership_level IS NULL)"
    )
    for row in DEFAULT_ROLE_PERMISSIONS:
        execute(sql, row + (row[0],))


def seed_club_leader_capabilities(execute):
    """实体主理人只在能力从未建立时获得对应能力；不复活已撤销记录。"""
    for leader_id, member_id in CLUB_LEADER_CAPABILITY_SUBJECTS:
        execute(
            "INSERT INTO member_capability "
            "(member_id,capability,status,granted_at,granted_by,create_time) "
            "SELECT member_id,'club_leader',1,NOW(),0,NOW() FROM club_leader "
            "WHERE id=%s AND member_id=%s AND status=1 AND del_flag=0 "
            "AND NOT EXISTS (SELECT 1 FROM member_capability mc "
            "WHERE mc.member_id=club_leader.member_id AND mc.capability='club_leader')",
            (leader_id, member_id),
        )


def seed_visible_topic(execute):
    """补一个可从正常列表进入的虚构免费主题；业务动作仍由真实接口创建。"""
    execute(
        "INSERT INTO cms_topic (member_id,topic_type,product_type,name,subtitle,description,status,"
        "user_status,merchant_status,lifecycle,del_flag,is_draft,is_template,club_id,self_play,"
        "self_play_price,create_by,create_time) "
        "SELECT 9003,2,2,%s,'隔离验收样板','仅用于本机 cy_e2e_ 免费流程入口验收',"
        "1,1,1,1,0,0,0,7001,1,0.00,'e2e-seed',NOW() FROM dual "
        "WHERE NOT EXISTS (SELECT 1 FROM cms_topic WHERE member_id=9003 AND name=%s "
        "AND status=1 AND user_status=1 AND del_flag=0)",
        (VISIBLE_TOPIC_NAME, VISIBLE_TOPIC_NAME),
    )


def seed_merchant_node_prerequisites(execute):
    """结构快照不含权限数据；只补缺失夹具，不复活已撤销能力或覆盖配额。"""
    seed_member_permission_defaults(execute)
    for merchant_id, member_id in MERCHANT_CAPABILITY_SUBJECTS:
        execute(
            "INSERT INTO member_capability (member_id,capability,status,granted_at,granted_by,create_time) "
            "SELECT member_id,'merchant',1,NOW(),0,NOW() FROM mms_merchant "
            "WHERE id=%s AND member_id=%s AND status=1 AND account_status=1 AND del_flag=0 "
            "AND NOT EXISTS (SELECT 1 FROM member_capability mc "
            "WHERE mc.member_id=mms_merchant.member_id AND mc.capability='merchant')",
            (merchant_id, member_id),
        )


def assert_discoverable_merchants(rows):
    expected = {merchant_id: (member_id, name) for merchant_id, member_id, name, *_ in
                DISCOVERABLE_MERCHANTS}
    if len(rows) != len(expected) or {row[0] for row in rows} != set(expected):
        raise RuntimeError("可对接商家应恰为四个 E2E 夹具，实际=%s" % (rows,))
    for row in rows:
        (merchant_id, member_id, name, coop_open, business_status, capacity, available_time,
         suit_types, charge_type, address, city_role, description) = row
        if ((member_id, name) != expected[merchant_id] or coop_open != 1 or business_status != 1
                or not capacity or not available_time or not suit_types or charge_type not in (0, 1)
                or not address or not city_role or not description):
            raise RuntimeError("商家 %s 可对接档案不完整:%s" % (merchant_id, row))


def clear_fake_fixture_images(execute):
    execute("UPDATE cms_member_template SET img_url=NULL WHERE id IN (6001,6002,6003,6004)")
    execute(
        "UPDATE cms_topic SET img_url=NULL WHERE member_id=%s AND name=%s "
        "AND img_url IN (%s,%s)",
        (9003, "E2E 探店日一期", "cover.jpg", "tpl.jpg"),
    )


def assert_no_fake_fixture_images(rows):
    bad = tuple(row for row in rows if row[2] in ("cover.jpg", "tpl.jpg"))
    if bad:
        raise RuntimeError("E2E fixture 仍含裸相对假图片路径:%s" % (bad,))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema", default="cy_e2e_51")
    ap.add_argument("--host", default="127.0.0.1"); ap.add_argument("--port", type=int, default=3316)
    ap.add_argument("--user", default="root"); ap.add_argument("--password", default="")
    a = ap.parse_args()
    assert_local_scratch(a.host, a.schema)
    c = pymysql.connect(host=a.host, port=a.port, user=a.user, password=a.password,
                        database=a.schema, autocommit=True, charset="utf8mb4")
    cur = c.cursor()

    def ex(sql, args=None):
        try:
            cur.execute(sql, args or ())
        except Exception as e:
            print("   ! %s  <= %s" % (str(e)[:90], sql.split("\n")[0][:70]))

    print("[seed] 关掉验证码(sys_config)")
    ex("INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time) "
       "VALUES('账号自助-验证码开关','sys.account.captchaEnabled','false','Y','admin',NOW()) "
       "ON DUPLICATE KEY UPDATE config_value='false'")

    print("[seed] 注册招募到期任务(topicRecruitDeadlineTask)")
    seed_recruit_deadline_job(ex)

    print("[seed] 后台部门 + 超管账号 admin(夹具口令见 chain_lib.E2E_SUPERADMIN_PASSWORD)")
    ex("INSERT INTO sys_dept(dept_id,parent_id,ancestors,dept_name,order_num,status,del_flag,create_by,create_time) "
       "VALUES(100,0,'0','城瘾总部',0,'0','0','admin',NOW())")
    ex("INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,email,phonenumber,sex,password,"
       "status,del_flag,create_by,create_time) VALUES(1,100,'admin','超管','00','a@b.c','13800000000','0',%s,"
       "'0','0','admin',NOW())", (SUPERADMIN_HASH,))
    ex("INSERT INTO sys_user_role(user_id,role_id) VALUES(1,1)")

    print("[seed] 对公付款与平台审核/客服独立账号(密码 admin123)")
    ex("INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,"
       "create_by,create_time) VALUES(2,100,'e2e_finance','E2E 财务','00',%s,'0','0','admin',NOW())",
       (ADMIN_HASH,))
    ex("INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,"
       "create_by,create_time) VALUES(3,100,'e2e_operator','E2E 运营','00',%s,'0','0','admin',NOW())",
       (ADMIN_HASH,))
    ex("INSERT INTO sys_user_role(user_id,role_id) VALUES(2,103)")
    ex("INSERT INTO sys_user_role(user_id,role_id) VALUES(3,100)")
    seed_system_role_accounts(ex)
    for name, perm, role_id in (("E2E 应付批次确认", "business:clubpayable:confirm", 103),
                                ("E2E 应付付款登记", "business:clubpayable:paid", 100)):
        ex("INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,"
           "visible,status,perms,icon,create_by,create_time) SELECT %s,2293,99,'','',1,0,'F','0','0',"
           "%s,'#','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms=%s)",
           (name, perm, perm))
        ex("INSERT INTO sys_role_menu(role_id,menu_id) SELECT %s,menu_id FROM sys_menu WHERE perms=%s "
           "AND NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=%s AND menu_id=sys_menu.menu_id)",
           (role_id, perm, role_id))

    print("[seed] 四个角色的 member(平台/商家/俱乐部/玩家)")
    # ★ 承接商家是【四个】不是一个:探店日 canonical15 硬性要求 storeCount == 4
    #   (TopicSaleCanonicalV1.validateStoreCount:136-138),而 storeCount 由
    #   TopicSaleCanonicalBuilder:104 算成【去重后的商家 member 数】—— 一个商家承接四章只算 1 家,
    #   开售提审必红 SALE_SNAPSHOT_MISSING_STORE_COUNT。「四店」是写死在码里的业务事实,不是凑数。
    for mid, nick, phone in ((9001, "平台运营", "13900000001"), (9002, "商家老板", "13900000002"),
                             (9003, "俱乐部主理人", "13900000003"), (9004, "玩家小城", "13900000004"),
                             (9005, "商家二号", "13900000005"), (9006, "商家三号", "13900000006"),
                             (9007, "商家四号", "13900000007"),
                             # ★执行俱乐部必须是【另一个人】的俱乐部:/api/coop/invite 禁止自邀自接
                             #   (ApiCoopController:214-217),而发起人又必须是主题发布者(:226-233)。
                             #   发布者 9003 自己要是 club_leader 才能开商家池(resolveMerchantPoolFlag),
                             #   所以「发布者的俱乐部」与「执行俱乐部」只能是两个。
                             (9008, "执行俱乐部主理人", "13900000008"),
                             # ③ 一个账号一张票(ExploreAccountTicketGate),同一期要跑第二张票就得换人
                             (9009, "玩家二号", "13900000009")):
        merchant_owner = mid in (9002, 9005, 9006, 9007)
        ex("INSERT INTO ums_member(id,nickname,phone,role,user_type,status,create_time) "
           "VALUES(%s,%s,%s,%s,%s,1,NOW())",
           (mid, nick, phone, "merchant" if merchant_owner else "player", 2 if merchant_owner else 1))

    print("[seed] 商家主体 + 俱乐部")
    ex("INSERT INTO sys_category(id,type,parent_id,category_name,level,sort_id,status,del_flag,create_time) "
       "VALUES(4001,1,0,'咖啡',1,1,1,0,NOW())")
    # 承接闸要 status=1 且 account_status=1(MerchantChapterApplicationServiceImpl:554-556),品类要与章节一致
    for mch_id, member_id, name in ((8001, 9002, '探店日样板咖啡'), (8002, 9005, '探店日样板书店'),
                                    (8003, 9006, '探店日样板花店'), (8004, 9007, '探店日样板面包'),
                                    # 发布者 9003 同时是主理人与商家 owner:前者才能开商家池,
                                    # 后者才能走 type1 商家→执行俱乐部合作单。它不承接章节,不计 storeCount。
                                    (8005, 9003, '主理人策划工作室')):
        ex("INSERT INTO mms_merchant(id,member_id,name,status,account_status,category_id,del_flag,create_time) "
           "VALUES(%s,%s,%s,1,1,4001,0,NOW())", (mch_id, member_id, name))
    seed_discoverable_merchants(ex)
    cur.execute(
        "SELECT id,member_id,name,coop_open,business_status,capacity,available_time,"
        "suit_activity_types,charge_type,address,city_role,description FROM mms_merchant "
        "WHERE id IN (8001,8002,8003,8004) ORDER BY id"
    )
    assert_discoverable_merchants(cur.fetchall())
    seed_merchant_node_prerequisites(cur.execute)
    ex("INSERT INTO club_leader(id,member_id,leader_name,phone,status,del_flag,create_time) "
       "VALUES(5001,9003,'俱乐部主理人','13900000003',1,0,NOW())")
    ex("INSERT INTO club_leader(id,member_id,leader_name,phone,status,del_flag,create_time) "
       "VALUES(5002,9008,'执行俱乐部主理人','13900000008',1,0,NOW())")
    seed_club_leader_capabilities(ex)
    cur.execute("SHOW COLUMNS FROM club")
    club_cols = {r[0] for r in cur.fetchall()}
    owner_col = next((x for x in ("owner_member_id", "member_id", "leader_member_id", "creator_id")
                      if x in club_cols), None)
    if owner_col:
        ex("INSERT INTO club(id,%s,name,status,create_time) VALUES(7001,9003,'城瘾跑团',1,NOW())" % owner_col)
        ex("INSERT INTO club(id,%s,name,status,create_time) VALUES(7002,9008,'执行俱乐部',1,NOW())" % owner_col)
    else:
        ex("INSERT INTO club(id,name,status,create_time) VALUES(7001,'城瘾跑团',1,NOW())")
        ex("INSERT INTO club(id,name,status,create_time) VALUES(7002,'执行俱乐部',1,NOW())")
    # 角色分配页只能给本俱乐部正常成员委派场次角色；把 E2E 玩家放进执行俱乐部，
    # 否则“俱乐部→领队→玩家”真实链路在第一步就被造数自身截断。
    ex("INSERT INTO club_member(club_id,member_id,role,status,join_time,del_flag) "
       "SELECT 7002,9009,0,1,NOW(),0 WHERE NOT EXISTS "
       "(SELECT 1 FROM club_member WHERE club_id=7002 AND member_id=9009 AND del_flag=0)")
    cur.execute("UPDATE club SET member_count=(SELECT COUNT(*) FROM club_member "
                "WHERE club_id=club.id AND status=1 AND del_flag=0) WHERE id IN (7001,7002)")
    seed_visible_topic(ex)

    print("[seed] 商家自己的玩法模板(图片验证 —— 探店日『看规则/传照片』那一步靠它)")
    # validation_method: 1=文字验证 2=图片验证 3=选项验证(CmsMemberTemplate.java:74)
    # 节点绑定闸要求模板【属于本商家】(ChapterMerchantNodeServiceImpl:246-252),所以 member_id=9002。
    # 字段按真实形状给全:玩家侧要读 rule_instructions / photo_require_desc / feedback_text,
    # 只塞标题的空壳会在通关那一步以「规则页空白」的形式炸,更难查。
    ex("INSERT INTO cms_member_template(id,original_template_id,member_id,category_id,title,description,"
       "img_url,players,usage_location,required_materials,duration,difficulty,is_sync,rule_instructions,"
       "validation_method,feedback_text,photo_require_desc,photo_review,status,publish_status,del_flag,"
       "create_by,create_time) VALUES(6001,0,9002,4001,'到店打卡·拍一张你的咖啡',"
       "'进店后按规则拍一张照片,店员核销即完成本站',NULL,'1-4人','店内','手机',15,'简单',0,"
       "'1. 到吧台报「自由探索」；2. 点单后在窗边座位拍一张带杯型 logo 的照片；3. 把照片给店员看即可核销。',"
       "2,'干得漂亮！这一站完成了,去下一家吧。','需能看清杯身 logo 与店内环境,不要只拍杯口',0,1,1,0,"
       "'admin',NOW())")
    # 其余三家各自一份同形状模板(节点绑定闸要求模板属于本商家)
    for tpl_id, member_id, title in ((6002, 9005, '到店打卡·拍一张你选的书'), (6003, 9006, '到店打卡·拍一束你挑的花'),
                                     (6004, 9007, '到店打卡·拍一只你选的面包')):
        ex("INSERT INTO cms_member_template(id,original_template_id,member_id,category_id,title,description,"
           "img_url,players,usage_location,required_materials,duration,difficulty,is_sync,rule_instructions,"
           "validation_method,feedback_text,photo_require_desc,photo_review,status,publish_status,del_flag,"
           "create_by,create_time) VALUES(%s,0,%s,4001,%s,"
           "'进店后按规则拍一张照片,店员核销即完成本站',NULL,'1-4人','店内','手机',15,'简单',0,"
           "'1. 进店报「自由探索」；2. 按提示拍一张能看清主体的照片；3. 把照片给店员看即可核销。',"
           "2,'干得漂亮！这一站完成了,去下一家吧。','需能看清主体与店内环境',0,1,1,0,'admin',NOW())",
           (tpl_id, member_id, title))
    clear_fake_fixture_images(ex)
    cur.execute(
        "SELECT 'template',id,img_url FROM cms_member_template WHERE id IN (6001,6002,6003,6004) "
        "UNION ALL SELECT 'topic',id,img_url FROM cms_topic "
        "WHERE member_id=9003 AND name='E2E 探店日一期'"
    )
    assert_no_fake_fixture_images(cur.fetchall())

    print("[seed] 商家常备权益模板(章节 terms_mode=PERK 时,入驻供给必须从它复制快照)")
    # perk_min_value=30 ⇒ retail_value 必须 >= 30,否则 assertTermsShape 会拒。
    # valid_end 要在活动期之后:materializeStandalonePerkFromTemplate 会用它冻结供给有效期,
    # 过期的模板会被「权益模板已过期」拒(CoopInviteServiceImpl:1048)。
    ex("INSERT INTO coop_perk_template(id,merchant_id,perk_type,name,unit_cost,quota,redeem_rule,"
       "valid_end,create_time,del_flag,retail_value) VALUES(3001,9002,1,'到店赠美式一杯',12.00,200,"
       "'到店出示核销码,每人限一杯','2099-12-31 23:59:59',NOW(),0,32.00)")
    for perk_id, member_id, name in ((3002, 9005, '到店赠精选书签一枚'), (3003, 9006, '到店赠鲜花一支'),
                                     (3004, 9007, '到店赠现烤面包一只')):
        ex("INSERT INTO coop_perk_template(id,merchant_id,perk_type,name,unit_cost,quota,redeem_rule,"
           "valid_end,create_time,del_flag,retail_value) VALUES(%s,%s,1,%s,12.00,200,"
           "'到店出示核销码,每人限一份','2099-12-31 23:59:59',NOW(),0,32.00)", (perk_id, member_id, name))

    print("\n[seed] 结果")
    for t in ("sys_user", "sys_user_role", "ums_member", "mms_merchant", "club", "sys_config"):
        try:
            cur.execute("SELECT COUNT(*) FROM `%s`" % t); print("   %-16s %d 行" % (t, cur.fetchone()[0]))
        except Exception as e:
            print("   %-16s ❌ %s" % (t, str(e)[:70]))
    c.close()


if __name__ == "__main__":
    main()

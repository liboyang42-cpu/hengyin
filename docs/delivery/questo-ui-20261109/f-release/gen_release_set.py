#!/usr/bin/env python3
"""f-release：为上线候选生成「发布迁移集 + 每文件只读预检」。只读 git 对象与仓库文件，不连任何库/主机。

口径
- 生产参考基线沿用 release-closeout-20261029：那份 88 项迁移包（基线 19408d49 / Merge #452）
  在生产上**从未被确认执行**，所以 88 项全部仍在本次发布集里，复用其已审阶段与预检分类块。
- 10-29 包源提交 60048713 之后、到候选头为止新增的迁移，用 ci/release_manifest.migration_diff 取得，
  逐项人工标注（见 NEW_META）。
- 执行序 = 候选头上 ci/migration_ledger 的全量拓扑序（依赖来自 ci/migration-dependencies.json）。
- 生产执行状态一律 UNKNOWN，必须由业主跑本目录 release-precheck.sql 判定。

用法（仓库根目录）：
  python3 docs/delivery/questo-ui-20261109/f-release/gen_release_set.py \
      --head origin/launch/questo-20261110 --out docs/delivery/questo-ui-20261109/f-release
  加 --check 只校验已提交产物与重新生成的结果一致（不写文件）。
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "ci"))

from ci.release_manifest import migration_diff, migrations_at, read_blob  # noqa: E402
from ci.sql_splitter import executable_statement, split_statements_with_delimiters  # noqa: E402

PACK_DIR = ROOT / "docs/delivery/release-closeout-20261029"
PACK_JSON = PACK_DIR / "release-migration-pack.json"
PACK_PRECHECK = PACK_DIR / "release-migration-precheck.sql"
PACK_SOURCE = "60048713ab6a57890a056fe3091f5be441919939"   # 10-29 包 generatedFor.masterCommit
MASTER_BRIEF_BASE = "2bb811437"                              # BRIEF.md 所写 A 线基点（master）

REQUIRED, OPTIONAL, OWNER, SKIP = "必须", "可选", "需业主授权", "不执行"
PRE, POST = "PRE_DEPLOY", "POST_DEPLOY"

# 10-29 包内、但因定时任务 bean 校验需要挪到新 jar 之后的纯 sys_job 文件（见 POST_DEPLOY_REASON）。
PACK_POST_DEPLOY = {"o14_reco_search_jobs_20261022", "20261023_g_wp08_delivery_jobs_paused"}
POST_DEPLOY_REASON = ("只插 sys_job 行：CanonicalJobTarget.canonical 会对每一行 invoke_target 做 getBean，"
                      "旧 jar 没有该 bean 时调度器整体失效（参见 migration_20261104_content_safety_jobs_post_deploy.sql 头注释），"
                      "所以放到新 jar 健康之后执行")

# 10-29 之后新增迁移的人工标注：(类别, 阶段, 说明, 预检检查)
# 预检检查留空 = 从 SQL 自动提取表/列/索引/菜单权限/定时任务/配置键；
# 字符串 = 直接使用该布尔表达式（1=已应用）；"READBACK" = 复用迁移文件自身最后一条只读断言 SELECT。
NEW_META = {
    "20261029_activity_cancellation_receipt": (REQUIRED, PRE, "cms_activity 新增取消回执列；新 jar 读写", None),
    "20261029_topic_cancellation_receipt": (REQUIRED, PRE, "cms_topic.cancellation_epoch + 取消回执表；新 jar 读写", None),
    "20261031_coop_invite_read_time": (REQUIRED, PRE, "coop_invite.read_time 已读回执列", None),
    "20261101_author_write_receipt": (REQUIRED, PRE, "作者写入幂等回执表", None),
    "20261101_stagger_sys_job_seconds": (OPTIONAL, PRE, "只改既有 sys_job 的 cron 秒位错峰（缓解整点连接池等待）；须在重启 jar 前执行才生效", "READBACK"),
    "20261101_theme_categories": (REQUIRED, PRE, "改名 4 个主题类别 + 新增 7 个（不删行、不改 id；业主 11-01 走查已批）；商家行业迁移依赖它", "READBACK"),
    "20261102_merchant_industry_categories": (REQUIRED, PRE, "新增 sys_category type=6 商家行业；新 jar 商家类目读 type 1+6，缺了商家行业为空", "READBACK"),
    "20261103_content_safety_recheck": (REQUIRED, PRE, "内容安全自动复检队列表", None),
    "20261103_sensitive_content_library": (REQUIRED, PRE, "敏感内容库 4 表 + 开关（默认只报告）+ 后台菜单（零授权）", None),
    "20261104_content_safety_jobs_post_deploy": (REQUIRED, POST, "两条暂停的 sys_job；台账 POST_DEPLOY，新 jar 健康后再跑；回滚旧 jar 前须先删这两行", None),
    "20261104_invitee_signup_points_off": (OWNER, PRE, "积分规则 id 6（被邀请人报名送分）status/value 置 0", "READBACK"),
    "20261104_merchant_industry_categories_readback": (REQUIRED, PRE, "只读断言（紧跟 20261102 商家行业）", "READBACK"),
    "20261104_topic_total_time_seconds": (OWNER, POST, "把编辑器误按分钟落库的 cms_topic.total_time 换算成秒；台账 POST_DEPLOY（旧 jar 仍按分钟写）", "READBACK"),
    "20261105_chapter_video": (REQUIRED, PRE, "cms_topic_chapter 开场/结局视频两列", None),
    "20261105_welcome_points_fifty": (OWNER, PRE, "积分规则 id 1（注册赠送）value 改 50，与新 jar 余额口径一致", "READBACK"),
    "20261107_template_adoption_inherited_config_hash": (REQUIRED, PRE, "template_adoption_receipt 新列 + 索引", None),
    "20261108_play_help_run_quota": (REQUIRED, PRE, "整局共享提示额度计数表", None),
    "20261108_registration_seat_invitation": (REQUIRED, PRE, "多人票席位邀请两张事实表", None),
    "20261108_topic_content_revision": (REQUIRED, PRE, "不可变内容修订索引表", None),
    "20261109_app_media_provenance": (REQUIRED, PRE, "app_media_asset 来源 5 列（素材来源）", None),
    "20261109_play_run_feedback": (REQUIRED, PRE, "完局问题反馈表", None),
    "20261110_ecommerce_payout": (REQUIRED, PRE, "电商收付通 6 表（开关默认关）", None),
    "20261110_ecommerce_payout_wiring": (REQUIRED, POST, "ecom_refund 表 + 两条暂停 sys_job（ecommercePayoutTask）+ 菜单；含 sys_job，放新 jar 之后（开关关时新 jar 不读本表）", None),
    "20261110_platform_pass": (REQUIRED, PRE, "通行证 4 表 + 两个未上架套餐（开关默认关）", None),
    "zzz_committed_official_notice_job_reregister_20261111": (REQUIRED, POST, "f-rehearsal 修正：按 invoke_target 判重补登记暂停的 committedOfficialNoticeTask 任务（正常路径空操作；jar 回滚后前滚时恢复该任务，原 step 11 是单次执行不能重跑）", None),
    "20261111_ecommerce_combine": (REQUIRED, POST, "合单支付 6 表（开关默认关）；依赖 ecommerce_payout_wiring，随它放新 jar 之后", None),
    "20261111_ai_generation_log_retention_index": (REQUIRED, PRE, "ai_generation_log 加 create_time 索引（f-compliance P0-10 留存清理按时间分批删，缺索引会全表扫）", None),
    "20261111_aigc_filing_config_seed": (REQUIRED, PRE, "sys_config 新增空值键 aigc.algorithm.filing-no（备案号公示位，空值不显示；拿到备案号后运营在后台填）", "READBACK"),
    "20261111_creator_income_detail_idempotency": (OPTIONAL, PRE, "creator_income_detail 加 idempotency_key 列与唯一键（f-money P0-17，备将来写入用；新 jar 不读不写）", "READBACK"),
    "20261111_rule_seed_split_steps": (OWNER, PRE, "官方模板种子规则改成一步一行（f-pages P-41；只改仍是种子原文的行）", "READBACK"),
    "20261102_club_session_gather_point": (OWNER, PRE, "回填未结束俱乐部场次的集合点坐标（只填空值）", None),
    "20261104_club_session_gather_point_readback": (OWNER, PRE, "只读断言（紧跟集合点回填）", "READBACK"),
    "20261029_club_post_repost": (REQUIRED, PRE, "俱乐部帖文转发表 + club_post.repost_count", None),
    "marketing_attribution_20261108": (REQUIRED, PRE, "营销归因 2 表", None),
    "marketing_product_20261108": (REQUIRED, PRE, "营销商品/礼品 5 表", None),
    "marketing_extra_seat_20261108": (REQUIRED, PRE, "加座：放宽 CHECK 到 (1,2,3) + 索引", None),
    "marketing_revenue_allocation_20261108": (REQUIRED, PRE, "收入分配 4 表", None),
    "marketing_z_channel_voucher_20261108": (REQUIRED, PRE, "渠道兑换码 5 表", None),
    "marketing_task_registration_20261108": (REQUIRED, POST, "两条暂停的 sys_job（渠道退款补消费、收入分配队列）；纯 sys_job，放新 jar 之后", None),
    "marketing_redemption_constraint_name_20261108": (REQUIRED, PRE, "改名 redemption 表的 CHECK 约束（为下一文件让名）", None),
    "marketing_promotion_20261108": (REQUIRED, PRE, "促销 7 表", None),
    "preset_draft_source_20261108": (REQUIRED, PRE, "cms_member_template.preset_source", None),
    "public_content_catalog_20261108": (SKIP, PRE, "被 public_content_catalog_index_shape_20261109 逐字替代（原断言把两列唯一索引数成 1 行，必失败）；只执行替代文件", None),
    "public_content_catalog_index_shape_20261109": (REQUIRED, PRE, "公开目录 2 表（替代上一文件，DDL 逐字相同、断言已修）", None),
    "public_content_catalog_node_topic_index_20261108": (REQUIRED, PRE, "cms_topic_node(topic_id) 索引（大表加索引，看锁）", None),
    "20261103_settlement_rule_three_percent": (OWNER, PRE, "通用结算规则平台抽成改 3%（资金口径）", "READBACK"),
    "20261102_club_checkin_phone_visible": (REQUIRED, PRE, "club_role_assignment.customer_phone_visible", None),
    "club_host_reservation_20261109": (REQUIRED, PRE, "club_event_series.host_reservation_required", None),
    "20261030_merchant_game_mechanics": (OWNER, PRE, "商家默认档位开放分支/博弈玩法（user_permission）", "READBACK"),
    "20261104_merchant_activity_limit": (OWNER, PRE, "商家默认档位同时 3 场活动（user_permission）", "READBACK"),
    "20261031_roam_session_track": (REQUIRED, PRE, "roam_session.track_json", None),
    "20261101_prefab_life_template_copy": (OWNER, PRE, "官方模板 990059 文案修订（只改仍是种子原文的行）", "READBACK"),
    "20261101_prefab_life_template_state_keys": (OPTIONAL, PRE, "为官方模板 990059 补注册两个状态键（不补则套用后发布被拒）", "READBACK"),
    "topic_location_contract_20261107": (REQUIRED, PRE, "cms_topic.content_contract_json；selectCmsTopicVo 读它", None),
    "20261030_merchant_game_covers_to_oss": (OWNER, PRE, "平台商家玩法封面 URL 改指 OSS（先确认 OSS 上对象已存在）", "READBACK"),
    "video_bounty_host_tip_20261108": (REQUIRED, PRE, "视频悬赏 + 主持小费 6 表", None),
    "video_bounty_group_verification_20261108": (REQUIRED, PRE, "video_bounty_submission.verified_group_key + 索引", None),
    "20261104_review_wording_seeded_text": (OWNER, PRE, "平台种子文案「盲盒/探店日/竞猜」改名（只改逐字等于原文的行）", "READBACK"),
    "20261109_play_help_audit_permission": (REQUIRED, PRE, "后台按钮权限 business:playhelp:query（零角色授权）",
                                            "(SELECT COUNT(*) = 1 FROM sys_menu WHERE perms = 'business:playhelp:query' AND menu_type = 'F')"),
    "20261109_questo_admin_menus": (REQUIRED, PRE, "Questo 后台新页面菜单（零角色授权）", None),
    "x12_topic_look_20261029": (REQUIRED, PRE, "cms_topic.look_json 主题画面风格", None),
    "z_channel_voucher_snapshot_20261109": (REQUIRED, PRE, "marketing_channel_voucher.grant_snapshot_json", None),
    "z_marketing_revenue_channel_reverse_kind_20261108": (SKIP, PRE, "被 *_index_shape_20261109 逐字替代（原守卫按 1 行数两列唯一索引，ALTER 会被静默跳过）；只执行替代文件", None),
    "z_marketing_revenue_channel_reverse_kind_index_shape_20261109": (REQUIRED, PRE, "收入投递队列 CHECK 放宽到 task_kind (1,2,3)（替代上一文件）", None),
    "z_workshop_apple_iap_20261109": (REQUIRED, PRE, "工坊 Apple IAP 3 表", None),
    "z_workshop_purchase_20261109": (REQUIRED, PRE, "工坊购买 3 表", None),
    "z_workshop_paid_review_20261109": (REQUIRED, PRE, "工坊付费审核：workshop_version/order 加列与唯一键 + 2 表", None),
    "zz_questo_collation_align_20261109": (REQUIRED, PRE, "Questo 新表隐式 0900_ai_ci 字符列改到 utf8mb4_general_ci（修 1267 排序规则冲突）", None),
}

# 自动提取不到或需要特别判定的检查（布尔表达式，1 = 该对象已就位）
EXTRA_CHECKS = {
    "20261102_club_session_gather_point": [
        "(SELECT COUNT(*) = 0 FROM cms_activity a WHERE a.del_flag = '0' AND a.club_id IS NOT NULL"
        " AND (a.latitude IS NULL OR a.latitude = '') AND (a.longitude IS NULL OR a.longitude = '')"
        " AND a.end_time > NOW() AND a.status <> 3)  /* 粗判：需回填的场次为 0；精确谓词见 20261104 readback */"],
    "marketing_redemption_constraint_name_20261108": [
        "(SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()"
        " AND table_name = 'redemption' AND constraint_type = 'CHECK' AND constraint_name = 'ck_perk_redemption_status')"],
    "marketing_extra_seat_20261108": [
        "(SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE()"
        " AND constraint_name = 'ck_marketing_order_fulfillment' AND check_clause LIKE '%3%')"],
    "z_marketing_revenue_channel_reverse_kind_index_shape_20261109": [
        "(SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE()"
        " AND constraint_name = 'ck_revenue_posting_kind' AND check_clause LIKE '%3%')"],
    "z_marketing_revenue_channel_reverse_kind_20261108": [
        "(SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE()"
        " AND constraint_name = 'ck_revenue_posting_kind' AND check_clause LIKE '%3%')"],
    "zz_questo_collation_align_20261109": [
        "(SELECT COUNT(*) = 0 FROM information_schema.columns WHERE table_schema = DATABASE()"
        " AND collation_name = 'utf8mb4_0900_ai_ci' AND table_name IN (%s))"],
}

# 台账判「幂等」、但文件第一句是「目标对象必须都不存在」的前置断言（JSON_EXTRACT 故意报 3141）的迁移：
# 重跑必然在前置断言处报错停下（不改任何状态）。f-rehearsal 第二遍实测，所以标成「APPLIED 时必须跳过」。
PREFLIGHT_SINGLE_APPLY = {
    "marketing_attribution_20261108", "marketing_product_20261108", "marketing_revenue_allocation_20261108",
    "marketing_z_channel_voucher_20261108", "video_bounty_host_tip_20261108",
}

# 10-29 包分类块钉的是「建表后」的精确列/索引指纹；同一发布集里后续迁移再给这张表加列/索引后，旧指纹会把
# 已应用误判成 CONFLICT（f-rehearsal 在 MySQL 8.0 演练中发现）。这里为这类表追加「后续迁移落地后」的第二个指纹，
# 两个形状之一完全吻合即 APPLIED，其余形状仍判 CONFLICT。指纹取自演练库按发布集执行后的 information_schema。
# 表名 -> (列数, 列指纹, 索引行数, 索引指纹, 追加形状来源)
PACK_POST_SHAPES = {
    "20261027_template_adoption_receipt": (
        "template_adoption_receipt", 13, 15081007648298938700, 6, 7132261291781051820,
        "20261107_template_adoption_inherited_config_hash"),
}

FORBIDDEN = re.compile(r"\b(INSERT|UPDATE|DELETE|REPLACE|CREATE|ALTER|DROP|TRUNCATE|RENAME|GRANT|REVOKE|SET|PREPARE|EXECUTE|CALL|LOCK|HANDLER|LOAD|COMMIT|ROLLBACK|START)\b", re.I)


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def strip_literals(sql):
    return re.sub(r"'(?:[^']|'')*'", "''", sql)


def assert_read_only(sql, label):
    statements = [executable_statement(s).strip() for s, _ in split_statements_with_delimiters(sql)]
    for statement in statements:
        if not statement:
            continue
        if not statement.upper().startswith("SELECT"):
            raise SystemExit("预检不是 SELECT（%s）: %s" % (label, statement[:120]))
        bad = FORBIDDEN.search(strip_literals(statement))
        if bad:
            raise SystemExit("预检含写/会话语句 %s（%s）" % (bad.group(0), label))
        if re.search(r"(?<!@)@(?!@)\w", strip_literals(statement)) or re.search(r"\btmp_\w+", strip_literals(statement)):
            raise SystemExit("预检引用会话变量或临时表（%s）: %s" % (label, statement[:160]))


def extract_objects(text):
    """从迁移 SQL（含动态 DDL 字符串）提取它要建立的对象。"""
    body = "\n".join(l for l in text.splitlines() if not l.strip().startswith("--"))
    tables = re.findall(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(", body, re.I)
    columns, indexes = [], []
    for m in re.finditer(r"ALTER\s+TABLE\s+`?(\w+)`?", body, re.I):
        table = m.group(1)
        # 引号奇偶判断 ALTER 是否在动态 DDL 字符串里（'' 转义成对出现，不改变奇偶）
        in_string = body[:m.start()].count("'") % 2 == 1
        rest = body[m.end():]
        stop = re.search(r"(?<!')'(?!')", rest) if in_string else re.search(r";", rest)
        clause = rest[: stop.start() if stop else len(rest)]
        columns += [(table, c) for c in re.findall(r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)", clause, re.I)]
        indexes += [(table, i) for i in re.findall(r"ADD\s+(?:UNIQUE\s+)?(?:INDEX|KEY)\s+`?(\w+)", clause, re.I)]
    indexes += [(t, i) for i, t in re.findall(r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+`?(\w+)`?\s+ON\s+`?(\w+)", body, re.I)]
    temps = set(re.findall(r"CREATE\s+TEMPORARY\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)", body, re.I))
    tables = [t for t in dict.fromkeys(tables) if t not in temps]
    created = set(tables)
    columns = [c for c in dict.fromkeys(columns) if c[0] not in created]
    indexes = [i for i in dict.fromkeys(indexes) if i[0] not in created]
    # 菜单：迁移自身用 NOT EXISTS 守卫的 perms / component 即它要建立的菜单行
    menus = sorted(set(re.findall(r"NOT\s+EXISTS\s*\(\s*SELECT\s+1\s+FROM\s+sys_menu\s+WHERE\s+perms\s*=\s*'([^']+)'", body, re.I)))
    menus += ["component:" + c for c in sorted(set(re.findall(
        r"NOT\s+EXISTS\s*\(\s*SELECT\s+1\s+FROM\s+sys_menu\s+WHERE\s+component\s*=\s*'([^']+)'", body, re.I)))]
    if not menus and re.search(r"INSERT\s+INTO\s+`?sys_menu", body, re.I):
        menus = sorted(set(re.findall(r"perms\s*=\s*'(business:[^']+)'", body, re.I)))
    jobs = sorted(set(re.findall(r"'(\w+\.\w+\(\))'", body))) if re.search(r"INSERT\s+INTO\s+`?sys_job", body, re.I) else []
    configs = sorted(set(re.findall(r"'((?:[a-z0-9_]+\.)+[a-z0-9_\-]+)'", body))) if re.search(r"INSERT\s+INTO\s+`?sys_config", body, re.I) else []
    return tables, columns, indexes, menus, jobs, configs


def object_checks(text):
    tables, columns, indexes, menus, jobs, configs = extract_objects(text)
    checks = []
    checks += ["(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '%s')" % t for t in tables]
    checks += ["(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '%s' AND column_name = '%s')" % c for c in columns]
    checks += ["(SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = '%s' AND index_name = '%s')" % i for i in indexes]
    checks += ["(SELECT COUNT(*) > 0 FROM sys_menu WHERE component = '%s')" % p[10:] if p.startswith("component:")
               else "(SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = '%s')" % p for p in menus]
    checks += ["(SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = '%s')" % j for j in jobs]
    checks += ["(SELECT COUNT(*) > 0 FROM sys_config WHERE config_key = '%s')" % k for k in configs]
    summary = []
    if tables: summary.append("表 " + "、".join(tables))
    if columns: summary.append("列 " + "、".join("%s.%s" % c for c in columns))
    if indexes: summary.append("索引 " + "、".join("%s.%s" % i for i in indexes))
    if menus: summary.append("菜单行 %d 个" % len(menus))
    if jobs: summary.append("定时任务 " + "、".join(jobs))
    if configs: summary.append("配置键 " + "、".join(configs))
    return checks, "；".join(summary)


def readback_expr(text, label):
    statements = [executable_statement(s).strip() for s, _ in split_statements_with_delimiters(text)]
    selects = [s for s in statements if s.upper().startswith("SELECT")]
    if not selects:
        raise SystemExit("找不到只读断言: %s" % label)
    expr = "(%s)" % selects[-1]
    assert_read_only(selects[-1], label)
    return expr


def classifier(step, migration_id, cls, phase, checks, detail):
    expected = len(checks)
    total = " + ".join("IFNULL(%s, 0)" % c for c in checks)
    return ("SELECT %d AS step, '%s' AS migration_id, '%s' AS release_class, '%s' AS phase,\n"
            "  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,\n"
            "  CONCAT(present, '/', expected) AS objects_present, '%s' AS detail\n"
            "FROM (SELECT (%s) AS present, %d AS expected) x;"
            % (step, migration_id, cls, phase, detail.replace("'", "''"), total, expected))


def pack_blocks():
    text = PACK_PRECHECK.read_text(encoding="utf-8")
    first = re.search(r"(?m)^SELECT \d+ AS exec_order,", text).start()
    end = text.index("\nORDER BY exec_order;", first)
    blocks = {}
    for block in re.split(r"\nUNION ALL\n(?=SELECT \d+ AS exec_order,)", text[first:end]):
        blocks[re.search(r"'([a-z0-9_]+)' AS migration_id", block).group(1)] = block
    return blocks


def build(head):
    head_sha = git("rev-parse", head)
    pack = json.loads(PACK_JSON.read_text(encoding="utf-8"))
    all_items = migrations_at(str(ROOT), head_sha)
    by_path = {m["path"]: m for m in all_items}
    rows = []
    diff = migration_diff(str(ROOT), PACK_SOURCE, head_sha)
    # qx-final6 起 pack 契约也收录了 10-29 之后的新增项（供隔离演练）；这些项仍按 NEW_META 标注，此处只取原 10-29 包。
    added_since_pack = {c["migrationId"] for c in diff["changes"]}
    for m in pack["migrations"]:
        if m["migrationId"] in added_since_pack:
            continue
        now = by_path.get(m["path"])
        if not now or now["sha256"] != m["sha256"]:
            raise SystemExit("10-29 包内迁移在候选头上缺失或内容变化: %s" % m["path"])
        if m.get("rerunPolicy") == "REPLACED_UNAPPLIED":
            cls, phase, note = SKIP, PRE, "已被替代，永不执行（10-29 包 REPLACED_UNAPPLIED）"
        elif m["phase"] == "OWNER_GATED_DATA":
            cls, phase, note = OWNER, (POST if m["migrationId"] in PACK_POST_DEPLOY else PRE), m["changes"]
        elif m["phase"] == "PRE_DEPLOY_REQUIRED":
            cls, phase, note = REQUIRED, PRE, m["changes"]
        else:
            cls, phase, note = OPTIONAL, (POST if m["migrationId"] in PACK_POST_DEPLOY else PRE), m["changes"]
        if m["migrationId"] in PACK_POST_DEPLOY:
            note = note + "（f-release 改到新 jar 之后：" + POST_DEPLOY_REASON + "）"
        rerun = {"REPLACED_UNAPPLIED": "永不执行", "PRECHECK_SKIP_APPLIED": "APPLIED 时必须跳过"}.get(m.get("rerunPolicy"), "幂等")
        rows.append({"source": "10-29 发布包（未确认执行）", "class": cls, "phase": phase, "note": note, "rerun": rerun,
                     "packPhase": m["phase"], "migrationId": now["migrationId"], "path": now["path"],
                     "sha256": now["sha256"], "order": now["order"], "dependsOn": now["dependsOn"]})
    if any(c["change"] != "ADDED" for c in diff["changes"]):
        raise SystemExit("10-29 之后有迁移被修改或删除: %s" % [c["path"] for c in diff["changes"] if c["change"] != "ADDED"])
    since_master = {c["path"] for c in migration_diff(str(ROOT), MASTER_BRIEF_BASE, head_sha)["changes"]}
    unknown = sorted({c["migrationId"] for c in diff["changes"]} - set(NEW_META))
    stale = sorted(set(NEW_META) - {c["migrationId"] for c in diff["changes"]})
    if unknown or stale:
        raise SystemExit("NEW_META 需要人工补标注: 未标注=%s 多余=%s" % (unknown, stale))
    for c in diff["changes"]:
        cls, phase, note, _ = NEW_META[c["migrationId"]]
        rows.append({"source": "launch 波次（master 2bb81143 之后）" if c["path"] in since_master else "10-29 之后的 master",
                     "class": cls, "phase": phase, "note": note, "packPhase": None,
                     "rerun": "永不执行" if cls == SKIP else ("APPLIED 时必须跳过" if c["idempotency"] == "NEEDS_REVIEW"
                                                       or c["migrationId"] in PREFLIGHT_SINGLE_APPLY else "幂等"),
                     "migrationId": c["migrationId"], "path": c["path"], "sha256": c["sha256"],
                     "order": c["order"], "dependsOn": c["dependsOn"],
                     "idempotency": c["idempotency"], "destructiveStatements": c["destructiveStatements"]})
    rows.sort(key=lambda r: r["order"])
    for step, row in enumerate(rows, 1):
        row["step"] = step
    rounds = execution_rounds(rows)
    return head_sha, diff["headMigrationCount"], rows, rounds


ROUND_NAMES = ("1_PRE_REQUIRED", "2_PRE_OPTIONAL", "3_POST_DEPLOY", "4_OWNER_DATA_PRE", "5_OWNER_DATA_POST")


def execution_rounds(rows):
    """业主执行轮次：①必须（先于 jar）②可选（先于 jar）—部署 jar—③jar 之后的必须/可选 ④⑤需业主授权的数据项。
    每轮内按 step；校验每个依赖都排在前面（被替代项视为已由替代文件覆盖）。"""
    by_id = {r["migrationId"]: r for r in rows}
    rounds = {name: [] for name in ROUND_NAMES}
    for r in rows:
        if r["class"] == SKIP:
            continue
        if r["class"] == OWNER:
            rounds["4_OWNER_DATA_PRE" if r["phase"] == PRE else "5_OWNER_DATA_POST"].append(r["migrationId"])
        elif r["phase"] == POST:
            rounds["3_POST_DEPLOY"].append(r["migrationId"])
        else:
            rounds["1_PRE_REQUIRED" if r["class"] == REQUIRED else "2_PRE_OPTIONAL"].append(r["migrationId"])
    # 必须项的集内前置被拉进第 1 轮
    promoted = True
    while promoted:
        promoted = False
        for migration_id in list(rounds["1_PRE_REQUIRED"]):
            for dep in by_id[migration_id]["dependsOn"]:
                if dep in rounds["2_PRE_OPTIONAL"]:
                    rounds["2_PRE_OPTIONAL"].remove(dep)
                    rounds["1_PRE_REQUIRED"].append(dep)
                    by_id[dep]["note"] += "（被必须项依赖，升为必须、提前到第 1 轮）"
                    by_id[dep]["class"] = REQUIRED
                    promoted = True
        rounds["1_PRE_REQUIRED"].sort(key=lambda i: by_id[i]["step"])
    seen = set(r["migrationId"] for r in rows if r["class"] == SKIP)
    for name in ROUND_NAMES:
        for migration_id in rounds[name]:
            missing = [d for d in by_id[migration_id]["dependsOn"] if d in by_id and d not in seen]
            if missing:
                raise SystemExit("执行轮次破坏依赖: %s 需要 %s" % (migration_id, missing))
            seen.add(migration_id)
    return rounds


def render_precheck(head_sha, rows):
    blocks = pack_blocks()
    out = [
        "-- =====================================================================================",
        "-- f-release 只读预检（候选 %s）" % head_sha,
        "-- 每个发布迁移一条 SELECT，返回 step / migration_id / release_class / phase / status。",
        "-- 只读：全部是 SELECT（information_schema 与业务表），生成器逐句校验，无写入、无 SET、无 PREPARE。",
        "-- 不含任何主机、端口、账号；由业主用只读账号执行（建议先 SET SESSION TRANSACTION READ ONLY，不在本文件里）。",
        "-- 10-29 包的 88 项：原样复用 docs/delivery/release-closeout-20261029/release-migration-precheck.sql",
        "--   的主分类块（列/索引指纹已在 MySQL 8.0 演练中钉住），状态含义见该文件头；其余节（D-PAYEE 授权矩阵、",
        "--   种子键、依赖前置、锁/体量、长事务）请把那份文件整份再跑一次。",
        "-- 之后新增的项：按迁移要建立的对象计数，APPLIED=全部就位，NEEDED=全部缺失，PARTIAL=部分就位（先停下核对）；",
        "--   数据类迁移复用迁移文件自身的只读断言，1/1=已应用（或无需改动），0/1=需要执行。",
        "-- 生产执行状态在本文件运行前一律 UNKNOWN。",
        "-- =====================================================================================",
        "",
        "SELECT VERSION() AS mysql_version, DATABASE() AS current_schema, @@transaction_read_only AS session_read_only;",
        "",
    ]
    for r in rows:
        out.append("-- [%d] %s · %s · %s" % (r["step"], r["path"].split("/")[-1], r["class"], r["phase"]))
        if r["packPhase"] is not None:
            block = re.sub(r"^SELECT \d+ AS exec_order", "SELECT %d AS exec_order" % r["step"],
                           blocks[r["migrationId"]], count=1)
            if r["migrationId"] in PACK_POST_SHAPES:
                table, cols, col_fp, idx, idx_fp, source = PACK_POST_SHAPES[r["migrationId"]]
                alt = ("THEN 1 WHEN COUNT(*) = %d AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,"
                       "column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = %d\n"
                       "       AND (SELECT COUNT(*) = %d AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,"
                       "seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = %d FROM information_schema.statistics "
                       "WHERE table_schema = DATABASE() AND table_name = '%s')\n"
                       "       THEN 1 ELSE 2 END" % (cols, col_fp, idx, idx_fp, table))
                if block.count("THEN 1 ELSE 2 END") != 1:
                    raise SystemExit("追加形状只支持单表分类块: %s" % r["migrationId"])
                # qx-final7 起 pack 预检块自身已带这个形状（CI 演练第一遍后回读需要），不重复追加。
                if alt not in block:
                    block = block.replace("THEN 1 ELSE 2 END", alt)
                out.append("-- 也接受 %s 落地后的形状（f-rehearsal：否则全部执行后重跑预检会误判 CONFLICT）" % source)
            out.append(block.rstrip() + ";")
        else:
            text = (ROOT / r["path"]).read_text(encoding="utf-8")
            spec = NEW_META[r["migrationId"]][3]
            checks, summary = object_checks(text)
            if spec == "READBACK":
                checks, summary = [readback_expr(text, r["migrationId"])], "迁移自身只读断言"
            elif spec:
                checks, summary = [spec], r["note"]
            extra = EXTRA_CHECKS.get(r["migrationId"], [])
            if r["migrationId"] == "zz_questo_collation_align_20261109":
                names = sorted(set(re.findall(r"ALTER\s+TABLE\s+`?(\w+)", text, re.I)))
                extra = [extra[0] % ", ".join("'%s'" % n for n in names)]
                checks, summary = extra, "这些表里隐式 utf8mb4_0900_ai_ci 的列数为 0（%d 张表）" % len(names)
            elif r["migrationId"] in ("z_marketing_revenue_channel_reverse_kind_20261108",
                                      "z_marketing_revenue_channel_reverse_kind_index_shape_20261109",
                                      "marketing_redemption_constraint_name_20261108"):
                checks, summary = extra, "CHECK 约束已是新形状"
            elif r["migrationId"] == "20261102_club_session_gather_point":
                nxt = readback_expr((ROOT / "chengyinhub-admin/sql/migration_20261104_club_session_gather_point_readback.sql")
                                    .read_text(encoding="utf-8"), "gather_point_readback")
                checks, summary = [nxt], "复用 20261104 readback 的精确谓词：该填未填的场次为 0"
            else:
                checks = checks + extra
            if not checks:
                raise SystemExit("没有可检查的对象: %s" % r["migrationId"])
            out.append(classifier(r["step"], r["migrationId"], r["class"], r["phase"], checks, summary or r["note"]))
        out.append("")
    sql = "\n".join(out) + "\n"
    assert_read_only(sql, "release-precheck.sql")
    return sql


def render_md(head_sha, count, rows, rounds):
    lines = [
        "# 发布迁移清单（f-release，候选 `%s`）" % head_sha[:8],
        "",
        "由 `gen_release_set.py` 生成，勿手改。生产执行状态一律 **UNKNOWN**，以 `release-precheck.sql` 的结果为准。",
        "",
        "| 项 | 值 |",
        "|---|---|",
        "| 候选头 | `%s` |" % head_sha,
        "| 生产参考基线 | 10-29 发布包基线 `19408d49`（Merge #452）；10-22、10-28、10-29 三个包都没有在生产确认执行 |",
        "| 发布集 | %d 项 = 10-29 包 %d 项 + 10-29 之后 %d 项（其中 launch 波次 %d 项） |" % (
            len(rows), sum(1 for r in rows if r["packPhase"] is not None),
            sum(1 for r in rows if r["packPhase"] is None),
            sum(1 for r in rows if r["source"].startswith("launch"))),
        "| 候选头全量迁移数 | %d（执行序 order 取自其拓扑序） |" % count,
        "| 必须 / 可选 / 需业主授权 / 不执行 | %d / %d / %d / %d |" % tuple(sum(1 for r in rows if r["class"] == c) for c in (REQUIRED, OPTIONAL, OWNER, SKIP)),
        "",
        "## 执行轮次",
        "",
        "| 轮次 | 何时 | 项数 | 迁移（按 step） |",
        "|---|---|---|---|",
    ]
    when = {"1_PRE_REQUIRED": "部署 jar 前", "2_PRE_OPTIONAL": "部署 jar 前（可选，建议同批）",
            "3_POST_DEPLOY": "新 jar 健康后", "4_OWNER_DATA_PRE": "业主逐项授权后（jar 前后均可，开对应功能前）",
            "5_OWNER_DATA_POST": "业主授权后，且新 jar 健康后"}
    by_id = {r["migrationId"]: r for r in rows}
    for name in ROUND_NAMES:
        ids = rounds[name]
        lines.append("| %s | %s | %d | %s |" % (name, when[name], len(ids), "、".join("%d" % by_id[i]["step"] for i in ids) or "—"))
    lines += ["", "## 逐项清单", "",
              "| step | order | 文件 | 类别 | 阶段 | 重跑 | 来源 | 依赖（集内） | 说明 |",
              "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        deps = [d for d in r["dependsOn"] if d in by_id]
        lines.append("| %d | %d | `%s` | %s | %s | %s | %s | %s | %s |" % (
            r["step"], r["order"], r["path"].split("/")[-1], r["class"], r["phase"], r["rerun"], r["source"],
            "、".join("%d" % by_id[d]["step"] for d in deps) or "—", r["note"].replace("|", "／").replace("\n", " ")))
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--head", default="origin/launch/questo-20261110")
    ap.add_argument("--out", default=str(pathlib.Path(__file__).resolve().parent))
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    head_sha, count, rows, rounds = build(args.head)
    payload = {"schemaVersion": 1, "kind": "f-release-migration-set", "candidateHead": head_sha,
               "headMigrationCount": count, "productionAppliedState": "UNKNOWN",
               "baseline": {"pack": "docs/delivery/release-closeout-20261029/release-migration-pack.json",
                            "packSourceCommit": PACK_SOURCE, "productionReferenceBaseline": "19408d49",
                            "masterBriefBase": MASTER_BRIEF_BASE},
               "rounds": rounds, "migrations": rows}
    outputs = {
        "release-migrations.json": json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        "release-precheck.sql": render_precheck(head_sha, rows),
        "MIGRATIONS.md": render_md(head_sha, count, rows, rounds),
    }
    out = pathlib.Path(args.out)
    if args.check:
        drift = [n for n, t in outputs.items() if not (out / n).exists() or (out / n).read_text(encoding="utf-8") != t]
        if drift:
            print("产物与重新生成结果不一致: %s" % drift)
            return 1
        print("OK %d 项，产物一致" % len(rows))
        return 0
    for name, text in outputs.items():
        (out / name).write_text(text, encoding="utf-8")
    print("wrote %d migrations, rounds=%s" % (len(rows), {k: len(v) for k, v in rounds.items()}))
    return 0


if __name__ == "__main__":
    sys.exit(main())

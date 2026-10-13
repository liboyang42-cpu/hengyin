-- ============================================================
-- 只读预检(全 SELECT,绝不改库)。用途:确认 d78f18c 系列迁移是否已落生产库,
-- 判断小程序能否安全上体验版。本机出口 IP 不在白名单,需在白名单主机/宝塔 phpMyAdmin
-- 上对库 chengyinhubdb 执行本文件,逐段对照「期望」。
-- 库:jdbc:mysql://47.103.124.64:3306/chengyinhubdb
-- ============================================================
SET @db := 'chengyinhubdb';

-- 1) oms_ticket 成团列 —— 期望返回 8 行(全部齐)
--    min_people / max_people / signup_deadline / gather_lng / gather_lat / team_status / refund_rule / need_audit
SELECT 'oms_ticket 成团列' AS check_item, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'oms_ticket'
  AND COLUMN_NAME IN ('min_people','max_people','signup_deadline','gather_lng','gather_lat','team_status','refund_rule','need_audit')
ORDER BY COLUMN_NAME;

-- 2) club 索引 —— 期望:idx_club_member 存在(NON_UNIQUE=1)、uk_club_member 不存在(0 行)
--    若仍见 uk_club_member,则「再建一个俱乐部」会因唯一约束插入失败 → 体验版前端须隐藏该入口
SELECT 'club 索引' AS check_item, INDEX_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'club'
  AND INDEX_NAME IN ('uk_club_member','idx_club_member')
GROUP BY INDEX_NAME, NON_UNIQUE;

-- 3) 新表存在性 —— 期望返回 5 行(全部建好)
SELECT 'club/coop 新表' AS check_item, TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @db
  AND TABLE_NAME IN ('club_leader','club_post','club_post_like','club_post_comment','coop_invite')
ORDER BY TABLE_NAME;

-- 4) mms_merchant 承接档案列 —— 期望返回 6 行
SELECT 'mms_merchant 新列' AS check_item, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'mms_merchant'
  AND COLUMN_NAME IN ('capacity','available_time','suit_activity_types','charge_type','demand','coop_open')
ORDER BY COLUMN_NAME;

-- 5) cms_topic.club_id —— 期望返回 1 行
SELECT 'cms_topic.club_id' AS check_item, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'cms_topic' AND COLUMN_NAME = 'club_id';

-- 任一段返回行数 < 期望 → 对应迁移未落库,jar 部署/体验版上传前必须先按
-- chengyinhub-admin/sql/_deploy_d78f18c_ordered.sql 顺序补执行。

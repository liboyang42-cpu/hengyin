-- ============================================================================
-- 城瘾 迁移落库状态 只读预检 · 增补(2026-07-08)
-- 覆盖原 _precheck_migration_status.sql(2026-06-30,52个)之后新增的迁移 + 官方活动依赖。
-- 安全:纯 information_schema 只读,不改任何数据/结构。
-- 跑法:mysql -h <host> -u <user> -p <db> < _precheck_addendum_20260708.sql
-- 判定:每个迁移取一个"签名对象"(新建表/新增列)是否存在。✅已落 / ❌待落。
-- ❌待落 的必须"先于 jar"在生产执行(否则新 jar 的 Mapper 引用会 SQL 报错)。
-- ⚠️ 两个数据型迁移无法用 information_schema 测,见文末说明,须人工核:
--    migration_player_maxthemes_2_20260707(UPDATE user_permission)、
--    migration_downshelf_overquota_themes_20260707(数据回填)。
-- 要求:MySQL 8。
-- ============================================================================

WITH m AS (
  SELECT 100 ord,'member_collectible                (官方活动徽章依赖)' migration,'表 member_collectible' sig,
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_collectible'),'✅已落','❌待落') status
  UNION ALL SELECT 101,'registration_request_id','cms_registration.request_id',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_registration' AND COLUMN_NAME='request_id'),'✅已落','❌待落')
  UNION ALL SELECT 102,'roam(4表)','表 roam_poi',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='roam_poi'),'✅已落','❌待落')
  UNION ALL SELECT 103,'city_merchant_node','roam_poi.merchant_id',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='roam_poi' AND COLUMN_NAME='merchant_id'),'✅已落','❌待落')
  UNION ALL SELECT 104,'city_interaction_template','cms_member_template.interaction_type',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_member_template' AND COLUMN_NAME='interaction_type'),'✅已落','❌待落')
  UNION ALL SELECT 105,'coop_deposit','coop_invite.deposit_amount',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coop_invite' AND COLUMN_NAME='deposit_amount'),'✅已落','❌待落')
  UNION ALL SELECT 106,'topic_draft_20260707','cms_topic.is_draft',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_topic' AND COLUMN_NAME='is_draft'),'✅已落','❌待落')
  UNION ALL SELECT 107,'topic_template_20260707','cms_topic.is_template',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_topic' AND COLUMN_NAME='is_template'),'✅已落','❌待落')
  UNION ALL SELECT 108,'merchant_business_status_20260707','mms_merchant.business_status',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mms_merchant' AND COLUMN_NAME='business_status'),'✅已落','❌待落')
  UNION ALL SELECT 109,'merchant_decor_20260708','mms_merchant.cover_image',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mms_merchant' AND COLUMN_NAME='cover_image'),'✅已落','❌待落')
  UNION ALL SELECT 110,'member_growth(R0大墙·官方活动XP依赖)','表 member_growth',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_growth'),'✅已落','❌待落')
  UNION ALL SELECT 111,'official_event                    ★本轮','表 official_event',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='official_event'),'✅已落','❌待落')
  UNION ALL SELECT 112,'official_event(event_signup)      ★本轮','表 event_signup',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_signup'),'✅已落','❌待落')
  UNION ALL SELECT 113,'official_event(权限位)            ★本轮','user_permission.can_publish_official_event',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_permission' AND COLUMN_NAME='can_publish_official_event'),'✅已落','❌待落')
  UNION ALL SELECT 114,'official_broadcast                ★本轮','表 official_broadcast',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='official_broadcast'),'✅已落','❌待落')
  UNION ALL SELECT 115,'official_broadcast(log)           ★本轮','表 official_broadcast_log',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='official_broadcast_log'),'✅已落','❌待落')
)
SELECT ord AS 序, migration AS 迁移, sig AS 签名对象, status AS 状态 FROM m ORDER BY ord;

-- ============================================================================
-- 城瘾 迁移落库状态 只读预检
-- 生成:2026-06-30  对应 sql/migration_*.sql 共 52 个
-- 用途:在生产库上跑一次,直接看每个 migration 是否已落库(✅已落 / ❌待落 / ⚠️需人工核)
-- 安全:纯 SELECT / information_schema 只读,不改任何数据与结构。
-- 跑法(在能连生产库的服务器上):
--   mysql -h <host> -u <user> -p <db> < _precheck_migration_status.sql
--   或登入后:  source /path/to/_precheck_migration_status.sql
-- 判定依据:每个迁移取一个"签名对象"(它新建的表 / 新增的列)是否存在。
--   数据型迁移(只 INSERT 菜单/任务/回填,无 schema 变更)无法用 information_schema 测,标 ⚠️需人工核。
-- 要求:MySQL 8(用到 WITH CTE)。
-- ============================================================================

WITH m AS (
  -- ---- 列存在型(ALTER TABLE ADD COLUMN);列存在=已落 ----
  SELECT 11 ord,'login_identity' migration,'ums_member.union_id' sig,
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ums_member' AND COLUMN_NAME='union_id'),'✅已落','❌待落') status
  UNION ALL SELECT 12,'rbac_role','ums_member.role',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ums_member' AND COLUMN_NAME='role'),'✅已落','❌待落')
  UNION ALL SELECT 13,'square_route_preview','ums_creative_square.route_preview_img',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ums_creative_square' AND COLUMN_NAME='route_preview_img'),'✅已落','❌待落')
  UNION ALL SELECT 14,'topic_audio','cms_topic.audio_url',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_topic' AND COLUMN_NAME='audio_url'),'✅已落','❌待落')
  UNION ALL SELECT 15,'club_topic_link','cms_topic.club_id',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_topic' AND COLUMN_NAME='club_id'),'✅已落','❌待落')
  UNION ALL SELECT 16,'activity_topic_id','cms_activity.topic_id',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_activity' AND COLUMN_NAME='topic_id'),'✅已落','❌待落')
  UNION ALL SELECT 17,'activity_designer_commission','cms_setting.activity_designer_commission',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_setting' AND COLUMN_NAME='activity_designer_commission'),'✅已落','❌待落')
  UNION ALL SELECT 18,'multi_ticket_signup','cms_registration.participate_date',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_registration' AND COLUMN_NAME='participate_date'),'✅已落','❌待落')
  UNION ALL SELECT 19,'play_progress_points','cms_topic_node.checkin_code',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_topic_node' AND COLUMN_NAME='checkin_code'),'✅已落','❌待落')
  UNION ALL SELECT 20,'merchant_pool_dispatch','cms_registration_merchant.audit_status',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_registration_merchant' AND COLUMN_NAME='audit_status'),'✅已落','❌待落')
  UNION ALL SELECT 21,'merchant_coop_profile','mms_merchant.capacity',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mms_merchant' AND COLUMN_NAME='capacity'),'✅已落','❌待落')
  UNION ALL SELECT 22,'c3_team_fields','oms_ticket.min_people',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='oms_ticket' AND COLUMN_NAME='min_people'),'✅已落','❌待落')
  UNION ALL SELECT 23,'c3_growth_badge_club','growth_badge.club_id',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='growth_badge' AND COLUMN_NAME='club_id'),'✅已落','❌待落')
  UNION ALL SELECT 24,'m1_permission_full','user_permission.max_nodes_per_theme',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_permission' AND COLUMN_NAME='max_nodes_per_theme'),'✅已落','❌待落')
  UNION ALL SELECT 25,'m5_medal','cms_member_template.medal_img',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_member_template' AND COLUMN_NAME='medal_img'),'✅已落','❌待落')
  UNION ALL SELECT 26,'level_editor_fields','cms_member_template.story_text',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_member_template' AND COLUMN_NAME='story_text'),'✅已落','❌待落')
  UNION ALL SELECT 27,'level_editor_story_photo_review','cms_member_template.story_json',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cms_member_template' AND COLUMN_NAME='story_json'),'✅已落','❌待落')
  UNION ALL SELECT 28,'club_fields','club.club_types',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club' AND COLUMN_NAME='club_types'),'✅已落','❌待落')
  UNION ALL SELECT 29,'club_type','club.club_type',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club' AND COLUMN_NAME='club_type'),'✅已落','❌待落')
  UNION ALL SELECT 30,'club_apply_fields','club.city',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club' AND COLUMN_NAME='city'),'✅已落','❌待落')
  UNION ALL SELECT 31,'club_audit_level','club.level',
    IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club' AND COLUMN_NAME='level'),'✅已落','❌待落')

  -- ---- 建表型(CREATE TABLE);表存在=已落 ----
  UNION ALL SELECT 40,'im','im_conversation',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='im_conversation'),'✅已落','❌待落')
  UNION ALL SELECT 41,'ai_generation_log','ai_generation_log',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_generation_log'),'✅已落','❌待落')
  UNION ALL SELECT 42,'ai_merchant_match_snapshot','ai_merchant_match_snapshot',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_merchant_match_snapshot'),'✅已落','❌待落')
  UNION ALL SELECT 43,'member_capability','member_capability',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_capability'),'✅已落','❌待落')
  UNION ALL SELECT 44,'coop_invite','coop_invite',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coop_invite'),'✅已落','❌待落')
  UNION ALL SELECT 45,'preset_route','preset_route',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='preset_route'),'✅已落','❌待落')
  UNION ALL SELECT 46,'npc_profile','npc_profile',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_profile'),'✅已落','❌待落')
  UNION ALL SELECT 47,'npc_chat_log','npc_chat_log',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_chat_log'),'✅已落','❌待落')
  UNION ALL SELECT 48,'npc_line_cache','npc_line_cache',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_line_cache'),'✅已落','❌待落')
  UNION ALL SELECT 49,'club','club',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club'),'✅已落','❌待落')
  UNION ALL SELECT 50,'club_leader','club_leader',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club_leader'),'✅已落','❌待落')
  UNION ALL SELECT 51,'club_post','club_post',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club_post'),'✅已落','❌待落')
  UNION ALL SELECT 52,'club_post_comment','club_post_comment',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club_post_comment'),'✅已落','❌待落')
  UNION ALL SELECT 53,'club_post_like','club_post_like',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='club_post_like'),'✅已落','❌待落')
  UNION ALL SELECT 54,'p2_player_growth_badge','player_growth',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='player_growth'),'✅已落','❌待落')
  UNION ALL SELECT 55,'p3_phase_b_player_world_creator','member_growth',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_growth'),'✅已落','❌待落')
  UNION ALL SELECT 56,'p3_platform_foundation','biz_event_log',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_event_log'),'✅已落','❌待落')
  UNION ALL SELECT 57,'p3_phase_c_crm_finance','merchant_customer_profile',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='merchant_customer_profile'),'✅已落','❌待落')
  UNION ALL SELECT 58,'p3_phase_c_web_rules_refund','settlement_rule',
    IF(EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='settlement_rule'),'✅已落','❌待落')

  -- ---- 特殊型 ----
  UNION ALL SELECT 60,'club_multi','club 唯一索引(SHOW INDEX FROM club 看 member_id 唯一是否已放开)','⚠️需人工核'
  UNION ALL SELECT 61,'p3_phase_b_retire_energy','member_growth.energy_value(应已删)',
    CASE
      WHEN NOT EXISTS(SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_growth') THEN '前置未落(member_growth 不存在)'
      WHEN EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_growth' AND COLUMN_NAME='energy_value') THEN '❌待落(energy_value 仍在)'
      ELSE '✅已落(energy_value 已删)'
    END

  -- ---- 数据/菜单/任务型(无 schema 变更,information_schema 测不到,需人工核)----
  UNION ALL SELECT 80,'club_menu_grant','sys_role_menu 授权行','⚠️需人工核(数据)'
  UNION ALL SELECT 81,'club_topic_backfill','cms_topic.club_id 回填值','⚠️需人工核(数据)'
  UNION ALL SELECT 82,'im_menu','sys_menu IM 菜单行','⚠️需人工核(数据)'
  UNION ALL SELECT 83,'preset_menu','sys_menu 预设菜单行','⚠️需人工核(数据)'
  UNION ALL SELECT 84,'userpermission_menu','sys_menu 权限菜单行','⚠️需人工核(数据)'
  UNION ALL SELECT 85,'p1_rbac_permission_defaults','user_permission 默认行','⚠️需人工核(数据)'
  UNION ALL SELECT 86,'p0_clean_member_security_probe','ums_member 探针清理','⚠️需人工核(数据)'
  UNION ALL SELECT 90,'c3_autosettle_job','sys_job 自动结算任务','⚠️需人工核(任务·须 jar 先部署)'
  UNION ALL SELECT 91,'c3_beforestart_job','sys_job 开赛前通知任务','⚠️需人工核(任务·须 jar 先部署)'
  UNION ALL SELECT 92,'c3_team_job','sys_job 成团/退款任务','⚠️需人工核(任务·须 jar 先部署)'
)
SELECT migration AS '迁移', sig AS '签名对象', status AS '状态' FROM m ORDER BY ord;

-- 精确汇总
WITH m AS (
  SELECT IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ums_member' AND COLUMN_NAME='union_id'),'✅','x') s
  -- 注:汇总区与上方明细一一对应需复制整段 UNION;为避免维护两份,汇总请直接对上方结果按状态列肉眼/Excel 统计。
)
SELECT '汇总请按上表「状态」列统计:✅已落 / ❌待落 / ⚠️需人工核' AS note FROM m;

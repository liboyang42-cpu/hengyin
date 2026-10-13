-- ============================================================
-- 部署前只读预检(2026-07-09)—— 纯查 information_schema,零改动
-- 用法(宝塔终端):mysql -uchengyinhubdb -p chengyinhubdb < 本文件
-- 或把整段粘进 mysql 交互里。看 status 列:❌待落=要跑,✅已落=跳过
-- 每个多列迁移用「最后一列」当哨兵:哨兵在=整条已跑
-- ============================================================
SET @db := 'chengyinhubdb';

-- 列是否存在
SELECT mig, chk,
  IF((SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=@db AND TABLE_NAME=t AND COLUMN_NAME=c)>0,'✅已落','❌待落') status
FROM (
  SELECT 'A1 rbac_role'          mig, 'ums_member.role(哨兵)'              chk, 'ums_member' t, 'role' c
  UNION ALL SELECT 'A2 m1_permission_full','user_permission.withdrawable(哨兵·11列末)','user_permission','withdrawable'
  UNION ALL SELECT 'D  city_merchant_node','user_permission.max_city_nodes','user_permission','max_city_nodes'
  UNION ALL SELECT 'B1 merchant_geo',     'mms_merchant.location_verified(哨兵·3列末)','mms_merchant','location_verified'
  UNION ALL SELECT 'B2 merchant_decor',   'mms_merchant.featured_id(哨兵·8列末)',     'mms_merchant','featured_id'
  UNION ALL SELECT 'D  merchant_business_status','mms_merchant.business_status','mms_merchant','business_status'
  UNION ALL SELECT 'D  city_interaction_template','cms_member_template.interaction_type','cms_member_template','interaction_type'
  UNION ALL SELECT 'D  registration_request_id','cms_registration.request_id','cms_registration','request_id'
) x
UNION ALL
-- 表是否存在
SELECT mig, chk,
  IF((SELECT COUNT(*) FROM information_schema.TABLES
      WHERE TABLE_SCHEMA=@db AND TABLE_NAME=t)>0,'✅已落','❌待落') status
FROM (
  SELECT 'A1 rbac_role'          mig, 'table user_permission' chk, 'user_permission' t
  UNION ALL SELECT 'B3 merchant_subscription','table merchant_subscription','merchant_subscription'
  UNION ALL SELECT 'D  official_event',  'table official_event',  'official_event'
  UNION ALL SELECT 'D  official_event',  'table event_signup',    'event_signup'
  UNION ALL SELECT 'D  official_broadcast','table official_broadcast','official_broadcast'
  UNION ALL SELECT 'D  official_broadcast','table official_broadcast_log','official_broadcast_log'
  UNION ALL SELECT 'D  roam(四表)',      'table roam_session',    'roam_session'
  UNION ALL SELECT 'D  roam(四表)',      'table roam_poi',        'roam_poi'
  UNION ALL SELECT 'D  roam(四表)',      'table roam_fog_tile',   'roam_fog_tile'
  UNION ALL SELECT 'D  roam(四表)',      'table roam_poi_found',  'roam_poi_found'
  UNION ALL SELECT 'D  city_merchant_node','table roam_poi_reward_log','roam_poi_reward_log'
  UNION ALL SELECT 'D  member_collectible','table member_collectible','member_collectible'
) y
ORDER BY mig, chk;

-- C 组(jar 之后)+ 种子行数,单独看
SELECT 'C1 sys_job=110'  item, IFNULL((SELECT CONCAT('存在:',status) FROM sys_job  WHERE job_id=110 ),'❌待落') val
UNION ALL SELECT 'C2 sys_menu=2192', IFNULL((SELECT CONCAT('存在:',menu_name) FROM sys_menu WHERE menu_id=2192),'❌待落')
UNION ALL SELECT 'A3 user_permission 种子行数', IFNULL((SELECT CONCAT(COUNT(*),' 行') FROM user_permission),'表不存在');

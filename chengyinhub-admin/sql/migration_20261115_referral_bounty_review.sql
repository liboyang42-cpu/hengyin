-- R9 BE-13 拓展人激活赏金审核队列(2026-11-15)
-- 回退:菜单 DELETE FROM sys_menu WHERE perms IN ('business:referralbounty:review','business:referralbounty:list');
-- 回退:三列只增不改旧值,回退时可保留;若必须回退,先确认 status IN (4,5) 的行已人工处理,再
--   ALTER TABLE merchant_referral_bounty DROP COLUMN review_note, DROP COLUMN review_time, DROP COLUMN review_by;
--   并把应用配置 chengyin.referral-bounty.auto-pay 设为 true(旧行为:达成即入账,仍受资金开关约束)。
-- 新状态值:4=待审核(达成激活、未入账) 5=已驳回。status 列是 TINYINT,无需改类型。
-- 幂等:每列先查 information_schema,存在则跳过。

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_referral_bounty' AND COLUMN_NAME = 'review_by');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE merchant_referral_bounty ADD COLUMN review_by VARCHAR(64) NULL COMMENT ''审核人(sys:<userId>)'' AFTER clawback_reason',
  'SELECT ''review_by exists'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_referral_bounty' AND COLUMN_NAME = 'review_time');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE merchant_referral_bounty ADD COLUMN review_time DATETIME NULL COMMENT ''审核时间'' AFTER review_by',
  'SELECT ''review_time exists'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_referral_bounty' AND COLUMN_NAME = 'review_note');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE merchant_referral_bounty ADD COLUMN review_note VARCHAR(255) NULL COMMENT ''审核备注/驳回原因'' AFTER review_time',
  'SELECT ''review_note exists'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 队列按状态筛选 + 新到旧
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_referral_bounty' AND INDEX_NAME = 'idx_status_id');
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE merchant_referral_bounty ADD KEY idx_status_id (status, id)',
  'SELECT ''idx_status_id exists'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 后台菜单:资金与交易 → 拓展人赏金(只建 sys_menu,不授权任何角色;超管可直接访问)。父目录不存在时不插。
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '拓展人赏金',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare' LIMIT 1) m),63,'referral-bounty','business/referralbounty/index',1,0,'C','0','0','business:referralbounty:list','money','migration',NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:referralbounty:list')
  AND EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '赏金审核',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:referralbounty:list' LIMIT 1) m),1,'#','',1,0,'F','0','0','business:referralbounty:review','#','migration',NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:referralbounty:review')
  AND EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:referralbounty:list');

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_referral_bounty'
           AND COLUMN_NAME IN ('review_by', 'review_time', 'review_note')) = 3 AS referral_bounty_review_ready;
